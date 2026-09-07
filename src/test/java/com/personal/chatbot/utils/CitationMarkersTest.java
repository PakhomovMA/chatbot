package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationMarkersTest {

    @Test
    void collectsMarkersAscendingWithoutDuplicates() {
        assertThat(CitationMarkers.collect("See [2] and [1], again [2].")).containsExactly(1, 2);
        assertThat(CitationMarkers.collect("no markers here")).isEmpty();
        assertThat(CitationMarkers.collect("[1234] is too long")).isEmpty();
    }

    @Test
    void removesOnlyTheGivenMarkersTogetherWithLeadingWhitespace() {
        assertThat(CitationMarkers.remove("Answer [1] and more [7].", List.of(7))).isEqualTo("Answer [1] and more.");
        assertThat(CitationMarkers.remove("Answer [1].", List.of())).isEqualTo("Answer [1].");
        assertThat(CitationMarkers.remove("[9] leading", List.of(9))).isEqualTo("leading");
    }
}
