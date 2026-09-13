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

@SpringBootTest(properties="spring.ai.dashscope.api-key=offline-test-placeholder")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EmbeddingLabControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmbeddingModel model;
    @Test void compareReturnsOnlySummaryAndRankReturnsOrderedText() throws Exception {
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1,0},new float[]{1,0}));
        mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content("{\"left\":\"a\",\"right\":\"b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",aMapWithSize(2)))
                .andExpect(jsonPath("$.dimensions").value(2)).andExpect(jsonPath("$.score").value(1));
        mvc.perform(post("/internal/embedding-lab/rank").contentType("application/json").content("{\"query\":\"a\",\"candidates\":[\"b\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches[0].content").value("b"));
    }
    @Test void invalidAndMalformedRequestsAre400WithoutModelCall() throws Exception {
        for(String body:List.of("{}","{\"left\":\" \",\"right\":\"x\"}","null","{invalid")) {
            mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/embedding-lab/rank").contentType("application/json").content("{\"query\":\"a\",\"candidates\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(model);
    }
    @Test void unavailableReturnsStable502WithoutProviderDetails() throws Exception {
        when(model.embed(anyList())).thenThrow(new RuntimeException("SECRET_PROVIDER_TEXT"));
        mvc.perform(post("/internal/embedding-lab/compare").contentType("application/json").content("{\"left\":\"a\",\"right\":\"b\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("SECRET_PROVIDER_TEXT"))));
    }
    @Test void localPageIsServedWithoutModelRequest() throws Exception {
        mvc.perform(get("/internal/embedding-lab")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("语义实验室")));
        verifyNoInteractions(model);
    }
}
