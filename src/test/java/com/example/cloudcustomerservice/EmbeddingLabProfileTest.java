package com.example.cloudcustomerservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证实验入口的 local 环境限制：未启用该环境时页面与 API 均不注册。
 */

@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder","spring.profiles.active=default"})
@AutoConfigureMockMvc
class EmbeddingLabProfileTest {
    @Autowired MockMvc mvc;
    /**
     * 在非 local 环境尝试实验页面与 API，验证调试入口不会被默认环境公开。
     */
    @Test void labPageAndApisDoNotExistOutsideLocal() throws Exception {
        mvc.perform(get("/internal/hybrid-search")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/hybrid-search/compare").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/rerank")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/rerank/c/compare").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/query-expansion")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/query-expansion/c/expand").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/query-transformation")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/query-transformation/c/compress").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/embedding-lab")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/knowledge-admin")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/knowledge-admin/documents")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge-admin/evaluations").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        // 第十章同样只在 local 与 knowledge 同时启用时注册。
        mvc.perform(get("/internal/advisor-rag")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/advisor-rag/conversations")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/advisor-rag/conversations/c/messages").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/rag")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/rag/answer").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/knowledge")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge/seed")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge/search").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge/import").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(multipart("/internal/knowledge/import/file").file("file",new byte[]{1})).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge/preview").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/knowledge/files/refund-policy/preview")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/knowledge/previews/fake/import")).andExpect(status().isNotFound());
        for(String path:new String[]{"compare","rank"}) {
            mvc.perform(post("/internal/embedding-lab/"+path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
