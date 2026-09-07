package com.personal.chatbot.exceptions;

import com.embabel.agent.api.tool.ToolControlFlowSignal;
import com.personal.chatbot.utils.Throwables;

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
        return Throwables.anyCauseIs(e, ChatCancelledException.class);
    }
}
