package com.personal.chatbot.exceptions;

/** An upload was rejected before anything was stored; {@link Reason} maps to the HTTP status. */
public class InvalidUploadException extends RuntimeException {

    public enum Reason {
        EMPTY,
        BAD_FILENAME,
        UNSUPPORTED_TYPE,
        TOO_LARGE
    }

    private final Reason reason;

    public InvalidUploadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
