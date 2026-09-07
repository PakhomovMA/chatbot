package com.personal.chatbot.exceptions;

import com.embabel.agent.api.tool.ToolControlFlowSignal;
import com.personal.chatbot.utils.Throwables;
import org.jspecify.annotations.Nullable;

/**
 * Raised inside an agent run when the caller is gone: a disconnected client, an expired stream, a
 * connection dropped for falling behind, or shutdown. Marked as an Embabel
 * {@link ToolControlFlowSignal} so the tool loop and the data-binding retry template propagate it
 * immediately instead of retrying the whole run.
 */
public class ChatCancelledException extends RuntimeException implements ToolControlFlowSignal {

    public ChatCancelledException(String messageId) {
        this(messageId, null);
    }

    public ChatCancelledException(String messageId, @Nullable String reason) {
        super("Chat request " + messageId + " was cancelled" + (reason != null ? ": " + reason : ""));
    }

    /** Whether {@code e} or any of its causes is a cancellation. */
    public static boolean isCancellation(Throwable e) {
        return Throwables.anyCauseIs(e, ChatCancelledException.class);
    }
}
