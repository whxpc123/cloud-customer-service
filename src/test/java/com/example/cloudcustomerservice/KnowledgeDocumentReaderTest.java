package com.example.cloudcustomerservice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.example.cloudcustomerservice.knowledge.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 文件读取与上传契约测试，使用内存生成的真实 PDF 字节及 multipart 请求。
 * 向量模型和数据库写入使用替身；读取成功与发布成功是不同验证层次。
 */

class KnowledgeDocumentReaderTest {
    private final KnowledgeDocumentReader reader = new KnowledgeDocumentReader();

    /**
     * 用带 BOM 和 CRLF 的真实字节验证 UTF-8 文本与 Markdown 读取，结果统一为正常正文换行。
     */
    @Test void readsUtf8TextAndMarkdownAndNormalizesLineEndings() {
        for (String name : new String[]{"政策.txt", "政策.MD", "政策.markdown"}) {
            assertThat(reader.read(file(name, "\uFEFF退货政策\r\n需保持商品完好。".getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("退货政策\n需保持商品完好。");
        }
    }
    /**
     * 在内存生成含文字的真实 PDF，再通过读取器提取，避免仅用文件后缀模拟 PDF 支持。
     */
    @Test void extractsTextFromActualPdf() throws Exception {
        assertThat(reader.read(file("rules.pdf", pdf("Moonlight support is open on Thursday."))))
                .contains("Moonlight support is open on Thursday.");
    }
    /**
     * 覆盖扫描件、损坏内容、非法编码及文件/正文超限，检查拒绝行为和可读说明。
     */
    @Test void rejectsEmptyScannedInvalidAndOversizedFiles() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(file("scan.pdf", pdf(null))))
                .withMessageContaining("OCR");
        for (var invalid : new MockMultipartFile[]{file("empty.txt", new byte[0]), file("a.docx", new byte[]{1}),
                file("bad.pdf", new byte[]{1,2,3}), file("binary.txt", new byte[]{0,1}),
                file("gbk.txt", new byte[]{(byte)0xff}), file("large.txt", new byte[KnowledgeDocumentReader.MAX_FILE_BYTES + 1])}) {
            assertThatIllegalArgumentException().isThrownBy(() -> reader.read(invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> KnowledgeDocumentReader.normalize("x".repeat(50001)));
    }
    /**
     * 通过 multipart 上传真实 PDF，捕获准备后写入参数，再测试错误文件不调用向量和写入依赖。
     */
    @Test void uploadEndpointPassesExtractedTextAndSafeNameAndReportsErrors() throws Exception {
        var preparation = new com.example.cloudcustomerservice.knowledge.ingestion.KnowledgePreparationService(
                new com.example.cloudcustomerservice.knowledge.ingestion.KnowledgeDocumentReaderFactory());
        var writer=mock(com.example.cloudcustomerservice.knowledge.ingestion.KnowledgeBatchWriter.class);
        var model=mock(org.springframework.ai.embedding.EmbeddingModel.class);
        when(model.call(any())).thenAnswer(inv->{float[] vector=new float[1024];vector[0]=1;
            return new org.springframework.ai.embedding.EmbeddingResponse(java.util.List.of(new org.springframework.ai.embedding.Embedding(vector,0)));});
        var service = new CustomKnowledgeImportService(preparation,model,writer);
        var mvc = MockMvcBuilders.standaloneSetup(new CustomKnowledgeImportController(service))
                .setControllerAdvice(new KnowledgeErrors()).build();
        mvc.perform(multipart("/internal/knowledge/import/file").file(file("C:\\fakepath\\rules.pdf",pdf("A real PDF policy."))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedDocuments").value(1)).andExpect(jsonPath("$.sourceName").value("rules.pdf"));
        verify(writer).replace(argThat(p->p.chunks().get(0).getText().contains("A real PDF policy.")),anyList());
        clearInvocations(writer,model);
        mvc.perform(multipart("/internal/knowledge/import/file").file(file("bad.pdf",new byte[]{1})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(writer,model);
    }

    /**
     * 在内存包装 multipart 文件字节，用于测试上传与读取流程而不访问用户文件。
     */
    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }
    /**
     * 在内存创建真实 PDF；正文为空时生成无文字页面，用于模拟需要 OCR 的扫描件边界。
     */
    static byte[] pdf(String text) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            if (text != null) try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(40,700);stream.showText(text);stream.endText();
            }
            document.save(output);return output.toByteArray();
        }
    }
}
