package com.personal.chatbot.controller;

import com.embabel.agent.core.support.LlmInteraction;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The switch behind the section tools: with {@code section-tools.enabled=false} the model is handed the
 * store's plain search view, so ToolishRag builds exactly the four tools of Phase 9c. This is the arm of
 * the comparison that measures whether a small local model chooses better with four tools or with six.
 */
@AutoConfigureMockMvc
class SectionToolsDisabledChatTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void sectionToolsOff(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.chat.section-tools.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theModelIsOfferedOnlyTheSearchTools() throws Exception {
        String question = "Anything at all?";
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenReturn(new AgenticDraft("Nothing found.", List.of(), false, "everything"));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<LlmInteraction> captor = ArgumentCaptor.forClass(LlmInteraction.class);
        verify(llmOperations).createObject(any(), captor.capture(), eq(AgenticDraft.class), any(), any());
        assertThat(captor.getValue().getTools()).extracting(t -> t.getDefinition().getName())
                .containsExactlyInAnyOrder("knowledge_base_vectorSearch", "knowledge_base_textSearch",
                        "knowledge_base_broadenChunk", "knowledge_base_zoomOut");
    }
}
