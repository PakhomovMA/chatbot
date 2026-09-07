package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RankFusionTest {

    @Test
    void itemsPresentInBothRankingsWin() {
        List<RankFusion.Fused> fused = RankFusion.reciprocalRank(List.of(
                List.of("a", "b", "c"),
                List.of("c", "d", "a")), 60);
        assertThat(fused).extracting(RankFusion.Fused::key).containsExactly("a", "c", "b", "d");
        assertThat(fused.getFirst().score()).isCloseTo(1.0 / 61 + 1.0 / 63, within(1e-12));
        assertThat(fused.getFirst().bestRank()).isEqualTo(1);
        // "b" (rank 2 in one list) and "d" (rank 2 in the other) tie on score; b appeared first.
        assertThat(fused.get(2).score()).isEqualTo(fused.get(3).score());
    }

    @Test
    void singleRankingKeepsOrderAndIgnoresDuplicates() {
        List<RankFusion.Fused> fused = RankFusion.reciprocalRank(List.of(List.of("x", "y", "x", "z")), 0);
        assertThat(fused).extracting(RankFusion.Fused::key).containsExactly("x", "y", "z");
        assertThat(fused).extracting(RankFusion.Fused::score).containsExactly(1.0, 0.5, 1.0 / 3);
    }

    @Test
    void emptyAndInvalidInput() {
        assertThat(RankFusion.reciprocalRank(List.of(), 60)).isEmpty();
        assertThat(RankFusion.reciprocalRank(List.of(List.of(), List.of()), 60)).isEmpty();
        assertThatThrownBy(() -> RankFusion.reciprocalRank(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
