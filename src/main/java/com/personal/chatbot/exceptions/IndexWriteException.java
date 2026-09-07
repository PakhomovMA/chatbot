package com.personal.chatbot.exceptions;

/** A document could not be (fully) written to the index; partial content has been removed (INV-09). */
public class IndexWriteException extends RuntimeException {

    private final String stage;

    public IndexWriteException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public IndexWriteException(String stage, String message) {
        this(stage, message, null);
    }

    public String stage() {
        return stage;
    }
}
