package com.personal.chatbot.utils;

/** Cause-chain helpers; Embabel (Kotlin) wraps failures several layers deep. */
public final class Throwables {

    private Throwables() {
    }

    /** The deepest cause, guarding against self-referencing chains. */
    public static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Message of the root cause, falling back to its simple class name. */
    public static String rootMessage(Throwable e) {
        Throwable root = rootCause(e);
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    /** True when any link of the cause chain is an instance of {@code type}. */
    public static boolean anyCauseIs(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
