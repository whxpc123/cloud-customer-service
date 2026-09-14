package com.example.cloudcustomerservice.knowledge;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 第七章的文件读取实现，保留供旧章节与读取测试使用；新 ETL 入口使用 ReaderFactory。
 * 支持严格 UTF-8 文本及可提取文字的 PDF，设置文件大小、页数和正文长度边界。
 */
@Component
@Profile("local & knowledge")
public class KnowledgeDocumentReader {
    public static final int MAX_TEXT_LENGTH = 50_000;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    /**
     * 根据扩展名读取 UTF-8 或 PDF；先限制上传字节数，再限制解析页数和正文长度。
     * PDF 使用 try-with-resources 释放文档，扫描件无文字时提示先 OCR，不假装导入成功。
     */
    public String read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空文件");
        if (file.getSize() > MAX_FILE_BYTES) throw new IllegalArgumentException("文件不能超过 5 MB");
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 TXT、Markdown 和含文字的 PDF 文件");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("文件不能超过 5 MB");
            if (!lower.endsWith(".pdf")) {
                return normalize(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
            }
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent()) {
                    throw new IllegalArgumentException("请上传未加密且允许提取文字的 PDF");
                }
                if (pdf.getNumberOfPages() > 100) throw new IllegalArgumentException("PDF 不能超过 100 页");
                var writer = new LimitedTextWriter();
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.writeText(pdf, writer);
                if (writer.text.toString().isBlank()) {
                    throw new IllegalArgumentException("PDF 未提取到文字，扫描版请先进行 OCR，再上传文字版 PDF 或 TXT");
                }
                return normalize(writer.text.toString());
            }
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("TXT / Markdown 必须使用 UTF-8 编码，请转换后上传");
        } catch (IOException ex) {
            throw new IllegalArgumentException("文件无法解析，请检查文件是否损坏、加密或格式不符");
        }
    }

    /**
     * 统一 BOM 与换行并去掉首尾空白；拒绝超长、空白以及含 NUL 的二进制内容。
     */
    public static String normalize(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("正文最多 50000 字符");
        String normalized = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("正文不能为空");
        if (normalized.indexOf('\0') >= 0) throw new IllegalArgumentException("正文包含二进制内容，请上传纯文本文件");
        return normalized;
    }

    /**
     * 有界的内存 Writer，在 PDF 提取过程中累计文本，超过上限立即停止。
     * flush 和 close 无需操作：本对象只有 StringBuilder，没有需要关闭的文件句柄。
     */
    private static final class LimitedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        /**
         * 每次写入时累计字符数，超过正文上限立即终止 PDF 提取。
         */
        @Override public void write(char[] chars, int offset, int length) {
            if (text.length() + length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("提取后的正文超过 50000 字符，请拆分文件后上传");
            text.append(chars, offset, length);
        }
        /**
         * 只有内存缓冲或计数，没有外部流需要刷新。
         */
        @Override public void flush() { }
        /**
         * Writer 不拥有 PDF 资源，真正的文档由外层 finally 或 try-with-resources 关闭。
         */
        @Override public void close() { }
    }
}
