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

class KnowledgeDocumentReaderTest {
    private final KnowledgeDocumentReader reader = new KnowledgeDocumentReader();

    @Test void readsUtf8TextAndMarkdownAndNormalizesLineEndings() {
        for (String name : new String[]{"政策.txt", "政策.MD", "政策.markdown"}) {
            assertThat(reader.read(file(name, "\uFEFF退货政策\r\n需保持商品完好。".getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("退货政策\n需保持商品完好。");
        }
    }
    @Test void extractsTextFromActualPdf() throws Exception {
        assertThat(reader.read(file("rules.pdf", pdf("Moonlight support is open on Thursday."))))
                .contains("Moonlight support is open on Thursday.");
    }
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
    @Test void uploadEndpointPassesExtractedTextAndSafeNameAndReportsErrors() throws Exception {
        var service = mock(CustomKnowledgeImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CustomKnowledgeImportController(service, reader))
                .setControllerAdvice(new KnowledgeErrors()).build();
        when(service.importText("rules.pdf", "A real PDF policy.")).thenReturn(new CustomKnowledgeImportResult("id","rules.pdf","v",1,18));
        mvc.perform(multipart("/internal/knowledge/import/file").file(file("C:\\fakepath\\rules.pdf",pdf("A real PDF policy."))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedDocuments").value(1));
        verify(service).importText("rules.pdf", "A real PDF policy.");
        mvc.perform(multipart("/internal/knowledge/import/file").file(file("bad.pdf",new byte[]{1})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoMoreInteractions(service);
    }
    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }
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
