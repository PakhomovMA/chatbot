package com.personal.chatbot.exceptions;

/**
 * A request arrived after the application started stopping (docs/concurrency-plan.md C08). The
 * window is small — the server's graceful shutdown closes the door right after the ordered stop of
 * background work — but a request that slips through is refused rather than started and abandoned.
 */
public class ServiceStoppingException extends RuntimeException {

    private final String what;

    public ServiceStoppingException(String what) {
        super("The application is shutting down; " + what + " requests are no longer accepted");
        this.what = what;
    }

    /** Which part of the application refused the request. */
    public String what() {
        return what;
    }
}
