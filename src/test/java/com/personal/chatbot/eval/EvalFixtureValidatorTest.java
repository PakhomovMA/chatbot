package com.personal.chatbot.eval;

import com.embabel.agent.rag.model.Chunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalFixtureValidatorTest {

    @Test
    void acceptsACompleteSetWithDistinctMultiChunkEvidence() {
        EvalQuestionSet set = completeSet();

        EvalFixtureValidator.validateSchema(set);
        assertThatCode(() -> EvalFixtureValidator.validateChunks(set, matchingChunks(set))).doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongQuestionAndCategoryQuotas() {
        List<EvalQuestion> questions = new ArrayList<>(completeSet().questions());
        questions.removeLast();

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(new EvalQuestionSet("bad", questions)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("160");
    }

    @Test
    void rejectsMultiChunkShareBelowOneQuarterOfAnExpandedSet() {
        List<EvalQuestion> questions = new ArrayList<>(completeSet().questions());
        for (int i = 0; i < 10; i++) {
            questions.add(new EvalQuestion("extra" + i, "extra question " + i, List.of(),
                    List.of(new EvalQuestion.RelevantPassage("doc-a", List.of("extra answer")))));
        }

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(new EvalQuestionSet("expanded", questions)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("25%");
    }

    @Test
    void rejectsDuplicateNormalizedQuestionText() {
        EvalQuestion original = completeSet().questions().get(1);
        EvalQuestionSet set = replace(completeSet(), 1,
                new EvalQuestion(original.id(), " QUESTION   0 ", original.categories(), original.relevant()));

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(set))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question text")
                .hasMessageContaining("q1");
    }

    @Test
    void rejectsDuplicateIdsAndBlankQuestions() {
        EvalQuestionSet duplicate = replace(completeSet(), 1, completeSet().questions().getFirst());
        EvalQuestionSet blank = replace(completeSet(), 0,
                new EvalQuestion("q0", " ", completeSet().questions().getFirst().categories(),
                        completeSet().questions().getFirst().relevant()));

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(duplicate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("q0");
        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("q0");
    }

    @Test
    void rejectsANegativeCategoryWithRelevantEvidence() {
        EvalQuestion original = completeSet().questions().get(136);
        EvalQuestionSet set = replace(completeSet(), 136,
                new EvalQuestion(original.id(), original.question(), original.categories(),
                        List.of(new EvalQuestion.RelevantPassage("doc-a", List.of("answer")))));

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(set))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(original.id())
                .hasMessageContaining("negative");
    }

    @Test
    void rejectsAQuestionWithoutEvidenceThatIsNotTaggedNegative() {
        EvalQuestion original = completeSet().questions().get(50);
        EvalQuestionSet set = replace(completeSet(), 50,
                new EvalQuestion(original.id(), original.question(), original.categories(), List.of()));

        assertThatThrownBy(() -> EvalFixtureValidator.validateSchema(set))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(original.id())
                .hasMessageContaining("no negative category");
    }

    @Test
    void rejectsAMultiChunkQuestionWithOneTargetRepeatedAcrossChunks() {
        EvalQuestionSet set = completeSet();
        EvalQuestion original = set.questions().getFirst();
        EvalQuestionSet single = replace(set, 0, new EvalQuestion(original.id(), original.question(), original.categories(),
                List.of(original.relevant().getFirst())));
        List<Chunk> chunks = new ArrayList<>(matchingChunks(set));
        chunks.add(Chunk.create("answer 0 a repeated", "doc-a",
                Map.of(ProvenanceChunkTransformer.DOCUMENT_ID, "doc-a"), "q0-repeat", "answer 0 a repeated", null));

        assertThatThrownBy(() -> EvalFixtureValidator.validateChunks(single, chunks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("q0")
                .hasMessageContaining("distinct");
    }

    @Test
    void rejectsUnknownDocumentsAndMissingExpectedPhrases() {
        EvalQuestionSet set = completeSet();
        EvalQuestion original = set.questions().getFirst();
        EvalQuestionSet unknown = replace(set, 0, new EvalQuestion(original.id(), original.question(), original.categories(),
                List.of(new EvalQuestion.RelevantPassage("missing-doc", List.of("answer 0 a")),
                        original.relevant().get(1))));
        EvalQuestionSet missing = replace(set, 0, new EvalQuestion(original.id(), original.question(), original.categories(),
                List.of(new EvalQuestion.RelevantPassage("doc-a", List.of("not in the corpus")),
                        original.relevant().get(1))));

        assertThatThrownBy(() -> EvalFixtureValidator.validateChunks(unknown, matchingChunks(set)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing-doc");
        assertThatThrownBy(() -> EvalFixtureValidator.validateChunks(missing, matchingChunks(set)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in the corpus");
    }

    @Test
    void rejectsMultiChunkLabelsThatCollapseIntoOneChunk() {
        EvalQuestionSet set = completeSet();
        EvalQuestion original = set.questions().getFirst();
        EvalQuestionSet collapsed = replace(set, 0, new EvalQuestion(original.id(), original.question(), original.categories(),
                List.of(original.relevant().getFirst(),
                        new EvalQuestion.RelevantPassage("doc-a", List.of("answer 0 a")))));
        List<Chunk> chunks = new ArrayList<>(matchingChunks(set));
        chunks.removeIf(chunk -> chunk.getId().equals("q0-1"));

        assertThatThrownBy(() -> EvalFixtureValidator.validateChunks(collapsed, chunks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("q0")
                .hasMessageContaining("distinct");
    }

    private static EvalQuestionSet completeSet() {
        List<EvalQuestion> questions = new ArrayList<>();
        for (int i = 0; i < 136; i++) {
            List<String> categories = new ArrayList<>();
            if (i < 40) {
                categories.add("multi-chunk");
            }
            if (i < 12) {
                categories.add("comparison");
            }
            if (i >= 12 && i < 24) {
                categories.add("ru-to-en");
            }
            if (i >= 24 && i < 36) {
                categories.add("en-to-ru");
            }
            List<EvalQuestion.RelevantPassage> relevant = new ArrayList<>();
            relevant.add(new EvalQuestion.RelevantPassage("doc-a", List.of("answer " + i + " a")));
            if (i < 40) {
                relevant.add(new EvalQuestion.RelevantPassage("doc-b", List.of("answer " + i + " b")));
            }
            questions.add(new EvalQuestion("q" + i, "question " + i, categories, relevant));
        }
        for (int i = 0; i < 24; i++) {
            questions.add(new EvalQuestion("q" + (136 + i), "negative " + i,
                    List.of(i < 18 ? "near-topic" : "negative"), List.of()));
        }
        return new EvalQuestionSet("complete", questions);
    }

    private static List<Chunk> matchingChunks(EvalQuestionSet set) {
        List<Chunk> chunks = new ArrayList<>();
        for (EvalQuestion question : set.questions()) {
            for (int i = 0; i < question.relevant().size(); i++) {
                EvalQuestion.RelevantPassage target = question.relevant().get(i);
                String text = target.mustContain().getFirst();
                chunks.add(Chunk.create(text, target.document(),
                        Map.of(ProvenanceChunkTransformer.DOCUMENT_ID, target.document()),
                        question.id() + "-" + i, text, null));
            }
        }
        return chunks;
    }

    private static EvalQuestionSet replace(EvalQuestionSet set, int index, EvalQuestion question) {
        List<EvalQuestion> questions = new ArrayList<>(set.questions());
        questions.set(index, question);
        return new EvalQuestionSet(set.description(), questions);
    }
}
