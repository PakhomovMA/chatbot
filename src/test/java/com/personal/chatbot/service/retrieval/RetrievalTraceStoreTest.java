package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalTraceStoreTest {

    private static RetrievalResult result(String id) {
        return new RetrievalResult(id, "q", RetrievalMode.HYBRID, 8, 24, List.of(), false, -1,
                new RetrievalTimings(0, 0, 0, 0), Instant.now());
    }

    @Test
    void keepsNewestUpToCapacity() {
        RetrievalTraceStore store = new RetrievalTraceStore(2);
        store.record(result("1"));
        store.record(result("2"));
        store.record(result("3"));
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.recent(10)).extracting(RetrievalResult::traceId).containsExactly("3", "2");
        assertThat(store.recent(1)).extracting(RetrievalResult::traceId).containsExactly("3");
        assertThat(store.find("1")).isEmpty();
        assertThat(store.find("2")).isPresent();
    }

    /** An answer served from the cache records its retrieval again (docs/cache-plan.md §3.4). */
    @Test
    void aResultRecordedAgainMovesToTheFrontInsteadOfAppearingTwice() {
        RetrievalTraceStore store = new RetrievalTraceStore(3);
        store.record(result("1"));
        store.record(result("2"));
        store.record(result("1"));

        assertThat(store.recent(10)).extracting(RetrievalResult::traceId).containsExactly("1", "2");
    }
}
