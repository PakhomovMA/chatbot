package com.personal.chatbot;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Context gate: the full Spring context (Embabel platform + Lucene/Tika modules on the classpath)
 * starts without Ollama or model files.
 */
@AutoConfigureMockMvc
class ChatbotApplicationTests extends AbstractChatbotIntegrationTest {

    @Autowired
    private ChatbotProperties properties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(agentPlatform).isNotNull();
        assertThat(properties.dataDir()).isNotNull();
        assertThat(Files.isDirectory(properties.dataDir())).isTrue();
        assertThat(properties.embedding().provider()).isEqualTo("fake");
    }

    @Test
    void healthEndpointIsUpAndReportsEmbeddingComponent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.embedding.status").value("UP"))
                .andExpect(jsonPath("$.components.embedding.details.provider").value("fake"))
                .andExpect(jsonPath("$.components.embedding.details.fingerprint").isString());
    }
}
