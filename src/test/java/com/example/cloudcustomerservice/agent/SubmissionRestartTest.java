package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** 两个真实 JVM 验证：忽略首次响应后，即使进程退出也能按原操作编号回放数据库回执。 */
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class SubmissionRestartTest {
    @Test void receiptAndStableOperationSurviveTwoJvmRestart()throws Exception{
        var f=new PersistentDraftRestartTest();f.work=Files.createTempDirectory("ch26-process-");f.ready=f.work.resolve("ready");f.calls=f.work.resolve("calls");f.blocked=f.work.resolve("blocked");
        f.db=new PostgreSQLContainer<>(DockerImageName.parse(AcceptanceDatabase.IMAGE).asCompatibleSubstituteFor("postgres")).withDatabaseName("chapter26_restart_test");var report=new LinkedHashMap<String,Object>();
        try{f.db.start();var first=f.new Client(f.start("first"));report.put("firstPid",f.child.pid());
            String c=first.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();String id=f.create(first,c);String task=PersistentDraftRestartTest.path(id);
            first.call(task+"/turns","POST",f.turn(0,"first-fracture"),200);first.call(task+"/drafts","POST","{\"expectedTaskVersion\":2}",201);first.call(task+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",200);
            String root=SubmissionPersistenceTest.ROOT;var prepared=first.call(root,"POST","{\"taskId\":\""+id+"\",\"draftVersion\":1}",200);String op=root+"/"+prepared.path("operationId").asText();
            first.call(op+"/decision","POST","{\"decision\":\"APPROVE\",\"accepted\":true}",200);
            var receipt=first.call(op+"/execute","POST","{}",200).path("receipt");int calls=f.callCount();
            f.child.destroyForcibly();assertThat(f.child.waitFor(10,TimeUnit.SECONDS)).isTrue();var second=f.new Client(f.start("second"));report.put("secondPid",f.child.pid());
            assertThat(second.call(op+"/result","GET",null,200).path("receipt")).isEqualTo(receipt);
            assertThat(second.call(op+"/execute","POST","{}",200).path("receipt")).isEqualTo(receipt);
            assertThat(second.call(root,"POST","{\"taskId\":\""+id+"\",\"draftVersion\":1}",200).path("operationId")).isEqualTo(prepared.path("operationId"));assertThat(f.callCount()).isEqualTo(calls);
            try(var cn=java.sql.DriverManager.getConnection(f.db.getJdbcUrl(),f.db.getUsername(),f.db.getPassword());var st=cn.createStatement();var rs=st.executeQuery("select count(*) from ai.cs_after_sale_application")){rs.next();assertThat(rs.getLong(1)).isEqualTo(1);}
            report.put("receiptUnchangedAfterRestart",true);report.put("applicationCount",1);report.put("newModelCallsForSubmissionOrReplay",0);
        }finally{if(f.child!=null&&f.child.isAlive()){f.child.destroyForcibly();f.child.waitFor(10,TimeUnit.SECONDS);}f.db.stop();Files.writeString(Path.of("target/chapter-26-process-restart.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
}
