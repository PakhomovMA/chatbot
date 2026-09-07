package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Adapts the agent-facing {@link AnswerStreamSink} to the SSE event listener of a streaming chat
 * request: stages become {@code status} events, fragments become {@code delta} events, and the
 * cancellation check reports whether the client is still there.
 */
final class ListenerAnswerStreamSink implements AnswerStreamSink {

    private final Consumer<ChatStreamEvent> listener;
    private final BooleanSupplier cancelled;

    ListenerAnswerStreamSink(Consumer<ChatStreamEvent> listener, BooleanSupplier cancelled) {
        this.listener = listener;
        this.cancelled = cancelled;
    }

    @Override
    public void stage(String stage, @Nullable String detail) {
        listener.accept(new ChatStreamEvent.Status(stage, detail));
    }

    @Override
    public void delta(String text) {
        if (!text.isEmpty()) {
            listener.accept(new ChatStreamEvent.Delta(text));
        }
    }

    @Override
    public boolean cancelled() {
        return cancelled.getAsBoolean();
    }
}
