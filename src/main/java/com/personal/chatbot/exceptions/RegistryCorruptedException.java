package com.personal.chatbot.exceptions;

/** The document registry file (and its backup) cannot be read; refuse to start rather than lose data silently. */
public class RegistryCorruptedException extends RuntimeException {

    public RegistryCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
