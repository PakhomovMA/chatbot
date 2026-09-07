package com.personal.chatbot.controller;

import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Client-side routes of the bundled Vue app fall back to index.html; API routes do not. */
@AutoConfigureMockMvc
class SpaControllerTest extends AbstractChatbotIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clientRoutesForwardToIndex() throws Exception {
        for (String route : new String[]{"/", "/chat", "/knowledge", "/playground"}) {
            mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
        mockMvc.perform(get("/api/knowledge-base/status")).andExpect(forwardedUrl(null));
    }
}
