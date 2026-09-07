package com.personal.chatbot.exceptions;

import com.personal.chatbot.models.index.IndexState;

/** The index cannot serve the request in its current state (incompatible, rebuilding). */
public class IndexUnavailableException extends RuntimeException {

    private final IndexState state;

    public IndexUnavailableException(IndexState state, String message) {
        super(message);
        this.state = state;
    }

    public IndexState state() {
        return state;
    }
}
