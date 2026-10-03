package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 身份和会话检查必须在任何历史/路由/业务调用之前失败；浏览器提交的历史和演示身份头不可信。 */
class RoutingSessionTest {
    RoutedCustomerService service; MockMvc mvc; ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){service=mock(RoutedCustomerService.class);mvc=MockMvcBuilders.standaloneSetup(new LocalRoutingController(service,new RoutingStatistics())).build();}
    String token(MockHttpSession s)throws Exception{return json.readTree(mvc.perform(get("/internal/routing/session").session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("csrfToken").asText();}
    String create(MockHttpSession s,String t)throws Exception{return json.readTree(mvc.perform(post("/internal/routing/conversations").session(s).header("X-Routing-CSRF",t)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("conversationId").asText();}
    @Test void foreignConversationMissingSessionAndCsrfCannotReachRouter()throws Exception{
        var a=new MockHttpSession();var b=new MockHttpSession();String ta=token(a),tb=token(b),id=create(a,ta);
        mvc.perform(post("/internal/routing/conversations/"+id+"/messages").session(b).header("X-Routing-CSRF",tb).contentType("application/json").content("{\"message\":\"查 A10001\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/internal/routing/conversations/"+id+"/memory").session(b).header("X-Routing-CSRF",tb)).andExpect(status().isNotFound());
        mvc.perform(post("/internal/routing/decide").contentType("application/json").content("{\"message\":\"你好\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/routing/decide").session(a).contentType("application/json").content("{\"message\":\"你好\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void frontendCannotSupplyIdentityTrustedHistoryOrHumanMode()throws Exception{
        var s=new MockHttpSession();String t=token(s),id=create(s,t);
        mvc.perform(post("/internal/routing/conversations/"+id+"/messages").session(s).header("X-Routing-CSRF",t).header("X-Demo-User-Id",2002).contentType("application/json").content("{\"message\":\"查订单\",\"userId\":2002,\"history\":\"A10002\",\"mode\":\"HUMAN_ACTIVE\"}")).andExpect(status().isOk());
        verify(service).answer(eq(new Actor("tenant-yunshan",1001)),argThat(c->c.history().isEmpty()&&c.mode==RoutingConversation.Mode.BOT),eq("查订单"));
    }
    @Test void localAccessAndInputLengthCheckedBeforeService()throws Exception{
        mvc.perform(get("/internal/routing/session").with(r->{r.setRemoteAddr("192.168.1.1");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/internal/routing/session").header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/routing/session").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        var s=new MockHttpSession();String t=token(s);
        mvc.perform(post("/internal/routing/decide").session(s).header("X-Routing-CSRF",t).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("message","x".repeat(2001))))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void sessionHasBoundedConversationsAndExpiryReleasesAllWindows()throws Exception{
        var s=new MockHttpSession();String t=token(s);
        for(int i=0;i<10;i++)create(s,t);
        mvc.perform(post("/internal/routing/conversations").session(s).header("X-Routing-CSRF",t)).andExpect(status().isTooManyRequests());
        s.invalidate();verify(service,times(10)).clear(any(RoutingConversation.class));
    }
}
