package com.personal.chatbot.models.agent;

/**
 * Receives progress while the agent works on a question. Null when the caller does not stream.
 * Implementations must be cheap and must never throw: they run inside agent actions.
 */
public interface AnswerStreamSink {

    void stage(String stage);

    void delta(String text);
}
