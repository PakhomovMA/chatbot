package com.personal.chatbot.service.chat;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.exceptions.ConversationNotFoundException;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.chat.ChatTimings;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.observability.RequestContext;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.utils.Throwables;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Chat entry point (docs/system-plan.md §2.2): resolves the conversation, runs the knowledge assistant
 * agent through Embabel and maps its goal artifact to the API response. Frontend-facing types only
 * cross this boundary (INV-10). {@link #stream} runs the same agent and reports progress through a
 * listener; both paths end in an identical {@link ChatResponse}.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentPlatform agentPlatform;
    private final ConversationStore conversations;
    private final RetrievalTraceStore traces;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AnswerMode defaultMode;

    public ChatService(AgentPlatform agentPlatform, ConversationStore conversations, RetrievalTraceStore traces,
                       MeterRegistry meterRegistry, Clock clock, ChatbotProperties.Chat settings) {
        this.agentPlatform = agentPlatform;
        this.conversations = conversations;
        this.traces = traces;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.defaultMode = settings.mode();
    }

    public ChatResponse chat(ChatRequest request) {
        return run(request, null);
    }

    /**
     * Answers with progress events: {@code status} per stage, {@code delta} per text fragment and a
     * {@code final} event carrying the verified response (or {@code error}). Blocks until finished.
     */
    public void stream(ChatRequest request, Consumer<ChatStreamEvent> listener) {
        stream(request, listener, () -> false);
    }

    /**
     * @param cancelled reports that the listener is gone; the agent then abandons the run at the next
     *                  model or tool boundary and no terminal event is emitted
     */
    public void stream(ChatRequest request, Consumer<ChatStreamEvent> listener, BooleanSupplier cancelled) {
        AnswerStreamSink sink = new ListenerAnswerStreamSink(listener, cancelled);
        try {
            listener.accept(new ChatStreamEvent.Final(run(request, sink)));
        } catch (Exception e) { // Embabel (Kotlin) can surface checked exceptions such as ExecutionException
            if (ChatCancelledException.isCancellation(e) || cancelled.getAsBoolean()) {
                log.info("Streaming chat abandoned: the client went away ({})", Throwables.rootMessage(e));
                return;
            }
            log.error("Streaming chat failed", e);
            listener.accept(new ChatStreamEvent.Error("The assistant could not answer: " + Throwables.rootMessage(e)));
        }
    }

    private ChatResponse run(ChatRequest request, @Nullable AnswerStreamSink sink) {
        long started = System.nanoTime();
        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String question = request.message().strip();
        ChatRequest.Options options = request.optionsOrDefault();
        List<ConversationTurn> history = conversations.history(conversationId);

        AnswerMode mode = options.mode() != null ? options.mode() : defaultMode;
        UserQuestion input = new UserQuestion(conversationId, messageId, question, history, options.topK(), options.documentIds(), mode, sink);
        GroundedAnswer answer;
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.CONVERSATION_ID, conversationId);
             RequestContext.Scope _ = RequestContext.with(RequestContext.MESSAGE_ID, messageId)) {
            answer = AgentInvocation.create(agentPlatform, GroundedAnswer.class).invoke(input);
        }

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        ChatTimings timings = new ChatTimings(answer.retrievalMs(), Math.max(0, totalMs - answer.retrievalMs()), totalMs);
        RetrievalResult diagnostics = options.diagnostics() ? traces.find(answer.retrievalTraceId()).orElse(null) : null;

        Instant now = clock.instant();
        conversations.append(conversationId, ConversationTurn.user(question, now));
        conversations.append(conversationId, ConversationTurn.assistant(answer.answer(), answer.citations(), now));
        Timer.builder("chatbot.chat").tag("grounding", answer.grounding().name().toLowerCase())
                .tag("mode", sink != null ? "stream" : "sync").tag("answerMode", mode.name().toLowerCase()).register(meterRegistry)
                .record(totalMs, TimeUnit.MILLISECONDS);
        log.info("Chat [{}] {} in {} ms ({} citations, retrieval {} ms{})", messageId, answer.grounding(), totalMs,
                answer.citations().size(), answer.retrievalMs(), sink != null ? ", streamed" : "");
        return new ChatResponse(conversationId, messageId, answer.answer(), answer.grounding(), answer.citations(),
                answer.notes(), timings, answer.retrievalTraceId(), diagnostics);
    }

    public ConversationView conversation(String conversationId) {
        return conversations.find(conversationId).orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    public void deleteConversation(String conversationId) {
        if (!conversations.delete(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
    }
}
