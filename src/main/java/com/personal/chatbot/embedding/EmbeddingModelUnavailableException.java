package com.personal.chatbot.embedding;

/** The configured embedding backend cannot be used; the message tells the operator what to do. */
public class EmbeddingModelUnavailableException extends RuntimeException {

    public EmbeddingModelUnavailableException(String message) {
        super(message);
    }

    public EmbeddingModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
