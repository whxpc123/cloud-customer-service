package com.example.cloudcustomerservice.knowledge.ingestion;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import com.example.cloudcustomerservice.knowledge.KnowledgeDocumentReader;
import org.springframework.ai.document.*;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.apache.tika.sax.BodyContentHandler;

/**
 * 第八章按文件类型选择 Spring AI Reader，统一返回 Document 列表。
 * 文本验证编码；PDF 保留页号并释放资源；Office 文件在 Tika 解析前检查压缩包规模。
 * 这里只提取正文，不执行附件中的代码、命令或链接，也不具备扫描件 OCR 能力。
 */
@Component
@Profile("local & knowledge")
public class KnowledgeDocumentReaderFactory {
    /**
     * 规范化路径分隔符后只保留文件基本名；拒绝空、控制字符和超长名称。
     */
    public static String fileName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少文件名");
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("文件名须为 1–120 字符");
        return name;
    }
    /**
     * 按不区分大小写的后缀选择 Reader；Excel 暂未实现按行语义解析，因此显式拒绝。
     */
    public static String fileType(String name) {
        String suffix = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "txt" -> "TEXT";
            case "md", "markdown" -> "MARKDOWN";
            case "pdf" -> "PDF";
            case "docx" -> "DOCX";
            case "pptx" -> "PPTX";
            default -> throw new IllegalArgumentException("支持 TXT、Markdown、PDF、DOCX、PPTX；Excel FAQ 需要专门的按行解析");
        };
    }
    /**
     * 把受限字节数组包装为带文件名 Resource，选择对应 Reader 后统一核验总正文长度。
     * UTF-8 解码错误直接拒绝，不用替换字符掩盖乱码；读取器异常转为可展示的输入提示。
     */
    public List<Document> read(KnowledgeSource source, byte[] bytes, ChunkingOptions options) {
        if (bytes.length == 0 || bytes.length > KnowledgeDocumentReader.MAX_FILE_BYTES)
            throw new IllegalArgumentException("文件须非空且不能超过 5 MB");
        Resource resource = new ByteArrayResource(bytes) {
            /**
             * 提供显示文件名，供 Reader 识别来源；不表示允许访问此文件系统路径。
             */
            @Override public String getFilename() { return source.fileName(); } };
        try {
            DocumentReader reader;
            switch (source.fileType()) {
                case "TEXT", "MARKDOWN" -> {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    text = KnowledgeDocumentReader.normalize(text);
                    byte[] normalizedBytes = text.getBytes(StandardCharsets.UTF_8);
                    Resource utf8 = new ByteArrayResource(normalizedBytes) {
            /**
             * 提供显示文件名，供 Reader 识别来源；不表示允许访问此文件系统路径。
             */
            @Override public String getFilename() { return source.fileName(); } };
                    reader = source.fileType().equals("TEXT") ? new TextReader(utf8) : new MarkdownDocumentReader(utf8,
                            MarkdownDocumentReaderConfig.builder().withHorizontalRuleCreateDocument(true)
                                    .withIncludeCodeBlock(false).withIncludeBlockquote(true).build());
                }
                case "PDF" -> reader = new ClosingPdfReader(resource, options);
                case "DOCX", "PPTX" -> {
                    checkOfficeArchive(bytes, source.fileType());
                    reader = new TikaDocumentReader(resource, new BodyContentHandler(50_000), ExtractedTextFormatter.defaults());
                }
                default -> throw new IllegalArgumentException("不支持的文件类型");
            }
            List<Document> documents = reader.read();
            if (documents.stream().mapToInt(d -> d.getText() == null ? 0 : d.getText().length()).sum() > 50_000)
                throw new IllegalArgumentException("提取后的正文超过 50000 字符，请拆分文件");
            // includeCodeBlock=false 在此 Spring AI 版本表示单独输出代码块，不是丢弃，需显式过滤。
            return documents.stream().filter(d -> !"code_block".equals(d.getMetadata().get("category"))).toList();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("TXT / Markdown 必须使用 UTF-8 编码");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("文件无法解析或超过读取限制，请检查是否损坏、加密或格式不符");
        }
    }
    /**
     * Office 文档实质是 ZIP：流式检查条目数、展开总量及关键 XML，不将内部路径解压到磁盘。
     * 5 MB 上传限制不足以控制解压规模，因此额外限制 2000 条目与 20 MB 展开数据。
     */
    private static void checkOfficeArchive(byte[] bytes, String type) throws IOException {
        int count = 0, total = 0; boolean documentFound = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 2000) throw new IllegalArgumentException("Office 文件包含过多内部条目");
                documentFound |= entry.getName().equals(type.equals("DOCX") ? "word/document.xml" : "ppt/presentation.xml");
                for (int read; (read = zip.read(buffer)) != -1;) {
                    total += read;
                    if (total > 20 * 1024 * 1024) throw new IllegalArgumentException("Office 文件解压内容超过 20 MB");
                }
            }
        }
        if (!documentFound) throw new IllegalArgumentException("文件内容与 DOCX / PPTX 格式不符");
    }
    /**
     * Spring AI 1.1.2 的 Reader 未自行关闭 PDDocument；按页读取后显式释放。
     */
    private static final class ClosingPdfReader extends PagePdfDocumentReader {
        /**
         * 按页形成 Document 以保留页号；默认不裁掉页面边距，可按配置删除页首页尾文字行。
         */
        ClosingPdfReader(Resource resource, ChunkingOptions options) throws IOException {
            super(resource, PdfDocumentReaderConfig.builder().withPagesPerDocument(1)
                    .withPageTopMargin(0).withPageBottomMargin(0)
                    .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                            .withNumberOfTopTextLinesToDelete(options.pdfTopLines())
                            .withNumberOfBottomTextLinesToDelete(options.pdfBottomLines()).build()).build());
        }
        /**
         * 先校验权限、页数和全文字符上限，再调用父类按页读取；finally 显式释放 PDDocument。
         */
        @Override public List<Document> get() {
            try {
                if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                    throw new IllegalArgumentException("请上传未加密且允许提取文字的 PDF");
                if (document.getNumberOfPages() > 100) throw new IllegalArgumentException("PDF 不能超过 100 页");
                // 先以有长度上限的 Writer 检查全文，避免后续布局提取意外生成超长内容。
                var counter = new Writer() {
                    int count;
                    /**
                     * 每次写入时累计字符数，超过正文上限立即终止 PDF 提取。
                     */
                    @Override public void write(char[] c, int offset, int length) {
                        count += length;
                        if (count > 50_000) throw new IllegalArgumentException("提取后的正文超过 50000 字符");
                    }
                    /**
                     * 只有内存缓冲或计数，没有外部流需要刷新。
                     */
                    @Override public void flush() { }
                    /**
                     * Writer 不拥有 PDF 资源，真正的文档由外层 finally 或 try-with-resources 关闭。
                     */
                    @Override public void close() { }
                };
                new org.apache.pdfbox.text.PDFTextStripper().writeText(document, counter);
                return super.get();
            } catch (IOException ex) {
                throw new IllegalArgumentException("PDF 文字读取失败");
            } finally {
                try { document.close(); } catch (IOException ignored) { }
            }
        }
    }
}
