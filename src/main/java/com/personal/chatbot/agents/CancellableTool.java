package com.personal.chatbot.agents;

import com.embabel.agent.api.tool.DelegatingTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.ToolCallContext;
import com.personal.chatbot.exceptions.ChatCancelledException;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Decorates a tool so that every call first checks whether the caller is still there. The LLM call
 * itself cannot be interrupted mid-generation, so this is the point where an abandoned agentic run
 * stops: before the next search rather than after the next answer.
 */
public record CancellableTool(Tool delegate, BooleanSupplier cancelled, String messageId) implements DelegatingTool {

    public static List<Tool> wrapAll(List<Tool> tools, BooleanSupplier cancelled, String messageId) {
        return tools.stream().<Tool>map(tool -> new CancellableTool(tool, cancelled, messageId)).toList();
    }

    @NotNull
    @Override
    public Tool getDelegate() {
        return delegate;
    }

    @NotNull
    @Override
    public Definition getDefinition() {
        return delegate.getDefinition();
    }

    @NotNull
    @Override
    public Metadata getMetadata() {
        return delegate.getMetadata();
    }

    @NotNull
    @Override
    public Result call(@NotNull String input) {
        return call(input, ToolCallContext.EMPTY);
    }

    @NotNull
    @Override
    public Result call(@NotNull String input, @NotNull ToolCallContext context) {
        if (cancelled.getAsBoolean()) {
            throw new ChatCancelledException(messageId);
        }
        return delegate.call(input, context);
    }
}
