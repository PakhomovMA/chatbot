package com.personal.chatbot.support;

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that boot the full Spring context without Ollama or model files:
 * Embabel LLM operations are mocked by the superclass, embeddings come from {@link FakeTextEmbedder}.
 */
@ActiveProfiles("hermetic")
@Import(FakeEmbeddingConfiguration.class)
public abstract class AbstractChatbotIntegrationTest extends EmbabelMockitoIntegrationTest {
}
