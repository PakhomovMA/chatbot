package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CosineScoresTest {

    @Test
    void mapsPlainCosineOntoTheLuceneScale() {
        assertThat(CosineScores.toLuceneScore(1.0)).isEqualTo(1.0);
        assertThat(CosineScores.toLuceneScore(0.0)).isEqualTo(0.5);
        assertThat(CosineScores.toLuceneScore(-1.0)).isEqualTo(0.0);
    }

    @Test
    void roundTripsBothWays() {
        for (double cosine : new double[]{-1.0, -0.25, 0.0, 0.3, 0.87, 1.0}) {
            assertThat(CosineScores.fromLuceneScore(CosineScores.toLuceneScore(cosine))).isCloseTo(cosine, within(1e-12));
        }
    }
}
