package com.example.cloudcustomerservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder","spring.profiles.active=default"})
@AutoConfigureMockMvc
class EmbeddingLabProfileTest {
    @Autowired MockMvc mvc;
    @Test void labPageAndApisDoNotExistOutsideLocal() throws Exception {
        mvc.perform(get("/internal/embedding-lab")).andExpect(status().isNotFound());
        for(String path:new String[]{"compare","rank"}) {
            mvc.perform(post("/internal/embedding-lab/"+path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
