package com.personal.chatbot.service.cache;

import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.utils.Hashes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** K01: what the cache keys are made of (docs/cache-plan.md §3.2). */
class CacheKeysTest {

    private static final String QUESTION = "How do I restart the payments service?";
    private static final PipelineFingerprint PIPELINE = new PipelineFingerprint("a".repeat(64));
    private static final CacheScope SCOPE = new CacheScope(7, PIPELINE);

    @Test
    void questionsEqualAfterNormalisationShareAnAnswerKey() {
        assertThat(answer("  how do i RESTART the payments service")).isEqualTo(answer(QUESTION));
        assertThat(answer("How do I stop the payments service?")).isNotEqualTo(answer(QUESTION));
    }

    @Test
    void everythingElseTheAnswerDependsOnIsInItsKey() {
        String base = answer(QUESTION);
        assertThat(List.of(
                CacheKeys.answer(QUESTION, AnswerMode.AGENTIC, 8, null, AnswerLanguage.EN, CacheKeys.NO_HISTORY, SCOPE),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 5, null, AnswerLanguage.EN, CacheKeys.NO_HISTORY, SCOPE),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, List.of("doc-1"), AnswerLanguage.EN, CacheKeys.NO_HISTORY, SCOPE),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.RU, CacheKeys.NO_HISTORY, SCOPE),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.EN, Hashes.sha256("User: hi"), SCOPE),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.EN, CacheKeys.NO_HISTORY,
                        new CacheScope(8, PIPELINE)),
                CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.EN, CacheKeys.NO_HISTORY,
                        new CacheScope(7, new PipelineFingerprint("b".repeat(64))))))
                .doesNotContain(base)
                .doesNotHaveDuplicates();
    }

    @Test
    void theDocumentFilterIsASetAndAnEmptyOneIsTheWholeKnowledgeBase() {
        assertThat(withDocuments(List.of("b", "a"))).isEqualTo(withDocuments(List.of("a", "b")));
        assertThat(withDocuments(List.of())).isEqualTo(withDocuments(null));
        assertThat(withDocuments(List.of("a", "b"))).isNotEqualTo(withDocuments(List.of("ab")));
    }

    @Test
    void autoIsNotALanguageAnAnswerIsWrittenIn() {
        assertThatThrownBy(() -> CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.AUTO,
                CacheKeys.NO_HISTORY, SCOPE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyIsADigestAndCarriesNoTextOfTheQuestion() {
        assertThat(answer("Where are the Vault secrets stored?")).matches("[0-9a-f]{64}");
        assertThat(derivation(Derivation.EXPAND_REWRITE, "Where are the Vault secrets stored?")).matches("[0-9a-f]{64}");
    }

    @Test
    void aDerivationIsKeyedByItsFinishedPromptAndItsOwnModelSettings() {
        String base = derivation(Derivation.EXPAND_REWRITE, "prompt");
        assertThat(derivation(Derivation.EXPAND_REWRITE, "prompt")).isEqualTo(base);
        assertThat(List.of(
                derivation(Derivation.EXPAND_HYDE, "prompt"),
                // The prompt is taken exactly as the step sends it, whitespace included.
                derivation(Derivation.EXPAND_REWRITE, "prompt "),
                CacheKeys.derivation(Derivation.EXPAND_REWRITE, "prompt", "other rules", "gemma4:12b", 0.1, PIPELINE),
                CacheKeys.derivation(Derivation.EXPAND_REWRITE, "prompt", "rules", "qwen3:14b", 0.1, PIPELINE),
                CacheKeys.derivation(Derivation.EXPAND_REWRITE, "prompt", "rules", "gemma4:12b", 0.0, PIPELINE),
                CacheKeys.derivation(Derivation.EXPAND_REWRITE, "prompt", "rules", "gemma4:12b", 0.1,
                        new PipelineFingerprint("b".repeat(64)))))
                .doesNotContain(base)
                .doesNotHaveDuplicates();
        // An answer and a derivation over the same text are keys of different kinds.
        assertThat(derivation(Derivation.EXPAND_REWRITE, QUESTION)).isNotEqualTo(answer(QUESTION));
    }

    private static String answer(String question) {
        return CacheKeys.answer(question, AnswerMode.DETERMINISTIC, 8, null, AnswerLanguage.EN, CacheKeys.NO_HISTORY, SCOPE);
    }

    private static String withDocuments(@Nullable Collection<String> documentIds) {
        return CacheKeys.answer(QUESTION, AnswerMode.DETERMINISTIC, 8, documentIds, AnswerLanguage.EN,
                CacheKeys.NO_HISTORY, SCOPE);
    }

    private static String derivation(Derivation step, String prompt) {
        return CacheKeys.derivation(step, prompt, "rules", "gemma4:12b", 0.1, PIPELINE);
    }
}
