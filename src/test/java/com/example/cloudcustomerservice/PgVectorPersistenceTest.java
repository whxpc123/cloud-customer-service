package com.example.cloudcustomerservice;

import java.util.*;
import java.util.stream.IntStream;
import com.example.cloudcustomerservice.knowledge.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实 PostgreSQL/pgvector 集成测试，必须显式设置 RUN_PGVECTOR_TESTS=true。
 * 只连接 cloud_customer_service_test；测试前校验库名，测试后清空测试知识表。
 * 模型完全模拟，因此验证的是 SQL、向量列、索引、过滤及事务回滚，不产生百炼费用。
 *
 *
 * 显式运行的真实 pgvector 测试，只操作专用 test 数据库，模型完全离线。
 */
@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder", "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test"})
@ActiveProfiles({"local","knowledge"})
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class PgVectorPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalKnowledgeImportService importer;
    @Autowired KnowledgeSearchService search;
    @Autowired CustomKnowledgeImportService customImporter;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name="vectorStore")
    org.springframework.ai.vectorstore.pgvector.PgVectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.cloudcustomerservice.knowledge.ingestion.KnowledgeBatchWriter batchWriter;
    @MockitoBean EmbeddingModel model;
    /**
     * 先确认连接的是专用测试库再清表，并配置确定性向量返回，避免测试触碰用户知识库。
     */
    @BeforeEach void prepare() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");
        jdbc.update("delete from ai.knowledge_vector_store");
        when(model.call(any())).thenAnswer(inv->{EmbeddingRequest r=inv.getArgument(0);return new EmbeddingResponse(IntStream.range(0,r.getInstructions().size()).mapToObj(i->new Embedding(vector(r.getInstructions().get(i)),i)).toList());});
    }
    /**
     * 清空专用测试数据库的知识表，避免本例数据影响下一例；生产库不在此测试连接范围内。
     */
    @AfterEach void cleanup(){jdbc.update("delete from ai.knowledge_vector_store");}
    /**
     * 构造固定 1024 维、可预测的测试向量；只为验证批次或 SQL 行为，不代表真实语义嵌入。
     */
    float[] vector(String text) {var v=new float[1024];v[0]=1;v[1]=text.contains("物流")?1:0;return v;}
    /**
     * 先导入多块旧资料，再以短正文同名替换并重复导入；真实 SQL 应清掉多余旧块且保留其他来源。
     */
    @Test void customImportReplacesShortenedContentAndKeepsOtherSources() {
        importer.importDocuments();
        var first = customImporter.importText("会员说明.md", "会员规则。".repeat(150));
        assertThat(first.importedDocuments()).isGreaterThan(1);
        customImporter.importText("另一份资料", "其他来源的正文");
        var second = customImporter.importText("会员说明.md", "新版会员规则");
        customImporter.importText("会员说明.md", "新版会员规则");
        assertThat(second.sourceId()).isEqualTo(first.sourceId());
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("select content from ai.knowledge_vector_store where metadata->>'sourceId'=?", String.class, first.sourceId()))
                .containsExactly("新版会员规则");
        assertThat(search.search("tenant-yunshan", "问题", 10, 0.0).hits()).extracting(KnowledgeHit::sourceName).contains("会员说明.md");
    }
    /**
     * 在新块已经写入后模拟清理失败，读取真实数据库验证事务回滚、旧块完整且没有半份新版。
     */
    @Test void customReplacementRollsBackIfCleanupFailsAfterDatabaseWrite() {
        var original = customImporter.importText("回滚资料", "原版".repeat(600));
        doThrow(new RuntimeException("SIMULATED_CLEANUP_FAILURE")).when(batchWriter)
                .removeOldChunks(any());
        assertThatThrownBy(() -> customImporter.importText("回滚资料", "新版"))
                .isInstanceOf(KnowledgeUnavailableException.class);
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store where metadata->>'sourceId'=? and metadata->>'sourceVersion'=?",
                Integer.class, original.sourceId(), original.sourceVersion())).isEqualTo(original.importedDocuments());
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store where content='新版'", Integer.class)).isZero();
    }
    /**
     * 非法来源和超长正文不调用模型；向量调用发生在事务外，失败后数据库仍只有旧正文。
     */
    @Test void invalidCustomTextNeverCallsModelAndEmbeddingFailureKeepsOldContent() {
        for (String name : Arrays.asList(null, " ", "x".repeat(121))) {
            assertThatIllegalArgumentException().isThrownBy(() -> customImporter.importText(name, "正文"));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> customImporter.importText("资料", "x".repeat(50001)));
        verifyNoInteractions(model);
        doAnswer(inv -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            EmbeddingRequest req=inv.getArgument(0);
            return new EmbeddingResponse(IntStream.range(0,req.getInstructions().size()).mapToObj(i->new Embedding(vector(req.getInstructions().get(i)),i)).toList());
        }).when(model).call(any());
        customImporter.importText("资料", "旧正文");
        doThrow(new RuntimeException("MOCK_EMBEDDING_FAILURE")).when(model).call(any());
        assertThatThrownBy(() -> customImporter.importText("资料", "新正文")).isInstanceOf(KnowledgeUnavailableException.class);
        assertThat(jdbc.queryForList("select content from ai.knowledge_vector_store",String.class)).containsExactly("旧正文");
    }
    /**
     * 真实数据库上核对 1024 维 vector 列和 HNSW 余弦索引，重复导入仍只有四条样例。
     */
    @Test void flywayCreatesRealVectorColumnAndHnswIndexAndUpserts() {
        importer.importDocuments();importer.importDocuments();
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("select distinct vector_dims(embedding) from ai.knowledge_vector_store",Integer.class)).containsExactly(1024);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname='ai' and indexname='knowledge_vector_store_embedding_hnsw_idx'",String.class)).contains("hnsw","vector_cosine_ops");
    }
    /**
     * 直接修改测试表的租户、发布状态、语言与知识库，验证实际 SQL 检索按四项范围排除记录。
     */
    @Test void actualSqlFiltersTenantStatusKnowledgeBaseAndLanguage() {
        importer.importDocuments();
        jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{tenantId}', '\"tenant-songguo\"')::json where metadata->>'category'='REFUND_POLICY'");
        jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{status}', '\"DRAFT\"')::json where metadata->>'category'='REFUND_FREIGHT'");
        jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{language}', '\"en-US\"')::json where metadata->>'category'='INVOICE_POLICY'");
        assertThat(search.search("tenant-yunshan","问题",10,0.0).hits()).extracting(KnowledgeHit::category).containsExactly("LOGISTICS_EXCEPTION");
        jdbc.update("update ai.knowledge_vector_store set metadata=jsonb_set(metadata::jsonb,'{knowledgeBase}', '\"other\"')::json where metadata->>'category'='LOGISTICS_EXCEPTION'");
        assertThat(search.search("tenant-yunshan","问题",10,0.0).hits()).isEmpty();
    }
    /**
     * 清空模型交互记录后发起查询，检查一次问题向量调用及数据库层条数、阈值的效果。
     */
    @Test void queryCallsOnlyOneEmbeddingAndHonorsTopKAndThreshold() {
        importer.importDocuments();clearInvocations(model);
        assertThat(search.search("tenant-yunshan","问题",1,0.0).hits()).hasSize(1);
        verify(model).call(argThat(r->r.getInstructions().equals(List.of("问题"))));
        assertThat(search.search("tenant-yunshan","问题",10,0.9).hits()).hasSize(3);
    }
    /**
     * 已有四条样例时模拟后续向量失败，重新读取数据库确认旧行未被提前删除。
     */
    @Test void embeddingFailureDuringReimportPreservesOldRows() {
        importer.importDocuments();doThrow(new RuntimeException("MOCK_EMBEDDING_FAILURE")).when(model).call(any());
        assertThatThrownBy(importer::importDocuments).isInstanceOf(KnowledgeUnavailableException.class);
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store",Integer.class)).isEqualTo(4);
    }
}
