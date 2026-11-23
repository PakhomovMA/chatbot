package com.personal.chatbot.eval;

import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class EvalMetricsTest {

    @Test
    void oneRelevantHitAtRankOneHasPerfectScores() {
        EvalQuestion question = question("q1", passage("doc", "expected phrase"));
        List<RetrievedChunk> hits = List.of(hit("doc:1", 1, "doc", "the expected phrase"));

        EvalMetrics.QuestionMetrics metrics = EvalMetrics.evaluate(question, hits, 5, 10);

        assertThat(metrics.firstRelevantRank()).isEqualTo(1);
        assertThat(metrics.recallAtK()).isTrue();
        assertThat(metrics.reciprocalRank()).isEqualTo(1.0);
        assertThat(metrics.ndcgAtK()).isEqualTo(1.0);
        assertThat(metrics.fragmentRecallAtK()).isEqualTo(1.0);
        assertThat(metrics.fullCoverageAtK()).isTrue();
    }

    @Test
    void relevantHitOutsideRecallWindowDoesNotCountAsRecall() {
        EvalQuestion question = question("q1", passage("doc", "answer"));
        List<RetrievedChunk> hits = List.of(hit("doc:6", 6, "doc", "answer"));

        EvalMetrics.QuestionMetrics metrics = EvalMetrics.evaluate(question, hits, 5, 5);

        assertThat(metrics.firstRelevantRank()).isEqualTo(6);
        assertThat(metrics.recallAtK()).isFalse();
        assertThat(metrics.reciprocalRank()).isEqualTo(1.0 / 6);
        assertThat(metrics.ndcgAtK()).isZero();
        assertThat(metrics.fragmentRecallAtK()).isZero();
        assertThat(metrics.fullCoverageAtK()).isFalse();
    }

    @Test
    void twoRelevantChunksAtTheTopHavePerfectMultiChunkNdcg() {
        EvalQuestion question = question("q1", passage("doc", "first"), passage("doc", "second"));
        List<RetrievedChunk> hits = List.of(
                hit("doc:1", 1, "doc", "first"),
                hit("doc:2", 2, "doc", "second"));

        EvalMetrics.QuestionMetrics metrics = EvalMetrics.evaluate(question, hits, 5, 10);

        assertThat(metrics.ndcgAtK()).isEqualTo(1.0);
        assertThat(metrics.fragmentRecallAtK()).isEqualTo(1.0);
        assertThat(metrics.fullCoverageAtK()).isTrue();
    }

    @Test
    void partialMultiChunkCoverageUsesMultiRelevantIdealDcg() {
        EvalQuestion question = question("q1", passage("doc", "first"), passage("doc", "second"));
        List<RetrievedChunk> hits = List.of(hit("doc:1", 1, "doc", "first"));

        EvalMetrics.QuestionMetrics metrics = EvalMetrics.evaluate(question, hits, 5, 10);

        assertThat(metrics.ndcgAtK()).isCloseTo(0.613147, offset(0.000001));
        assertThat(metrics.fragmentRecallAtK()).isEqualTo(0.5);
        assertThat(metrics.fullCoverageAtK()).isFalse();
    }

    @Test
    void aHitIsCountedOnlyOnce() {
        EvalQuestion question = question("q1", passage("doc", "shared"), passage("doc", "shared"));
        List<RetrievedChunk> hits = List.of(hit("doc:1", 1, "doc", "shared"));

        EvalMetrics.QuestionMetrics metrics = EvalMetrics.evaluate(question, hits, 5, 10);

        assertThat(metrics.ndcgAtK()).isCloseTo(0.613147, offset(0.000001));
        assertThat(metrics.fragmentRecallAtK()).isEqualTo(0.5);
        assertThat(metrics.fullCoverageAtK()).isFalse();
    }

    @Test
    void negativeAndEmptyResultsHaveZeroScoresInsteadOfNaN() {
        EvalQuestion negative = new EvalQuestion("neg", "unanswered", List.of("negative"), List.of());

        EvalMetrics.QuestionMetrics negativeMetrics = EvalMetrics.evaluate(negative, List.of(), 5, 10);
        EvalMetrics.QuestionMetrics emptyMetrics = EvalMetrics.evaluate(
                question("q1", passage("doc", "answer")), List.of(), 5, 10);

        for (EvalMetrics.QuestionMetrics metrics : List.of(negativeMetrics, emptyMetrics)) {
            assertThat(metrics.firstRelevantRank()).isEqualTo(-1);
            assertThat(metrics.recallAtK()).isFalse();
            assertThat(metrics.reciprocalRank()).isZero();
            assertThat(metrics.ndcgAtK()).isZero();
            assertThat(metrics.fragmentRecallAtK()).isZero();
            assertThat(metrics.fullCoverageAtK()).isFalse();
        }
    }

    @Test
    void relevanceIgnoresCaseWhitespaceAndUsesAlternatives() {
        EvalQuestion.RelevantPassage target = new EvalQuestion.RelevantPassage(
                "doc", List.of("missing", "Fourteen   DAYS"));
        RetrievedChunk hit = hit("doc:1", 1, "doc", "fourteen\ndays");

        assertThat(EvalMetrics.isRelevant(hit, target)).isTrue();
        assertThat(EvalMetrics.isRelevant(hit("other:1", 1, "other", "fourteen days"), target)).isFalse();
    }

    private static EvalQuestion question(String id, EvalQuestion.RelevantPassage... passages) {
        return new EvalQuestion(id, "question", List.of(), List.of(passages));
    }

    private static EvalQuestion.RelevantPassage passage(String document, String phrase) {
        return new EvalQuestion.RelevantPassage(document, List.of(phrase));
    }

    private static RetrievedChunk hit(String id, int rank, String document, String text) {
        return new RetrievedChunk(id, text,
                new Provenance(document, document, 1, "", List.of(), id, rank, null, null, "text/markdown"),
                0.5, 0.5, 1.0, rank, null);
    }
}
