package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.knowledge.ingestion.*;
import com.example.cloudcustomerservice.knowledge.management.*;
import com.example.cloudcustomerservice.rag.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原件与评测的真实 PostgreSQL 验证，只允许专用测试库；不访问百炼，不改用户资料。 */
@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder","spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test"})
@ActiveProfiles({"local","knowledge"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class KnowledgeAdminPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeDocumentCatalog catalog;
    @Autowired CustomKnowledgeImportService importer;
    @Autowired LocalKnowledgeImportService samples;
    @Autowired KnowledgeEvaluationService evaluations;
    @Autowired KnowledgeSearchService search;
    @Autowired MockMvc mvc;
    @MockitoBean EmbeddingModel embeddings;
    @MockitoBean AdvisorKnowledgeAnswerService answers;
    @MockitoSpyBean KnowledgeBatchWriter writer;
    /** 校验库名后清理专用数据，向量使用确定的 1024 维结果。 */
    @BeforeEach void setup(){
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");
        clean();when(embeddings.call(any())).thenAnswer(inv->{EmbeddingRequest r=inv.getArgument(0);float[] v=new float[1024];v[0]=1;
            return new EmbeddingResponse(IntStream.range(0,r.getInstructions().size()).mapToObj(i->new Embedding(v,i)).toList());});
    }
    /** 所有异步运行测试必须等到终态后才清库，避免后台任务跨测试污染。 */
    @AfterEach void clean(){jdbc.update("delete from ai.knowledge_vector_store");jdbc.update("delete from ai.knowledge_originals");jdbc.update("delete from ai.knowledge_evaluation_runs");}
    /** Markdown 原件逐字节下载，正文和哈希可核对；列表不会返回原件内容。 */
    @Test void originalBytesMetadataAndPagedCatalogAreReal() throws Exception {
        byte[] bytes="# 制度\n\n| 项目 | 规则 |\n| --- | --- |\n| 退货 | 质量问题除外 |\n".getBytes(StandardCharsets.UTF_8);
        var result=importer.importFile("制度.md",new MockMultipartFile("file","制度.md","text/markdown",bytes));
        var detail=catalog.detail(result.sourceId());assertThat(detail.previewKind()).isEqualTo("ORIGINAL_TEXT");
        assertThat(detail.preview()).isEqualTo(new String(bytes,StandardCharsets.UTF_8));
        assertThat(catalog.download(result.sourceId()).bytes()).isEqualTo(bytes);
        assertThat(detail.source().createdAt()).isNotBlank();assertThat(detail.fileHash()).hasSize(64);
        var page=catalog.list("制度","PUBLISHED","MARKDOWN",1,1);assertThat(page.total()).isEqualTo(1);assertThat(page.items()).hasSize(1);
        assertThat(catalog.list("' OR 1=1 --","ALL","ALL",1,15).items()).isEmpty();
        mvc.perform(get("/internal/knowledge-admin/documents/"+result.sourceId()+"/download"))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes(bytes));
    }
    /** 回收站退出真实向量检索，恢复保留原件和块 ID，重复操作幂等。 */
    @Test void archiveAndRestoreChangeSearchWithoutDeletingOriginals(){
        var a=importer.importText("可恢复资料","云杉退货运费制度");var b=importer.importText("其他资料","其他资料内容");
        var ids=catalog.detail(a.sourceId()).chunks().stream().map(KnowledgeDocumentCatalog.Chunk::documentId).toList();
        catalog.archive(a.sourceId(),true);catalog.archive(a.sourceId(),true);
        assertThat(catalog.list("","ARCHIVED","ALL",1,15).items()).extracting(KnowledgeDocumentCatalog.Source::sourceId).containsExactly(a.sourceId());
        assertThat(search.search("tenant-yunshan","退货",10,0.0).hits()).extracting(KnowledgeHit::sourceId).containsExactly(b.sourceId());
        assertThat(catalog.download(a.sourceId()).bytes()).isNotEmpty();catalog.archive(a.sourceId(),false);
        assertThat(catalog.detail(a.sourceId()).chunks()).extracting(KnowledgeDocumentCatalog.Chunk::documentId).containsExactlyElementsOf(ids);
        assertThat(search.search("tenant-yunshan","退货",10,0.0).hits()).hasSize(2);
    }
    /** 列表、详情、下载、下架均检查租户/知识库/语言，恢复不能发布草稿。 */
    @Test void managementScopeAndDraftProtection(){
        var a=importer.importText("其他租户","其他内容");
        jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{tenantId}','\"other\"')::json");
        assertThat(catalog.list("","ALL","ALL",1,15).items()).isEmpty();
        assertThatThrownBy(()->catalog.detail(a.sourceId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->catalog.download(a.sourceId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->catalog.archive(a.sourceId(),false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var b=importer.importText("草稿","保留草稿");jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{status}','\"DRAFT\"')::json where metadata->>'sourceId'=?",b.sourceId());
        assertThatThrownBy(()->catalog.archive(b.sourceId(),false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    /** 更新失败时旧原件和旧向量一起保留；成功同名替换保留首次上传时间。 */
    @Test void originalAndChunksPublishAtomically(){
        var a=importer.importText("事务资料","旧正文");String created=catalog.detail(a.sourceId()).source().createdAt();
        doThrow(new RuntimeException("cleanup failure")).when(writer).removeOldChunks(any());
        assertThatThrownBy(()->importer.importText("事务资料","新正文")).isInstanceOf(KnowledgeUnavailableException.class);
        assertThat(new String(catalog.download(a.sourceId()).bytes(),StandardCharsets.UTF_8)).isEqualTo("旧正文");
        doCallRealMethod().when(writer).removeOldChunks(any());importer.importText("事务资料","新正文");
        assertThat(catalog.detail(a.sourceId()).source().createdAt()).isEqualTo(created);
        assertThat(catalog.detail(a.sourceId()).preview()).isEqualTo("新正文");
    }
    /** 课程旧资料可查看全部块，但原件和时间必须未知。 */
    @Test void legacyDocumentsNeverPretendToHaveOriginalFiles(){
        samples.importDocuments();var d=catalog.detail("refund-policy");assertThat(d.previewKind()).isEqualTo("CHUNKS");
        assertThat(d.source().hasOriginal()).isFalse();assertThat(d.source().createdAt()).isNull();assertThat(d.chunks()).hasSize(2);
        assertThatThrownBy(()->catalog.download("refund-policy")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(catalog.list("","ALL","LEGACY",1,2).total()).isEqualTo(3);
        assertThat(catalog.list("","ALL","LEGACY",2,2).items()).hasSize(1);
    }
    /** 异步评测真实写入数据库并逐题清理隔离会话；服务故障不能算作正确拒答。 */
    @Test void evaluationPersistsActualResultsAndIsolatesEachQuestion() throws Exception {
        when(answers.answer(anyString(),anyString(),eq("正常题"))).thenReturn(response(KnowledgeAnswerStatus.ANSWERED));
        when(answers.answer(anyString(),anyString(),eq("拒答题"))).thenReturn(response(KnowledgeAnswerStatus.NO_EVIDENCE));
        when(answers.answer(anyString(),anyString(),eq("失败题"))).thenReturn(response(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE));
        var request=new KnowledgeEvaluationService.Request("回归测试",List.of(
                new KnowledgeEvaluationService.CaseInput("正常题",false,List.of("policy")),
                new KnowledgeEvaluationService.CaseInput("拒答题",true,List.of()),new KnowledgeEvaluationService.CaseInput("失败题",true,List.of())));
        var run=evaluations.create(request);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do{run=evaluations.get(run.id());if(!Set.of("QUEUED","RUNNING").contains(run.status()))break;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");assertThat(run.results()).hasSize(3);
        assertThat(run.results().get(0).sourceHit()).isTrue();assertThat(run.results().get(1).refusalMatched()).isTrue();
        assertThat(run.results().get(2).refusalMatched()).isNull();assertThat(run.results().get(2).error()).isNotBlank();
        var ids=org.mockito.ArgumentCaptor.forClass(String.class);verify(answers,times(3)).clearMemory(eq("tenant-yunshan"),ids.capture(),isNull());
        assertThat(ids.getAllValues()).doesNotHaveDuplicates().allMatch(s->s.startsWith("eval-"));
        assertThat(evaluations.list()).hasSize(1);assertThat(evaluations.get(run.id()).cases()).hasSize(3);
    }
    /** 已中断任务不会自动重新消耗模型额度；输入错误不触发任何模型调用。 */
    @Test void restartAndHttpValidationDoNotGenerate() throws Exception {
        String id=UUID.randomUUID().toString();jdbc.update("insert into ai.knowledge_evaluation_runs(id,tenant_id,name,status,cases) values(?::uuid,'tenant-yunshan','中断实验','RUNNING','[]')",id);
        evaluations.recover();assertThat(evaluations.get(id).status()).isEqualTo("INTERRUPTED");verifyNoInteractions(answers);
        mvc.perform(get("/internal/knowledge-admin")).andExpect(status().isOk());
        mvc.perform(get("/internal/knowledge-admin/documents?page=0")).andExpect(status().isBadRequest());
        mvc.perform(patch("/internal/knowledge-admin/documents/refund-policy/archive").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/knowledge-admin/evaluations").contentType("application/json").content("{\"name\":\"x\",\"cases\":[]}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(answers);
    }
    /** 为评测比较提供稳定响应，来源只用于验证算法契约。 */
    private AdvisorKnowledgeAnswerResponse response(KnowledgeAnswerStatus status){
        return new AdvisorKnowledgeAnswerResponse("r","c","q",status,"答复",status==KnowledgeAnswerStatus.ANSWERED?
                List.of(new KnowledgeReference("d","policy","制度","1",1,"TEST",0.8,"测试资料")):List.of());
    }
}
