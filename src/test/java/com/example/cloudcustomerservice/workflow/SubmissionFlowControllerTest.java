package com.example.cloudcustomerservice.workflow;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import com.example.cloudcustomerservice.security.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** 真实控制器、过滤链、CSRF 和 Graph，采用已认证测试 Session；无数据库或模型依赖。 */
@WebMvcTest(SubmissionFlowController.class)
@Import({HandoffSecurityConfiguration.class,SubmissionFlowLab.class}) @ActiveProfiles({"local","knowledge"})
class SubmissionFlowControllerTest {
    @Autowired MockMvc mvc;
    final String base="/internal/draft-tasks/flow";

    /** 仅测试创建身份；正式接口仍从原来的登录安全链取得 HandoffPrincipal。 */
    MockHttpSession session(boolean support) {
        var p=new HandoffPrincipal("test","unused",new Actor("fixture",support?9001:1001),
            List.of(new SimpleGrantedAuthority(support?"support:serve":"customer:chat")));
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(p,p.getPassword(),p.getAuthorities()));
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);return session;
    }

    @Test void anonymousSeesOnlyPageShell() throws Exception {
        mvc.perform(get(base)).andExpect(status().isOk());
        mvc.perform(get(base+"/definition")).andExpect(status().isUnauthorized());
        mvc.perform(post(base+"/runs").with(csrf()).contentType("application/json").content("{\"scenario\":\"APPROVED\"}")).andExpect(status().isUnauthorized());
    }
    @Test void csrfSupportAndRemoteAccessStayProtected() throws Exception {
        mvc.perform(post(base+"/runs").session(session(false)).contentType("application/json").content("{\"scenario\":\"APPROVED\"}")).andExpect(status().isForbidden());
        mvc.perform(get(base+"/definition").session(session(true))).andExpect(status().isForbidden());
        mvc.perform(post(base+"/runs").session(session(true)).with(csrf()).contentType("application/json").content("{\"scenario\":\"APPROVED\"}")).andExpect(status().isForbidden());
        mvc.perform(get(base).with(r->{r.setRemoteAddr("10.2.3.4");return r;})).andExpect(status().isForbidden());
        mvc.perform(get(base).header("Origin","https://other.example")).andExpect(status().isForbidden());
    }
    @Test void definitionIsServerOwnedAndDoesNotExposeInternalState() throws Exception {
        mvc.perform(get(base+"/definition").session(session(false))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.mode").value("DETERMINISTIC_FIXTURE"))
            .andExpect(jsonPath("$.definition.nodes.length()").value(10)).andExpect(jsonPath("$.scenarios.length()").value(8));
    }
    @ParameterizedTest @CsvSource({"PENDING,WAITING_APPROVAL,0,0","REJECTED,REJECTED,0,0","INVALID_DRAFT,BLOCKED,0,0",
        "INVALID_APPROVAL,BLOCKED,0,0","APPROVED,SIMULATION_COMPLETED,1,1","CHANGED_BEFORE_EXECUTION,BLOCKED,1,0","UNKNOWN_RESULT,RECONCILIATION_REQUIRED,1,1"})
    void routesComeFromRealGraph(String scenario,String outcome,int calls,int effects) throws Exception {
        mvc.perform(post(base+"/runs").session(session(false)).with(csrf()).contentType("application/json").content("{\"scenario\":\""+scenario+"\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.result.status").value(outcome)).andExpect(jsonPath("$.result.actualSubmitted").value(false))
            .andExpect(jsonPath("$.result.refundExecuted").value(false)).andExpect(jsonPath("$.result.frameworkEnded").value(true))
            .andExpect(jsonPath("$.counters.simulationCalls").value(calls)).andExpect(jsonPath("$.counters.simulatedEffects").value(effects))
            .andExpect(jsonPath("$.counters.modelCalls").value(0)).andExpect(jsonPath("$.result.threadId").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"scenario\":null}","{\"scenario\":\"not-defined\"}",
        "{\"scenario\":\"APPROVED\",\"approval\":\"APPROVED\"}","{\"scenario\":\"APPROVED\",\"status\":\"SIMULATION_COMPLETED\"}",
        "{\"scenario\":\"APPROVED\",\"taskId\":\"some-real-task\"}","{\"scenario\":\"APPROVED\",\"actor\":1001}"})
    void clientCannotUploadApprovalStateOrBusinessIdentity(String body) throws Exception {
        mvc.perform(post(base+"/runs").session(session(false)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    @Test void nodeExceptionIsVisibleAsFailureNotSuccess() throws Exception {
        mvc.perform(post(base+"/runs").session(session(false)).with(csrf()).contentType("application/json").content("{\"scenario\":\"APPROVAL_READ_ERROR\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("GRAPH_FAILED"))
            .andExpect(jsonPath("$.result").doesNotExist()).andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
