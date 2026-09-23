package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.aftersale.*;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.IntStream;
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

/** 专用测试数据库中验证适用版本过滤，Embedding 替换为确定性向量，绝不读取真实订单或调用云模型。 */
@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder","spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test"})
@ActiveProfiles({"local","knowledge"})
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class AfterSalePolicyPersistenceTest {
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired ApplicablePolicyRetriever retriever;
    @MockitoBean EmbeddingModel embeddings;
    @BeforeEach void setup(){assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");clean();
        when(embeddings.call(any())).thenAnswer(inv->{EmbeddingRequest r=inv.getArgument(0);float[] v=new float[1024];v[0]=1;
            return new EmbeddingResponse(IntStream.range(0,r.getInstructions().size()).mapToObj(i->new Embedding(v,i)).toList());});}
    @AfterEach void clean(){jdbc.update("delete from ai.knowledge_vector_store");}
    String add(Map<String,Object> override)throws Exception{String id=UUID.randomUUID().toString();var metadata=new HashMap<String,Object>(Map.of("tenantId","tenant-yunshan","knowledgeBase","after-sales","language","zh-CN","status","PUBLISHED","sourceId","refund-policy","sourceVersion","3.2"));metadata.putAll(override);
        jdbc.update("insert into ai.knowledge_vector_store(id,content,metadata,embedding) values(?::uuid,?,?::json,?::vector)",id,"教学政策：用户反馈质量问题还需要核验，并不代表退货已获批。",json.writeValueAsString(metadata),"[1,"+"0,".repeat(1022)+"0]");return id;}
    @Test void exactApplicableVersionAndAllScopesAreEnforcedByRealStore()throws Exception{
        String expected=add(Map.of());
        for(var m:List.of(Map.<String,Object>of("sourceVersion","4.0"),Map.<String,Object>of("tenantId","other"),Map.<String,Object>of("sourceId","other-policy"),Map.<String,Object>of("status","ARCHIVED"),Map.<String,Object>of("language","en-US"),Map.<String,Object>of("knowledgeBase","other")))add(m);
        assertThat(retriever.retrieve(AfterSaleTest.ACTOR,AfterSaleTest.facts(),ReturnReason.QUALITY_ISSUE)).extracting(PolicyEvidence::documentId).containsExactly(expected);
    }
    @Test void archivedApplicableVersionCannotBeReplacedByPublishedNewerVersion()throws Exception{
        add(Map.of("status","ARCHIVED"));add(Map.of("sourceVersion","4.0"));
        assertThat(retriever.retrieve(AfterSaleTest.ACTOR,AfterSaleTest.facts(),ReturnReason.CHANGE_OF_MIND)).isEmpty();
    }
}
