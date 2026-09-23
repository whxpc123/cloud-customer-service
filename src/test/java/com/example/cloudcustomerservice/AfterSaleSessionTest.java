package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.aftersale.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不依赖真实登录的本地受控模式：校验浏览器 Session 归属、CSRF、回环来源与默认身份边界。 */
class AfterSaleSessionTest {
    AfterSaleChatService chat;MockMvc mvc;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){chat=mock(AfterSaleChatService.class);mvc=MockMvcBuilders.standaloneSetup(new LocalAfterSaleController(chat)).build();}
    String token(MockHttpSession s)throws Exception{return json.readTree(mvc.perform(get("/internal/after-sale/session").session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("csrfToken").asText();}
    String create(MockHttpSession s,String token)throws Exception{return json.readTree(mvc.perform(post("/internal/after-sale/conversations").session(s).header("X-AfterSale-CSRF",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("conversationId").asText();}
    @Test void csrfAndConversationOwnershipBlockModelAndHistoryAccess() throws Exception {
        var a=new MockHttpSession();var b=new MockHttpSession();String ta=token(a),tb=token(b),id=create(a,ta);
        mvc.perform(post("/internal/after-sale/conversations/"+id+"/messages").session(b).header("X-AfterSale-CSRF",tb).contentType("application/json").content("{\"message\":\"A10001 能退吗？\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/internal/after-sale/conversations/"+id+"/memory").session(b).header("X-AfterSale-CSRF",tb)).andExpect(status().isNotFound());
        mvc.perform(delete("/internal/after-sale/conversations/"+id+"/memory").session(a)).andExpect(status().isForbidden());
        verifyNoInteractions(chat);
    }
    @Test void identityHeadersCannotChangeServerActor() throws Exception {
        var s=new MockHttpSession();String token=token(s),id=create(s,token);
        mvc.perform(post("/internal/after-sale/conversations/"+id+"/messages").session(s).header("X-AfterSale-CSRF",token).header("X-Demo-User-Id",2002).contentType("application/json").content("{\"message\":\"A10001 能退吗？\"}")).andExpect(status().isOk());
        verify(chat).answer(eq(new AfterSaleModel.Actor("tenant-yunshan",1001)),startsWith("after-sale/"),eq("A10001 能退吗？"));
    }
    @Test void remoteAndCrossOriginRequestsAreNotLocalDemoSessions() throws Exception {
        mvc.perform(get("/internal/after-sale/session").with(r->{r.setRemoteAddr("192.168.1.10");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/internal/after-sale/session").header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/after-sale/session").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        verifyNoInteractions(chat);
    }
}
