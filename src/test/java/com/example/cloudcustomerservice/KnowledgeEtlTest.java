package com.example.cloudcustomerservice;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.knowledge.ingestion.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 第八章文档 ETL 测试，真实读取 Markdown、PDF、DOCX、PPTX，并检查 Unicode 与稳定 ID。
 * 预览测试使用模拟导入服务与可控时钟，验证无模型调用、重复提交复用和到期失效。
 */

class KnowledgeEtlTest {
    final KnowledgePreparationService preparation=new KnowledgePreparationService(new KnowledgeDocumentReaderFactory());
    /**
     * 读取课程 Markdown 两次对比稳定 ID 与元数据，并改变内容验证哈希和 ID 随正文变化。
     */
    @Test void markdownRetainsRulesHeadingsMetadataAndStableIds() throws Exception {
        byte[] bytes=new ClassPathResource("knowledge/refund-policy-v3.2.md").getContentAsByteArray();
        var a=preparation.bytes("售后制度","3.2","policy.md",bytes,ChunkingOptions.defaults());
        var b=preparation.bytes("售后制度","3.2","policy.md",bytes,ChunkingOptions.defaults());
        assertThat(a.chunks()).extracting(Document::getId).containsExactlyElementsOf(b.chunks().stream().map(Document::getId).toList());
        assertThat(a.chunks()).extracting(Document::getMetadata).containsExactlyElementsOf(b.chunks().stream().map(Document::getMetadata).toList());
        assertThat(a.chunks()).hasSize(4);
        assertThat(a.chunks()).anySatisfy(d->assertThat(d.getText()).contains("特殊商品","质量问题的除外"));
        a.chunks().forEach(d->assertThat(d.getMetadata()).containsKeys("tenantId","sourceId","sourceVersion","sourceFileName","chunkHash","chunkingVersion","embeddingProfile","title","rawDocumentIndex","language"));
        var changed=preparation.bytes("售后制度","3.2","policy.md",new String(bytes,StandardCharsets.UTF_8).replace("七日","十五日").getBytes(StandardCharsets.UTF_8),ChunkingOptions.defaults());
        assertThat(changed.chunks().get(0).getId()).isNotEqualTo(a.chunks().get(0).getId());
        assertThat(changed.chunks().get(0).getMetadata().get("chunkHash")).isNotEqualTo(a.chunks().get(0).getMetadata().get("chunkHash"));
    }
    /**
     * 用中文、emoji 和短例外测试三个 Token 档位，拼回非空白正文应一致且不含替换符。
     */
    @Test void tokenSizesChangeCountWithoutLosingUnicodeOrTinyException() {
        String text=("退货条件需要核对原始订单。质量问题的除外。蓝鲸😀收到商品后申请。\n").repeat(100)+"但不包括食品。";
        var counts=new ArrayList<Integer>();
        for(int size:new int[]{200,500,1000}) {
            var result=preparation.text("制度","1",text,new ChunkingOptions(size,0,0));counts.add(result.chunks().size());
            String joined=result.chunks().stream().map(Document::getText).reduce("",String::concat);
            assertThat(joined.replaceAll("\\s+","")).isEqualTo(text.replaceAll("\\s+",""));
            assertThat(joined).doesNotContain("\uFFFD");
        }
        assertThat(counts.get(0)).isGreaterThan(counts.get(1));assertThat(counts.get(1)).isGreaterThan(counts.get(2));
    }
    /**
     * 核对清理保留段落与短 FAQ，并覆盖空、超长、NUL、累计乱码和过多块的拒绝边界。
     */
    @Test void cleanerPreservesParagraphsAndShortFaqAndRejectsBadInput() {
        var result=preparation.text("FAQ","1","\uFEFF第一条\r\n\r\n\r\n  退款\t需要  审核。\u00A0",ChunkingOptions.defaults());
        assertThat(result.chunks().get(0).getText()).isEqualTo("第一条\n\n退款 需要 审核。");
        assertThat(result.warnings()).isNotEmpty();
        assertThatIllegalArgumentException().isThrownBy(()->preparation.text("资料","1"," ",ChunkingOptions.defaults()));
        assertThatIllegalArgumentException().isThrownBy(()->preparation.text("资料","1","字".repeat(50001),ChunkingOptions.defaults()));
        assertThatIllegalArgumentException().isThrownBy(()->preparation.text("资料","1","\uFFFD".repeat(21),ChunkingOptions.defaults()));
        var source = new KnowledgeSource("资料","1","rules.pdf","PDF");
        assertThatIllegalArgumentException().isThrownBy(()->preparation.prepare(source,List.of(new Document("正文\u0000")),ChunkingOptions.defaults()));
        assertThatIllegalArgumentException().isThrownBy(()->preparation.prepare(source,List.of(new Document("\uFFFD".repeat(11)),new Document("\uFFFD".repeat(11))),ChunkingOptions.defaults()));
        var tooMany=java.util.stream.IntStream.range(0,2001).mapToObj(i->new Document("有效规则。")).toList();
        assertThatIllegalArgumentException().isThrownBy(()->preparation.prepare(new KnowledgeSource("资料","1","x.txt","TEXT"),tooMany,ChunkingOptions.defaults()));
    }
    /**
     * 生成两页含重复页首的 PDF，对比保留与清理模式，确认正文和页号元数据仍可追溯。
     */
    @Test void pdfPagesAndOptionalHeaderRemovalArePreserved() throws Exception {
        byte[] bytes;
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            for(int page=1;page<=2;page++) {
                var p=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(p);
                try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,p)) {
                    stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(40,740);stream.showText("REPEATED HEADER");stream.newLineAtOffset(0,-30);
                    stream.showText("Page "+page+" policy: defective goods can be returned after review.");stream.endText();
                }
            }
            doc.save(out);bytes=out.toByteArray();
        }
        var keep=preparation.bytes("PDF制度","1","rules.pdf",bytes,ChunkingOptions.defaults());
        var clean=preparation.bytes("PDF制度","1","rules.pdf",bytes,new ChunkingOptions(500,1,0));
        assertThat(keep.chunks()).hasSize(2).allSatisfy(d->assertThat(d.getText()).contains("REPEATED HEADER"));
        assertThat(clean.chunks()).hasSize(2).allSatisfy(d->assertThat(d.getText()).doesNotContain("REPEATED HEADER").contains("policy:"));
        assertThat(clean.chunks()).extracting(d->d.getMetadata().get("page_number")).containsExactly(1,2);
    }
    /**
     * 使用 POI 生成真实 Office 字节后经 Tika 读取，证明不是把普通文本改后缀的假上传。
     */
    @Test void readsRealDocxAndPptxThroughTika() throws Exception {
        byte[] docx,pptx;
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("DOCX policy: blueberry gifts are collected every Thursday.");doc.write(out);docx=out.toByteArray();
        }
        try(var slides=new org.apache.poi.xslf.usermodel.XMLSlideShow();var out=new java.io.ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("PPTX policy: support opens at nine o'clock.");slides.write(out);pptx=out.toByteArray();
        }
        assertThat(preparation.bytes("word","1","rules.docx",docx,ChunkingOptions.defaults()).chunks().get(0).getText()).contains("DOCX policy");
        assertThat(preparation.bytes("slides","1","rules.pptx",pptx,ChunkingOptions.defaults()).chunks().get(0).getText()).contains("PPTX policy");
    }
    /**
     * 在 Markdown 中放入独特代码块标记，验证显式过滤后没有被错误当成知识正文。
     */
    @Test void markdownCodeIsExplicitlyExcluded() {
        var result=preparation.bytes("规则","1","rules.md","## 条件\n真实的退款规则。\n\n```java\nSECRET_CODE_ONLY\n```\n".getBytes(StandardCharsets.UTF_8),ChunkingOptions.defaults());
        assertThat(result.chunks()).allSatisfy(d->assertThat(d.getText()).doesNotContain("SECRET_CODE_ONLY"));
    }
    /**
     * 保存预览时验证导入器零交互，重复确认复用同一任务，后台只发布一次且未知令牌被拒绝。
     */
    @Test void previewNeverCallsImporterAndDuplicateCommitReturnsSameJob() throws Exception {
        var importer=mock(CustomKnowledgeImportService.class);
        var prepared=preparation.text("资料","1","退款申请需要核对订单。",ChunkingOptions.defaults());
        when(importer.importPrepared(any())).thenReturn(new CustomKnowledgeImportResult("id","资料","1",1,12));
        try(var service=new PreviewResource(new KnowledgePreviewService(importer))) {
            var preview=service.value.save(prepared);verifyNoInteractions(importer);
            assertThat(preview.previews()).hasSize(1);assertThat(preview.previews().get(0).metadata()).containsKey("chunkHash");
            var job=service.value.submit(preview.previewId());assertThat(service.value.submit(preview.previewId()).jobId()).isEqualTo(job.jobId());
            for(int i=0;i<100&&!service.value.job(job.jobId()).status().equals("PUBLISHED");i++)Thread.sleep(10);
            assertThat(service.value.job(job.jobId()).status()).isEqualTo("PUBLISHED");verify(importer,times(1)).importPrepared(any());
            assertThatThrownBy(()->service.value.submit("fabricated")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
    /**
     * 使用可控 Clock 推进到过期时间，确认旧令牌不能提交，并且不会触发导入。
     */
    @Test void expiredPreviewCannotBeCommitted() {
        /**
         * 可推进的 UTC 测试时钟，避免为了验证十五分钟过期而真实等待。
         */
        class MutableClock extends Clock {
            Instant now=Instant.parse("2026-09-13T00:00:00Z");
            /**
             * 测试固定使用 UTC，避免时区影响时间比较。
             */
            public ZoneId getZone(){return ZoneOffset.UTC;}
            /**
             * 本测试不切换时区，返回自身即可。
             */
            public Clock withZone(ZoneId z){return this;}
            /**
             * 返回测试手动推进的瞬时时间。
             */
            public Instant instant(){return now;}
        }
        var clock=new MutableClock();var importer=mock(CustomKnowledgeImportService.class);
        try(var resource=new PreviewResource(new KnowledgePreviewService(importer,clock))) {
            var preview=resource.value.save(preparation.text("资料","1","短规则。",ChunkingOptions.defaults()));
            clock.now=clock.now.plusSeconds(901);
            assertThatThrownBy(()->resource.value.submit(preview.previewId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            verifyNoInteractions(importer);
        }
    }
    /**
     * 测试资源包装器，退出 try-with-resources 时关闭预览线程池，避免后台线程影响后续用例。
     */
    record PreviewResource(KnowledgePreviewService value) implements AutoCloseable {
        /**
         * 委托关闭后台工作线程池，使测试离开作用域后释放资源。
         */
        public void close(){value.close();}}
}
