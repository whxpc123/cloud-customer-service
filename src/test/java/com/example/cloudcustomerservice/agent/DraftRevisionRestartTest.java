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

/** 草稿正文与确认回执经过真正 JVM 退出后原样读取，GET 不靠模型重新构造内容。 */
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class DraftRevisionRestartTest {
    @Test void revisionAndReceiptSurviveRestartWithoutModelAndNewVersionIsUnconfirmed()throws Exception{
        var fixture=new PersistentDraftRestartTest();
        fixture.work=Files.createTempDirectory("ch23-process-");fixture.ready=fixture.work.resolve("ready");fixture.calls=fixture.work.resolve("calls");fixture.blocked=fixture.work.resolve("blocked");
        fixture.db=new PostgreSQLContainer<>(DockerImageName.parse(AcceptanceDatabase.IMAGE).asCompatibleSubstituteFor("postgres")).withDatabaseName("chapter23_restart_test");
        var report=new LinkedHashMap<String,Object>();
        try{
            fixture.db.start();var first=fixture.new Client(fixture.start("first"));report.put("firstPid",fixture.child.pid());
            String conversation=first.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();String id=fixture.create(first,conversation);String path=PersistentDraftRestartTest.path(id);
            first.call(path+"/turns","POST",fixture.turn(0,"first-fracture"),200);
            var v1=first.call(path+"/drafts","POST","{\"expectedTaskVersion\":2}",201);
            var receipt=first.call(path+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",200);
            assertThat(fixture.callCount()).isEqualTo(4);fixture.child.destroyForcibly();assertThat(fixture.child.waitFor(10,TimeUnit.SECONDS)).isTrue();
            var second=fixture.new Client(fixture.start("second"));report.put("secondPid",fixture.child.pid());
            var restored=second.call(path+"/drafts/1","GET",null,200);assertThat(restored.path("body")).isEqualTo(v1.path("body"));assertThat(restored.path("confirmation")).isEqualTo(receipt);assertThat(restored.path("confirmationEffective").asBoolean()).isTrue();
            assertThat(second.call(path+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",200)).isEqualTo(receipt);assertThat(fixture.callCount()).isEqualTo(4);
            second.call(path+"/turns","POST",fixture.turn(4,"second-button"),200);var old=second.call(path+"/drafts/1","GET",null,200);
            assertThat(old.path("body")).isEqualTo(v1.path("body"));assertThat(old.path("confirmation")).isEqualTo(receipt);assertThat(old.path("confirmationEffective").asBoolean()).isFalse();
            var v2=second.call(path+"/drafts","POST","{\"expectedTaskVersion\":6}",201);assertThat(v2.path("draftVersion").asInt()).isEqualTo(2);assertThat(v2.path("confirmation").isNull()).isTrue();assertThat(fixture.callCount()).isEqualTo(8);
            second.call(path+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",409);
            report.put("bodyAndReceiptIdenticalAfterRestart",true);report.put("restoredWithoutModel",true);report.put("newVersionUnconfirmed",true);report.put("historicalReceiptRetainedButIneffective",true);report.put("totalControlledModelCalls",fixture.callCount());
        }finally{
            if(fixture.child!=null&&fixture.child.isAlive()){fixture.child.destroyForcibly();fixture.child.waitFor(10,TimeUnit.SECONDS);}fixture.db.stop();
            Files.writeString(Path.of("target/chapter-23-process-restart.json"),fixture.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
