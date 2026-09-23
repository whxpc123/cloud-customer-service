package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.knowledge.search.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 PostgreSQL 验证触发器、生成列、范围过滤与更新；绝不连接业务库或云模型。 */
@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder","spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test"})
@ActiveProfiles({"local","knowledge"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class HybridSearchPersistenceTest {
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PostgresKeywordSearchRepository repository;@Autowired MockMvc mvc;
    @BeforeEach void setup(){assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");clean();}
    @AfterEach void clean(){jdbc.update("delete from ai.knowledge_vector_store");}
    String insert(String content,Map<String,Object> overrides) throws Exception {
        String id=UUID.randomUUID().toString();var m=new HashMap<String,Object>(Map.of("tenantId","tenant-yunshan","status","PUBLISHED","knowledgeBase","after-sales","language","zh-CN","sourceId",id,"sourceVersion","2.0"));m.putAll(overrides);
        String vector="[1,"+"0,".repeat(1022)+"0]";
        jdbc.update("insert into ai.knowledge_vector_store(id,content,metadata,embedding) values(?::uuid,?,?::json,?::vector)",id,content,json.writeValueAsString(m),vector);return id;
    }
    @Test void triggerRecognizesCodesAndGeneratesSearchOnEveryUpdate() throws Exception {
        String id=insert("请问cpn-88a7退款，SKU-E100、POLICY-4.2规则",Map.of());
        assertThat(repository.exact("tenant-yunshan",List.of("CPN-88A7","SKU-E100"),10)).hasSize(1);
        assertThat(repository.keyword("tenant-yunshan","CPN-88A7",10)).hasSize(1);
        jdbc.update("update ai.knowledge_vector_store set content='SKU-E101 warranty' where id=?::uuid",id);
        assertThat(repository.exact("tenant-yunshan",List.of("CPN-88A7"),10)).isEmpty();
        assertThat(repository.keyword("tenant-yunshan","CPN-88A7",10)).isEmpty();
        assertThat(repository.exact("tenant-yunshan",List.of("SKU-E101"),10)).hasSize(1);
        assertThat(repository.keyword("tenant-yunshan","warranty",10)).hasSize(1);
    }
    @Test void exactAndKeywordExcludeArchivedDraftOtherTenantsBasesAndLanguages() throws Exception {
        String valid=insert("CPN-88A7 current",Map.of());
        for(var m:List.of(Map.<String,Object>of("status","ARCHIVED","sourceVersion","1.0"),Map.<String,Object>of("status","DRAFT"),Map.<String,Object>of("tenantId","other"),Map.<String,Object>of("knowledgeBase","other"),Map.<String,Object>of("language","en-US")))insert("CPN-88A7 old",m);
        assertThat(repository.exact("tenant-yunshan",List.of("CPN-88A7"),24)).extracting(org.springframework.ai.document.Document::getId).containsExactly(valid);
        assertThat(repository.keyword("tenant-yunshan","CPN-88A7",24)).extracting(org.springframework.ai.document.Document::getId).containsExactly(valid);
    }
    @Test void noSubstringMatchingAndMetadataCodesAreIndexed() throws Exception {
        insert("CPN-88A70 SKU-E1000",Map.of());
        assertThat(repository.exact("tenant-yunshan",List.of("CPN-88A7","SKU-E100"),24)).isEmpty();
        insert("一次性券的退款说明",Map.of("couponCode","cpn-88a7"));
        assertThat(repository.exact("tenant-yunshan",List.of("CPN-88A7"),24)).hasSize(1);
        assertThat(repository.keyword("tenant-yunshan","CPN-88A7",24)).hasSize(1);
    }
    @Test void parametersDoNotBypassScopeAndSimpleDoesNotPretendChineseSegmentation() throws Exception {
        insert("无理由退货运费由客户承担",Map.of());
        assertThat(repository.keyword("tenant-yunshan","' OR 1=1 --",24)).isEmpty();
        assertThat(repository.keyword("tenant-yunshan","退货运费",24)).isEmpty();
        assertThatThrownBy(()->repository.keyword("bad' OR 1=1 --","anything",10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidRequestsAndOrdersNeverNeedCloud() throws Exception {
        mvc.perform(get("/internal/hybrid-search")).andExpect(status().isOk());
        mvc.perform(post("/internal/hybrid-search/compare").contentType("application/json").content("{\"question\":\"A10001 发货了吗？\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BUSINESS_TOOL_REQUIRED"));
        mvc.perform(post("/internal/hybrid-search/compare").contentType("application/json").content("{\"question\":\"test\",\"topK\":25}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/hybrid-search/compare").contentType("application/json").content(json.writeValueAsString(Map.of("question","x".repeat(2001)))))
                .andExpect(status().isBadRequest());
    }
}
