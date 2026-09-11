package com.personal.chatbot.service.cache;

import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.utils.Hashes;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * The keys of the result caches (docs/cache-plan.md §3.2). A key is a hex SHA-256 and nothing else: the
 * question enters it in its {@link QuestionNormalizer norm-v1} form and only hashed, so a key may appear
 * in a log line or a span without carrying what was asked. The text itself lives in the entry, which
 * never leaves the process.
 */
public final class CacheKeys {

    /** {@code historyDigest} of a first turn, whose prompt carries no history. */
    public static final String NO_HISTORY = Hashes.sha256("");

    private CacheKeys() {
    }

    /**
     * Key of an answer: everything the answer depends on apart from the knowledge base and the pipeline,
     * which the scope pins. It identifies the question, not the path the run takes — two questions equal
     * after normalisation may go down different branches, and both answers are valid in one scope.
     *
     * @param mode          the answer mode the run resolved, not what the request may have left empty
     * @param topK          resolved likewise
     * @param documentIds   the document filter; order does not matter, and absent and empty both mean
     *                      the whole knowledge base, as they do to retrieval
     * @param language      the resolved language; {@code AUTO} is not a language an answer is written in
     * @param historyDigest SHA-256 of exactly the history text the answer prompt carries,
     *                      {@link #NO_HISTORY} on a first turn
     */
    public static String answer(String question, AnswerMode mode, int topK, @Nullable Collection<String> documentIds,
                                AnswerLanguage language, String historyDigest, CacheScope scope) {
        if (language == AnswerLanguage.AUTO) {
            throw new IllegalArgumentException("Resolve AUTO to the language of the question before building a key");
        }
        List<String> documents = documentIds == null ? List.of() : documentIds.stream().sorted().distinct().toList();
        return new KeyDigest("answer")
                .add(QuestionNormalizer.VERSION).add(QuestionNormalizer.normalize(question))
                .add(mode.name()).add(topK).addAll(documents).add(language.name()).add(historyDigest)
                .add(scope.revision()).add(scope.pipeline().value())
                .digest();
    }

    /**
     * Key of a question-preparation step. Built from the finished prompt rather than from the fields
     * that went into it, so a change to how the step assembles its prompt changes the key by itself.
     * No revision: these steps never see the knowledge base.
     *
     * @param userPrompt   the exact user message of the step
     * @param instructions the step's rendered standing instructions
     * @param temperature  the step's own temperature, which is not the answer's
     */
    public static String derivation(Derivation step, String userPrompt, String instructions, String llm,
                                    double temperature, PipelineFingerprint pipeline) {
        return new KeyDigest("derivation")
                .add(step.label()).add(userPrompt).add(instructions).add(llm).add(temperature)
                .add(pipeline.value())
                .digest();
    }
}
