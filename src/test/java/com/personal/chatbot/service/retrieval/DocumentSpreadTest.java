package com.personal.chatbot.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSpreadTest {

    /** "a:1" belongs to document "a"; the quota counts by that prefix. */
    private static String documentOf(String candidate) {
        return candidate.substring(0, candidate.indexOf(':'));
    }

    @Test
    void aQuotaNeverReturnsFewerHitsThanPlainTruncationWould() {
        List<String> ranked = IntStream.rangeClosed(1, 10).mapToObj(i -> "a:" + i).toList();

        assertThat(new DocumentSpread(0.5).select(ranked, DocumentSpreadTest::documentOf, 8)).hasSize(8);
        assertThat(new DocumentSpread(0.5).select(ranked.subList(0, 3), DocumentSpreadTest::documentOf, 8)).hasSize(3);
        assertThat(new DocumentSpread(0.1).select(ranked, DocumentSpreadTest::documentOf, 8))
                .containsExactlyElementsOf(ranked.subList(0, 8));
    }

    @Test
    void whatTheQuotaHoldsBackComesBackInRankOrderWhenNothingElseCanFillTheSlots() {
        List<String> ranked = List.of("a:1", "a:2", "a:3", "a:4", "b:1", "a:5");

        // Quota 2 per document: a:3 and a:4 are held back, b:1 is promoted, and a:3 returns before a:4.
        assertThat(new DocumentSpread(0.5).select(ranked, DocumentSpreadTest::documentOf, 4))
                .containsExactly("a:1", "a:2", "a:3", "b:1");
    }

    @Test
    void aCandidateWithoutADocumentIsNeverHeldBack() {
        List<String> ranked = List.of("a:1", "a:2", "a:3", "a:4");

        assertThat(new DocumentSpread(0.5).select(ranked, _ -> null, 4)).containsExactlyElementsOf(ranked);
    }

    @Test
    void aShareOutsideItsRangeIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new DocumentSpread(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentSpread(1.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
