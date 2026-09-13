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

@Component
@Profile("local & knowledge")
public class KnowledgeDocumentReaderFactory {
    public static String fileName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少文件名");
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("文件名须为 1–120 字符");
        return name;
    }
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
    public List<Document> read(KnowledgeSource source, byte[] bytes, ChunkingOptions options) {
        if (bytes.length == 0 || bytes.length > KnowledgeDocumentReader.MAX_FILE_BYTES)
            throw new IllegalArgumentException("文件须非空且不能超过 5 MB");
        Resource resource = new ByteArrayResource(bytes) { @Override public String getFilename() { return source.fileName(); } };
        try {
            DocumentReader reader;
            switch (source.fileType()) {
                case "TEXT", "MARKDOWN" -> {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    text = KnowledgeDocumentReader.normalize(text);
                    byte[] normalizedBytes = text.getBytes(StandardCharsets.UTF_8);
                    Resource utf8 = new ByteArrayResource(normalizedBytes) { @Override public String getFilename() { return source.fileName(); } };
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
    /** Spring AI 1.1.2 的 Reader 未自行关闭 PDDocument；按页读取后显式释放。 */
    private static final class ClosingPdfReader extends PagePdfDocumentReader {
        ClosingPdfReader(Resource resource, ChunkingOptions options) throws IOException {
            super(resource, PdfDocumentReaderConfig.builder().withPagesPerDocument(1)
                    .withPageTopMargin(0).withPageBottomMargin(0)
                    .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                            .withNumberOfTopTextLinesToDelete(options.pdfTopLines())
                            .withNumberOfBottomTextLinesToDelete(options.pdfBottomLines()).build()).build());
        }
        @Override public List<Document> get() {
            try {
                if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                    throw new IllegalArgumentException("请上传未加密且允许提取文字的 PDF");
                if (document.getNumberOfPages() > 100) throw new IllegalArgumentException("PDF 不能超过 100 页");
                // 先以有长度上限的 Writer 检查全文，避免后续布局提取意外生成超长内容。
                var counter = new Writer() {
                    int count;
                    @Override public void write(char[] c, int offset, int length) {
                        count += length;
                        if (count > 50_000) throw new IllegalArgumentException("提取后的正文超过 50000 字符");
                    }
                    @Override public void flush() { }
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
