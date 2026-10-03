package com.example.cloudcustomerservice.acceptance;

import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.knowledge.ingestion.*;
import com.example.cloudcustomerservice.knowledge.search.PostgresKeywordSearchRepository;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.rag.expansion.ExpansionMode;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 显式运行的收费实验：临时数据库中只放合成夹具，正式 HTTP 登录/消息链使用真实 Qwen。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.ai.log-payload=false","handoff.accounts.customer1001.password=acceptance-customer-one-password",
    "handoff.accounts.customer2002.password=acceptance-customer-two-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_LIVE_ACCEPTANCE",matches="true")
class StageOneLiveExperiment {
    @Autowired VectorStore vectors;
    @Autowired org.springframework.ai.chat.model.ChatModel model;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired LocalKnowledgeDocuments fixtures;
    @Autowired AdvisorKnowledgeAnswerService knowledge;
    @Autowired CustomKnowledgeImportService importer;
    @Autowired KnowledgePreparationService preparation;
    @Autowired KnowledgeSearchService search;
    @Autowired PostgresKeywordSearchRepository keywords;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.example.cloudcustomerservice.knowledge.management.KnowledgeDocumentCatalog catalog;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean(name="afterSaleClock") Clock clock;
    final Map<String,Object> report=new LinkedHashMap<>();
    /** 即使中途失败也保存已执行结果；不存在的结果由汇总器标成未执行，绝不默认通过。 */
    @Test void executeFixedDatasetWithRealModelsAndIsolatedKnowledge()throws Exception{
        report.put("configuration",Map.of("chatModel",model.getDefaultOptions().getModel(),"embeddingModel",environment.getRequiredProperty("spring.ai.dashscope.embedding.options.model"),
            "embeddingDimensions",environment.getRequiredProperty("spring.ai.dashscope.embedding.options.dimensions"),"expansionMode","AUTO","rerankRequested",true,"chunking","manual-1 fixtures; TokenTextSplitter default for upload"));
        report.put("startedAt",Instant.now().toString());report.put("datasetVersion","acceptance-v1");
        report.put("fixtureClock","2026-08-30T10:00:00+08:00");report.put("modelVersionPinning","provider aliases; internal provider revision unavailable");
        try{
            assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
            when(clock.instant()).thenReturn(Instant.parse("2026-08-30T02:00:00Z"));when(clock.getZone()).thenReturn(ZoneOffset.UTC);
            seed(); importAndUpdate(); rag(); formalHttp();
        }finally{
            report.put("finishedAt",Instant.now().toString());
            Path path=Path.of("target/acceptance-live.json");Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }
    /** 当前、归档旧版和其他租户的相似知识共存；为每条夹具保留实际 Document ID 和元数据。 */
    void seed(){
        var docs=new ArrayList<>(fixtures.documents());var metadata=new HashMap<>(docs.get(0).getMetadata());
        metadata.put("sourceId","refund-timing");metadata.put("sourceVersion","1.0");metadata.put("chunkIndex",1);
        docs.add(new Document(UUID.nameUUIDFromBytes("acceptance-refund-timing-v1".getBytes(StandardCharsets.UTF_8)).toString(),
            "教学测试政策：退款审核通过后，两个工作日内提交退款；实际到账时间由支付机构处理，不能承诺立即到账。",metadata));
        for(String kind:List.of("archived","foreign")){
            var m=new HashMap<>(docs.get(1).getMetadata());
            if(kind.equals("archived")){m.put("status","ARCHIVED");m.put("sourceVersion","1.0");}else m.put("tenantId","other-tenant");
            docs.add(new Document(UUID.nameUUIDFromBytes(kind.getBytes(StandardCharsets.UTF_8)).toString(),"个人原因退货运费一律由商城承担（错误范围测试夹具）。",m));
        }
        vectors.add(docs);report.put("knowledgeManifest",docs.stream().map(d->Map.of("documentId",d.getId(),"metadata",d.getMetadata(),"content",d.getText())).toList());
    }
    /** 走正式导入 ETL 和 Embedding，检查新增及替换后两种索引、元数据和旧块退出情况。 */
    void importAndUpdate(){
        var first=preparation.text("阶段验收编码资料","1.0","CPN-19A1 教学验收条款：个人原因退货运费由消费者承担。",ChunkingOptions.defaults());
        importer.importPrepared(first);
        var vectorFirst=search.search("tenant-yunshan","CPN-19A1 教学验收条款",10,0.0).hits();
        assertThat(vectorFirst).anyMatch(h->h.sourceId().equals(first.source().sourceId()));
        assertThat(keywords.keyword("tenant-yunshan","CPN-19A1",10)).hasSize(1);
        var second=preparation.text("阶段验收编码资料","2.0","CPN-19B2 教学验收条款：质量核验通过后按适用政策处理，不自动批准退款。",ChunkingOptions.defaults());
        importer.importPrepared(second);
        assertThat(keywords.keyword("tenant-yunshan","CPN-19A1",10)).isEmpty();
        var updated=keywords.keyword("tenant-yunshan","CPN-19B2",10);assertThat(updated).hasSize(1);
        assertThat(updated.get(0).getMetadata()).containsEntry("sourceVersion","2.0").containsEntry("sourceId",second.source().sourceId());
        assertThat(search.search("tenant-yunshan","CPN-19B2 教学验收条款",10,0.0).hits()).anyMatch(h->h.sourceId().equals(second.source().sourceId())&&h.sourceVersion().equals("2.0"));
        assertThat(jdbc.queryForObject("select count(*) from ai.knowledge_vector_store where metadata->>'sourceId'=? and metadata->>'sourceVersion'='1.0'",Integer.class,second.source().sourceId())).isZero();
        // 完成导入验证后归档此独立编码夹具，避免改变固定政策评测候选池。
        catalog.archive(second.source().sourceId(),true);
        report.put("ingestion",Map.of("status","PASS","sourceId",second.source().sourceId(),"versions",List.of("1.0","2.0"),"vectorAndKeyword",true));
    }
    /** 每题独立记忆；轨迹只写本地受限报告，不把检索成功直接当成语义正确。 */
    void rag()throws Exception{
        var rows=new ArrayList<Map<String,Object>>();report.put("rag",rows);
        var dataset=json.readTree(Path.of("src/test/resources/acceptance/knowledge-cases.json").toFile());
        for(var c:dataset.get("cases")){
            var row=new LinkedHashMap<String,Object>();row.put("caseId",c.get("caseId").asText());long start=System.nanoTime();
            try{row.put("response",knowledge.answer("tenant-yunshan","acceptance-"+UUID.randomUUID(),1001L,c.get("question").asText(),ExpansionMode.AUTO,true));}
            catch(RuntimeException ex){row.put("errorType",ex.getClass().getSimpleName());}
            row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);rows.add(row);
        }
    }
    /** 实际 Cookie 登录后通过正式发布入口执行；模拟订单仍不是生产订单接口。 */
    void formalHttp()throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String csrf=get(client,"/internal/handoff/session").get("csrfToken").asText();
        var login=HttpRequest.newBuilder(URI.create(base()+"/internal/handoff/login")).header("X-CSRF-TOKEN",csrf).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=customer1001&password=acceptance-customer-one-password")).build();
        assertThat(client.send(login,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        csrf=get(client,"/internal/handoff/session").get("csrfToken").asText();
        var rows=new ArrayList<Map<String,Object>>();report.put("formalHttp",rows);
        for(String question:List.of("你好","A10001 发货了吗？","A10002 发货了吗？","A10001 的商品有质量问题，能退吗？","我要转人工")){
            String cid=post(client,"/api/handoff/conversations",csrf,Map.of()).get("conversationId").asText();
            long start=System.nanoTime();var row=new LinkedHashMap<String,Object>();row.put("question",question);rows.add(row);
            try{row.put("response",post(client,"/internal/routing/conversations/"+cid+"/messages",csrf,Map.of("message",question,"clientMessageId",UUID.randomUUID().toString())));}
            catch(Exception e){row.put("errorType",e.getClass().getSimpleName());}
            row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);
        }
    }
    String base(){return "http://127.0.0.1:"+port;}
    JsonNode get(HttpClient client,String path)throws Exception{return send(client,HttpRequest.newBuilder(URI.create(base()+path)).GET().build());}
    JsonNode post(HttpClient client,String path,String csrf,Object data)throws Exception{return send(client,HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(120))
        .header("X-CSRF-TOKEN",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build());}
    JsonNode send(HttpClient client,HttpRequest request)throws Exception{var r=client.send(request,HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isBetween(200,299);return json.readTree(r.body());}
}
