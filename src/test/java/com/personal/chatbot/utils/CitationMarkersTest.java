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
    void collectsEveryNumberOfAGroupedMarker() {
        assertThat(CitationMarkers.collect("Modes are low, high and max [1, 3].")).containsExactly(1, 3);
        assertThat(CitationMarkers.collect("Related docs [1,2], introduction [3, 6].")).containsExactly(1, 2, 3, 6);
        assertThat(CitationMarkers.collect("[2, 1] is written out of order")).containsExactly(1, 2);
        assertThat(CitationMarkers.collect("[1, 2345] holds a number that is too long")).isEmpty();
    }

    @Test
    void removesOnlyTheGivenMarkersTogetherWithLeadingWhitespace() {
        assertThat(CitationMarkers.remove("Answer [1] and more [7].", List.of(7))).isEqualTo("Answer [1] and more.");
        assertThat(CitationMarkers.remove("Answer [1].", List.of())).isEqualTo("Answer [1].");
        assertThat(CitationMarkers.remove("[9] leading", List.of(9))).isEqualTo("leading");
    }

    @Test
    void keepsTheSurvivingNumbersOfAGroupedMarker() {
        assertThat(CitationMarkers.remove("Answer [1, 9].", List.of(9))).isEqualTo("Answer [1].");
        assertThat(CitationMarkers.remove("Answer [1, 8, 9].", List.of(8, 9))).isEqualTo("Answer [1].");
        assertThat(CitationMarkers.remove("Answer [8, 9].", List.of(8, 9))).isEqualTo("Answer.");
    }

    @Test
    void leavesAnUntouchedMarkerExactlyAsTheModelWroteIt() {
        assertThat(CitationMarkers.remove("Answer [1,2] and [9].", List.of(9))).isEqualTo("Answer [1,2] and.");
    }
}
