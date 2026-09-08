package com.personal.chatbot.service.chat;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.exceptions.ConversationNotFoundException;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.ChatCancellation;
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
        return chat(request, ChatCancellation.none());
    }

    public ChatResponse chat(ChatRequest request, ChatCancellation cancellation) {
        return run(request, null, cancellation);
    }

    /**
     * Answers with progress events: {@code status} per stage, {@code delta} per text fragment and a
     * {@code final} event carrying the verified response (or {@code error}). Blocks until finished.
     */
    public void stream(ChatRequest request, Consumer<ChatStreamEvent> listener) {
        stream(request, listener, ChatCancellation.none());
    }

    /**
     * @param cancellation the request's completion signal; once it fires, the run is abandoned at the
     *                     next retrieval, model or tool boundary, no terminal event is emitted and no
     *                     partial answer reaches the conversation history
     */
    public void stream(ChatRequest request, Consumer<ChatStreamEvent> listener, ChatCancellation cancellation) {
        AnswerStreamSink sink = new ListenerAnswerStreamSink(listener, cancellation);
        try {
            listener.accept(new ChatStreamEvent.Final(run(request, sink, cancellation)));
        } catch (Exception e) { // Embabel (Kotlin) can surface checked exceptions such as ExecutionException
            if (ChatCancelledException.isCancellation(e) || cancellation.isCancelled()) {
                log.info("Streaming chat abandoned ({}): {}", cancellation.reason(), Throwables.rootMessage(e));
                return;
            }
            log.error("Streaming chat failed", e);
            listener.accept(new ChatStreamEvent.Error("The assistant could not answer: " + Throwables.rootMessage(e)));
        }
    }

    private ChatResponse run(ChatRequest request, @Nullable AnswerStreamSink sink, ChatCancellation cancellation) {
        long started = System.nanoTime();
        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        // Nobody is waiting: stop before queueing behind whatever else this conversation is doing.
        cancellation.abortIfCancelled(messageId);
        // The lease serialises the requests of this conversation: the next one reads a history that
        // already contains this exchange instead of a half-written one. Waiting for it ends as soon
        // as this request is cancelled, however long the one ahead still takes.
        ConversationStore.Lease lease = conversations.begin(conversationId, cancellation::isCancelled)
                .orElseThrow(() -> new ChatCancelledException(messageId, cancellation.reason()));
        try (lease) {
            return answer(request, sink, cancellation, lease, messageId, started);
        }
    }

    private ChatResponse answer(ChatRequest request, @Nullable AnswerStreamSink sink, ChatCancellation cancellation,
                                ConversationStore.Lease conversation, String messageId, long started) {
        String conversationId = conversation.conversationId();
        String question = request.message().strip();
        ChatRequest.Options options = request.optionsOrDefault();
        List<ConversationTurn> history = conversation.history();

        AnswerMode mode = options.mode() != null ? options.mode() : defaultMode;
        UserQuestion input = new UserQuestion(conversationId, messageId, question, history, options.topK(),
                options.documentIds(), mode, sink, cancellation);
        GroundedAnswer answer;
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.CONVERSATION_ID, conversationId);
             RequestContext.Scope _ = RequestContext.with(RequestContext.MESSAGE_ID, messageId)) {
            answer = AgentInvocation.create(agentPlatform, GroundedAnswer.class).invoke(input);
        }

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        ChatTimings timings = new ChatTimings(answer.retrievalMs(), Math.max(0, totalMs - answer.retrievalMs()), totalMs);
        RetrievalResult diagnostics = options.diagnostics() ? traces.find(answer.retrievalTraceId()).orElse(null) : null;

        // An abandoned run has no result to report: a half-written answer is not an answer, and it
        // must not enter the history the next question will be answered from.
        cancellation.abortIfCancelled(messageId);
        Instant now = clock.instant();
        if (!conversation.record(ConversationTurn.user(question, now),
                ConversationTurn.assistant(answer.answer(), answer.citations(), now))) {
            log.info("Conversation {} was deleted while [{}] ran; the exchange was not stored", conversationId, messageId);
        }
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
