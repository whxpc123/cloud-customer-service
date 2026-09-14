package com.example.cloudcustomerservice;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 第六章本地实验 HTTP 契约测试，向量模型由 Mockito 替换。
 * 同时断言状态码、响应字段和无效请求不会调用模型，避免仅检查页面能打开。
 */

@SpringBootTest(properties="spring.ai.dashscope.api-key=offline-test-placeholder")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EmbeddingLabControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmbeddingModel model;
    /**
     * 使用已知向量检查比较摘要和候选排序 JSON，确保接口不返回完整向量数组。
     */
    @Test void compareReturnsOnlySummaryAndRankReturnsOrderedText() throws Exception {
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1,0},new float[]{1,0}));
        mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content("{\"left\":\"a\",\"right\":\"b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",aMapWithSize(2)))
                .andExpect(jsonPath("$.dimensions").value(2)).andExpect(jsonPath("$.score").value(1));
        mvc.perform(post("/internal/embedding-lab/rank").contentType("application/json").content("{\"query\":\"a\",\"candidates\":[\"b\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches[0].content").value("b"));
    }
    /**
     * 缺字段、空正文和损坏 JSON 都应返回 400，避免无效请求触发模型开销。
     */
    @Test void invalidAndMalformedRequestsAre400WithoutModelCall() throws Exception {
        for(String body:List.of("{}","{\"left\":\" \",\"right\":\"x\"}","null","{invalid")) {
            mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/embedding-lab/rank").contentType("application/json").content("{\"query\":\"a\",\"candidates\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(model);
    }
    /**
     * 模型抛出带供应商标记的异常，HTTP 只返回统一 502 和稳定错误码。
     */
    @Test void unavailableReturnsStable502WithoutProviderDetails() throws Exception {
        when(model.embed(anyList())).thenThrow(new RuntimeException("SECRET_PROVIDER_TEXT"));
        mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content("{\"left\":\"a\",\"right\":\"b\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("SECRET_PROVIDER_TEXT"))));
    }
    /**
     * 读取本地实验 HTML，应成功返回页面且没有模型交互。
     */
    @Test void localPageIsServedWithoutModelRequest() throws Exception {
        mvc.perform(get("/internal/embedding-lab")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("语义实验室")));
        verifyNoInteractions(model);
    }
}
