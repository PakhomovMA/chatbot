package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.service.retrieval.SearchExpander;
import com.personal.chatbot.support.ChatSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 9a: when the {@code expandSearch} branch fires, and what it does when the model call fails. */
class EvidenceExpanderTest {

    private static final ChatbotProperties.Retrieval RETRIEVAL =
            new ChatbotProperties.Retrieval(4, 3, 60, 0.0, 0.0, 0.5, 0, 1.0, 200);

    private final Retriever retriever = query -> result(query.query(), true);

    private EvidenceExpander expander(ExpansionStrategy strategy) {
        ChatbotProperties.Chat chat = ChatSettings.of(new ChatbotProperties.ExpandSearch(strategy, 3),
                ChatSettings.NO_DECOMPOSITION, ChatSettings.NO_COMPARISON);
        return new EvidenceExpander(new SearchExpander(retriever, new RetrievalTraceStore(20), RETRIEVAL),
                new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO), new GroundingInstructions(4, 3, 3, 4), chat,
                new SimpleMeterRegistry());
    }

    private static Evidence evidence(RetrievalResult retrieval) {
        return new Evidence(new UserQuestion("c", "m", "how do I bounce it", List.of(), null, null), retrieval);
    }

    @Test
    void theBranchFiresOnlyOnWeakEvidenceOfAQuestionThatHasNotBeenWidenedYet() {
        assertThat(expander(ExpansionStrategy.HYDE).shouldExpand(evidence(result("q", false)))).isTrue();
        assertThat(expander(ExpansionStrategy.NONE).shouldExpand(evidence(result("q", false)))).isFalse();
        assertThat(expander(ExpansionStrategy.HYDE).shouldExpand(evidence(result("q", true)))).isFalse();

        Evidence expanded = expander(ExpansionStrategy.NEIGHBOURS).expand(evidence(result("q", false)), context());
        assertThat(expanded.expanded()).isTrue();
        assertThat(expander(ExpansionStrategy.NEIGHBOURS).shouldExpand(expanded)).isFalse();
    }

    @Test
    void aFailedModelCallLeavesTheQuestionWithTheEvidenceItAlreadyHad() {
        OperationContext context = Mockito.mock(OperationContext.class);
        Mockito.when(context.ai()).thenThrow(new IllegalStateException("no model today"));
        Evidence weak = evidence(result("q", false));

        Evidence expanded = expander(ExpansionStrategy.HYDE).expand(weak, context);

        assertThat(expanded.hits()).isEqualTo(weak.hits());
        assertThat(expanded.expanded()).isTrue();
        assertThat(expanded.retrieval().expansion().queries()).isEmpty();
    }

    /** The strategies that need no model call never touch the context. */
    private static OperationContext context() {
        return Mockito.mock(OperationContext.class);
    }

    private static RetrievalResult result(String query, boolean sufficient) {
        Provenance provenance = new Provenance("doc-1", "Runbook", 1, "Restart", List.of("Restart"), "c1", 1, 0, 3, "text/markdown");
        RetrievedChunk hit = new RetrievedChunk("c1", "text of c1", provenance, 0.6, 0.1, 1.0, 1, null);
        return new RetrievalResult("trace-" + query.hashCode(), query, RetrievalMode.HYBRID, 4, 12, List.of(hit),
                sufficient, 0.6, new RetrievalTimings(1, 1, 0, 3), Instant.now());
    }
}
