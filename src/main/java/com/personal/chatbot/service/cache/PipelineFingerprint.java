package com.personal.chatbot.service.cache;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.utils.Hashes;

/**
 * What a cached result was computed with, apart from the knowledge base (docs/cache-plan.md §3.1): the
 * model, the rendered standing instructions, the Java assembly of the prompts, the chat and retrieval
 * settings, and the embedding and chunking the index is built with. Computed once at startup, since
 * each of these changes only with a restart.
 *
 * <p>For the in-memory layers it is insurance — a restart empties them anyway. A persistent layer would
 * need it in every key.
 *
 * @param value hex SHA-256 over the components
 */
public record PipelineFingerprint(String value) {

    /**
     * Raised by hand with every change to how Java assembles a prompt ({@code GroundedAnswerPrompt}),
     * reads the model's reply or checks its citations. None of that shows in a template or a setting,
     * so without this number a cached answer would outlive the code that produced it.
     */
    public static final int ANSWER_PIPELINE_VERSION = 1;

    /**
     * @param instructionsDigest digest of every rendered standing instruction
     * @param chat               every chat setting, the answer temperature among them. More than the ones
     *                           that change an answer: a setting taken in needlessly costs a miss after a
     *                           restart, one left out would cost a wrong hit
     * @param retrieval          every retrieval setting, for the same reason
     * @param embedding          the embedding the running application writes into the index manifest
     * @param chunker            the chunking it writes there; retrieval runs only while the manifest
     *                           agrees with both (INV-05)
     */
    public static PipelineFingerprint of(String llm, String instructionsDigest, ChatbotProperties.Chat chat,
                                         ChatbotProperties.Retrieval retrieval, EmbeddingFingerprint embedding,
                                         IndexManifest.Chunker chunker) {
        return new PipelineFingerprint(new KeyDigest("pipeline")
                .add(ANSWER_PIPELINE_VERSION).add(llm).add(instructionsDigest)
                .add(chat.toString()).add(retrieval.toString())
                .add(embedding.value()).add(chunker.toString())
                .digest());
    }

    /** The first characters of the value, for a log line. */
    public String shortValue() {
        return Hashes.shortDigest(value);
    }
}
