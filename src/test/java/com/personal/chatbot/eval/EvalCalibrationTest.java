package com.personal.chatbot.eval;

import com.personal.chatbot.models.retrieval.RetrievalMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class EvalCalibrationTest {

    @Test
    void splitIsDeterministicCompleteAndStratified() {
        List<EvalQuestion> questions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<String> categories = i % 2 == 0 ? List.of("multi-chunk", "comparison") : List.of("ru-to-en");
            questions.add(new EvalQuestion("q" + i, "question", categories,
                    List.of(new EvalQuestion.RelevantPassage("doc", List.of("answer")))));
        }
        questions.add(new EvalQuestion("neg", "question", List.of("near-topic"), List.of()));

        EvalCalibration.Split first = EvalCalibration.split(questions, 20261123);
        EvalCalibration.Split second = EvalCalibration.split(questions, 20261123);

        assertThat(first).isEqualTo(second);
        assertThat(first.calibration()).hasSize(11);
        assertThat(first.validation()).hasSize(10);
        HashSet<String> ids = new HashSet<>();
        first.calibration().forEach(question -> ids.add(question.id()));
        first.validation().forEach(question -> ids.add(question.id()));
        assertThat(ids).hasSize(21);
        assertThat(first.calibration()).anyMatch(EvalQuestion::isMultiChunk);
        assertThat(first.validation()).anyMatch(EvalQuestion::isMultiChunk);
        assertThat(first.calibration()).anyMatch(question -> question.categories().contains("ru-to-en"));
        assertThat(first.validation()).anyMatch(question -> question.categories().contains("ru-to-en"));
    }

    @Test
    void thresholdUsesMidpointAndMaximisesYoudenJ() {
        List<EvalCalibration.SufficiencyObservation> observations = List.of(
                new EvalCalibration.SufficiencyObservation("p1", true, 0.5),
                new EvalCalibration.SufficiencyObservation("p2", true, 0.6),
                new EvalCalibration.SufficiencyObservation("n1", false, 0.1),
                new EvalCalibration.SufficiencyObservation("n2", false, 0.2));

        EvalCalibration.ThresholdSelection selection = EvalCalibration.selectThreshold(observations);

        assertThat(selection.threshold()).isCloseTo(0.35, offset(0.000001));
        assertThat(selection.truePositiveRate()).isEqualTo(1.0);
        assertThat(selection.falsePositiveRate()).isZero();
        assertThat(selection.margin()).isCloseTo(0.15, offset(0.000001));
    }

    @Test
    void thresholdTieBreaksByDistanceToObservedScores() {
        List<EvalCalibration.SufficiencyObservation> observations = List.of(
                new EvalCalibration.SufficiencyObservation("p1", true, 0.2),
                new EvalCalibration.SufficiencyObservation("p2", true, 0.8),
                new EvalCalibration.SufficiencyObservation("n1", false, 0.0),
                new EvalCalibration.SufficiencyObservation("n2", false, 0.4));

        EvalCalibration.ThresholdSelection selection = EvalCalibration.selectThreshold(observations);

        assertThat(selection.threshold()).isCloseTo(0.6, offset(0.000001));
        assertThat(selection.truePositiveRate()).isEqualTo(0.5);
        assertThat(selection.falsePositiveRate()).isZero();
        assertThat(selection.margin()).isCloseTo(0.2, offset(0.000001));
    }

    @Test
    void thresholdRejectsMissingPositiveOrNegativeObservations() {
        assertThatThrownBy(() -> EvalCalibration.selectThreshold(List.of(
                new EvalCalibration.SufficiencyObservation("p", true, 0.5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> EvalCalibration.selectThreshold(List.of(
                new EvalCalibration.SufficiencyObservation("n", false, 0.2))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void outcomeJsonContainsPerQuestionMetricsAndSplit() {
        var outcome = new RagEvalTest.QuestionOutcome("q1", RetrievalMode.HYBRID, 1, 0.5, true, 12,
                List.of("doc"), List.of("multi-chunk"), false, "calibration", true, 1.0, 0.75, 0.5, false, 1.0, true);

        String json = JsonMapper.builder().build().writeValueAsString(outcome);

        assertThat(json).contains("\"recallAt5\":true", "\"reciprocalRank\":1.0", "\"ndcgAt10\":0.75",
                "\"fragmentRecallAt5\":0.5", "\"fullCoverageAt5\":false", "\"split\":\"calibration\"",
                "\"categories\":[\"multi-chunk\"]", "\"negative\":false", "\"fragmentRecallInBudget\":1.0",
                "\"fullCoverageInBudget\":true");
    }
}
