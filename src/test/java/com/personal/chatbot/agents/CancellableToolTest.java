package com.personal.chatbot.agents;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.ToolCallContext;
import com.embabel.agent.api.tool.ToolControlFlowSignal;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.ChatCancellation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancellableToolTest {

    private final Tool delegate = mock(Tool.class);
    private final ChatCancellation cancellation = new ChatCancellation();

    @Test
    void delegatesWhileTheClientIsThere() {
        when(delegate.call(eq("{\"query\":\"x\"}"), any())).thenReturn(new Tool.Result.Text("passages"));
        List<Tool> wrapped = CancellableTool.wrapAll(List.of(delegate), cancellation, "m1");

        Tool.Result result = wrapped.getFirst().call("{\"query\":\"x\"}");

        assertThat(result).isEqualTo(new Tool.Result.Text("passages"));
        verify(delegate).call("{\"query\":\"x\"}", ToolCallContext.EMPTY);
    }

    @Test
    void refusesToSearchOnceTheClientIsGoneWithAControlFlowSignal() {
        Tool wrapped = CancellableTool.wrapAll(List.of(delegate), cancellation, "m1").getFirst();
        cancellation.cancel("client went away");

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(ChatCancelledException.class)
                .isInstanceOf(ToolControlFlowSignal.class) // Embabel must propagate it, not retry the loop
                .hasMessageContaining("m1");
        verify(delegate, never()).call(any(), any());
    }

    @Test
    void cancellationIsRecognisedThroughWrappingExceptions() {
        RuntimeException wrapped = new RuntimeException("agent failed", new IllegalStateException(new ChatCancelledException("m2")));
        assertThat(ChatCancelledException.isCancellation(wrapped)).isTrue();
        assertThat(ChatCancelledException.isCancellation(new RuntimeException("other"))).isFalse();
    }
}
