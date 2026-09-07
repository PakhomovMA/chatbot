package com.personal.chatbot.models.agent;

import org.jspecify.annotations.Nullable;

/**
 * Receives progress while the agent works on a question. Null when the caller does not stream.
 * Implementations must be cheap and must never throw: they run inside agent actions.
 */
public interface AnswerStreamSink {

    /** @param detail optional human-readable progress inside the stage, e.g. the search the model just ran */
    void stage(String stage, @Nullable String detail);

    default void stage(String stage) {
        stage(stage, null);
    }

    void delta(String text);

    /**
     * True once the caller is gone (client disconnected, stream timed out). Actions poll this between
     * model calls and tool calls and abandon the run instead of finishing work nobody will read.
     */
    default boolean cancelled() {
        return false;
    }
}
