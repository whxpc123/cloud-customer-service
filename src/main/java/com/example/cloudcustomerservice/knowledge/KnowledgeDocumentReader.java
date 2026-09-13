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

/** 本地文件只解析为正文，不保存原文件，不执行 Markdown 中的链接或 HTML。 */
@Component
@Profile("local & knowledge")
public class KnowledgeDocumentReader {
    public static final int MAX_TEXT_LENGTH = 50_000;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

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

    public static String normalize(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("正文最多 50000 字符");
        String normalized = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("正文不能为空");
        if (normalized.indexOf('\0') >= 0) throw new IllegalArgumentException("正文包含二进制内容，请上传纯文本文件");
        return normalized;
    }

    private static final class LimitedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] chars, int offset, int length) {
            if (text.length() + length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("提取后的正文超过 50000 字符，请拆分文件后上传");
            text.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
