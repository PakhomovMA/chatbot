package com.personal.chatbot.models.chat;

import org.jspecify.annotations.Nullable;

/**
 * Events of {@code POST /api/chat/stream} (docs/system-plan.md D11), sent as SSE with the event name
 * equal to {@link #type()}: {@code status} → {@code delta}* → {@code final}, or {@code error}.
 */
public sealed interface ChatStreamEvent {

    String type();

    /**
     * Which stage the assistant is in: {@code retrieving}, {@code researching}, {@code generating} or
     * {@code verifying}; {@code detail} optionally narrates progress inside the stage (e.g. the search
     * the model just ran in agentic mode). Repeated for the same stage as progress arrives.
     */
    record Status(String stage, @Nullable String detail) implements ChatStreamEvent {
        public Status(String stage) {
            this(stage, null);
        }

        @Override
        public String type() {
            return "status";
        }
    }

    /** A fragment of the answer text, in order. Markers {@code [n]} may be split across deltas. */
    record Delta(String text) implements ChatStreamEvent {
        @Override
        public String type() {
            return "delta";
        }
    }

    /** The verified answer; identical in shape to the non-streaming response. */
    record Final(ChatResponse response) implements ChatStreamEvent {
        @Override
        public String type() {
            return "final";
        }
    }

    record Error(String message) implements ChatStreamEvent {
        @Override
        public String type() {
            return "error";
        }
    }
}
