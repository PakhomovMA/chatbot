package com.personal.chatbot;

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.personal.chatbot.config.ChatbotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 0 gate: the full Spring context (Embabel platform + Lucene/Tika modules on the classpath)
 * starts without Ollama or model files. LLM access is mocked by the Embabel test base class.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatbotApplicationTests extends EmbabelMockitoIntegrationTest {

    @Autowired
    private ChatbotProperties properties;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(agentPlatform).isNotNull();
        assertThat(properties.dataDir()).isNotNull();
        assertThat(Files.isDirectory(properties.dataDir())).isTrue();
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
