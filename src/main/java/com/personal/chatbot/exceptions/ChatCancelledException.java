package com.personal.chatbot.exceptions;

import com.embabel.agent.api.tool.ToolControlFlowSignal;

/**
 * Raised inside an agent run when the streaming client has gone away. Marked as an Embabel
 * {@link ToolControlFlowSignal} so the tool loop and the data-binding retry template propagate it
 * immediately instead of retrying the whole run.
 */
public class ChatCancelledException extends RuntimeException implements ToolControlFlowSignal {

    public ChatCancelledException(String messageId) {
        super("Chat request " + messageId + " was cancelled by the client");
    }

    /** Whether {@code e} or any of its causes is a cancellation. */
    public static boolean isCancellation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ChatCancelledException) {
                return true;
            }
        }
        return false;
    }
}
