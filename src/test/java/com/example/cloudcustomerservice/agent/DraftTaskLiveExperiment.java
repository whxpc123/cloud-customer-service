package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.knowledge.LocalKnowledgeDocuments;
import com.example.cloudcustomerservice.handoff.HandoffModel.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.vectorstore.VectorStore;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import static org.assertj.core.api.Assertions.*;

/** 真实百炼 + 真实 MemorySaver 三轮续写；仅隔离容器中的合成政策，不改变用户知识库。 */
@SpringBootTest(properties={"app.ai.log-payload=false"}) @ActiveProfiles({"local","knowledge"})
@Import(AcceptanceDatabase.class) @EnabledIfEnvironmentVariable(named="RUN_LIVE_ACCEPTANCE",matches="true")
class DraftTaskLiveExperiment {
    @Autowired LocalDraftTaskService tasks;
    @Autowired LocalKnowledgeDocuments fixtures;
    @Autowired VectorStore vectors;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void realThreeTurnTaskUsesLatestDescriptionAndTreatsPhotosAsUserClaim()throws Exception{
        var results=new LinkedHashMap<String,Object>();results.put("startedAt",Instant.now().toString());
        var access=new Access(new Actor("tenant-yunshan",1001),id->DraftTaskTest.receipt(id,Mode.BOT,0));
        try{
            assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");vectors.add(fixtures.documents());
            var task=tasks.create(access,UUID.randomUUID(),"A10001",ReturnReason.QUALITY_ISSUE);results.put("created",task);
            String[] messages={"商品使用时插头发热。请先核验订单与政策，整理候选草稿，保留未核验标记，不要提交。",
                "刚才描述有误，请把用户描述改为外壳破损，删除插头发热的说法。请重新核验并更新候选，不要提交。",
                "我已经上传照片了，请在候选中记录我的声明；你并没有读取照片，不能认定质量已经核实。其余沿用最新的外壳破损描述，重新核验后更新候选，不要提交。"};
            Set<String> runs=new HashSet<>();
            for(int i=0;i<messages.length;i++){
                var view=tasks.continueTask(access,task.taskId(),messages[i]);results.put("turn"+(i+1),view);
                assertThat(view.task().taskId()).isEqualTo(task.taskId());assertThat(view.task().turnNo()).isEqualTo(i+1);
                assertThat(view.task().status()).isEqualTo(Status.CANDIDATE_UNVALIDATED);
                assertThat(view.state().userMessages()).isEqualTo(i+1);assertThat(view.lastRun().submitted()).isFalse();assertThat(view.lastRun().refundExecuted()).isFalse();
                assertThat(view.lastRun().executedSteps()).contains("inspectAfterSale:NEED_QUALITY_VERIFICATION","readDraftTemplate:READ");
                runs.add(view.lastRun().runId());
                if(i>0)assertThat(view.lastRun().candidateText()).contains("外壳破损").doesNotContain("插头发热");
                if(i==2)assertThat(view.lastRun().candidateText()).contains("照片").containsAnyOf("未读取","未查看","未核验","尚未核验","没有读取");
            }
            assertThat(runs).hasSize(3);results.put("readOnlyReload",tasks.get(access,task.taskId()));
            tasks.discard(access,task.taskId());assertThat(tasks.list(access.actor())).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from ai.cs_message",Integer.class)).isZero();
        }finally{
            results.put("finishedAt",Instant.now().toString());var file=Path.of("target/chapter-21-live.json");
            Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(results));Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }
}
