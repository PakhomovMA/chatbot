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
import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.ChatRun;
import com.personal.chatbot.observability.RequestContext;
import com.personal.chatbot.service.cache.CachedAnswer;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.utils.Throwables;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Chat entry point (docs/system-plan.md §2.2): resolves the conversation, runs the knowledge assistant
 * agent through Embabel and maps its goal artifact to the API response. Frontend-facing types only
 * cross this boundary (INV-10). {@link #stream} runs the same agent and reports progress through a
 * listener; both paths end in an identical {@link ChatResponse}. A first question asked before at the
 * same knowledge-base revision is answered from the answer cache instead (docs/cache-plan.md, INV-12).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentPlatform agentPlatform;
    private final ConversationStore conversations;
    private final RetrievalTraceStore traces;
    private final ChatAnswerCache answerCache;
    private final ChatObservations observations;
    private final Clock clock;
    private final AnswerMode defaultMode;

    public ChatService(AgentPlatform agentPlatform, ConversationStore conversations, RetrievalTraceStore traces,
                       ChatAnswerCache answerCache, ChatObservations observations, Clock clock,
                       ChatbotProperties.Chat settings) {
        this.agentPlatform = agentPlatform;
        this.conversations = conversations;
        this.traces = traces;
        this.answerCache = answerCache;
        this.observations = observations;
        this.clock = clock;
        this.defaultMode = settings.mode();
    }

    public ChatResponse chat(ChatRequest request) {
        return chat(request, ChatCancellation.none());
    }

    public ChatResponse chat(ChatRequest request, ChatCancellation cancellation) {
        return run(request, null, cancellation, _ -> { });
    }

    /**
     * Answers with progress events: {@code status} per stage, {@code delta} per text fragment and a
     * {@code final} event carrying the verified response (or {@code error}). Blocks until finished. An
     * answer from the cache has no stages and no fragments: its stream is the {@code final} event alone.
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
            run(request, sink, cancellation, response -> listener.accept(new ChatStreamEvent.Final(response)));
        } catch (Exception e) { // Embabel (Kotlin) can surface checked exceptions such as ExecutionException
            if (ChatCancelledException.isCancellation(e) || cancellation.isCancelled()) {
                log.info("Streaming chat abandoned ({}): {}", cancellation.reason(), Throwables.rootMessage(e));
                return;
            }
            log.error("Streaming chat failed", e);
            listener.accept(new ChatStreamEvent.Error("The assistant could not answer: " + Throwables.rootMessage(e)));
        }
    }

    private ChatResponse run(ChatRequest request, @Nullable AnswerStreamSink sink, ChatCancellation cancellation,
                             Consumer<ChatResponse> completed) {
        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        ChatRequest.Options options = request.optionsOrDefault();
        AnswerMode mode = options.mode() != null ? options.mode() : defaultMode;
        // The run is measured from here, whatever it ends in; the wait below is measured inside it, so
        // that queueing behind the previous question is never read as time spent in the model. The
        // conversation and the message are named for the whole of it, not only around the agent: the
        // lease wait belongs to this exchange too, in the log and on its span (docs/observability-plan.md §7.2).
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.CONVERSATION_ID, conversationId);
             RequestContext.Scope _ = RequestContext.with(RequestContext.MESSAGE_ID, messageId);
             ChatRun observed = observations.startRun(sink != null, mode)) {
            try {
                // Nobody is waiting: stop before queueing behind whatever else this conversation is doing.
                cancellation.abortIfCancelled(messageId);
                // The lease serialises the requests of this conversation: the next one reads a history that
                // already contains this exchange instead of a half-written one. Waiting for it ends as soon
                // as this request is cancelled, however long the one ahead still takes.
                ConversationStore.Lease lease = observed
                        .awaitConversation(() -> conversations.begin(conversationId, cancellation::isCancelled),
                                cancellation::telemetryReason)
                        .orElseThrow(() -> new ChatCancelledException(messageId, cancellation.reason()));
                try (lease) {
                    ChatResponse response = answer(request, mode, sink, cancellation, lease, messageId, observed);
                    completed.accept(response);
                    return response;
                }
            } catch (Exception e) { // Embabel (Kotlin) can surface checked exceptions from here as well
                observed.failed(e, cancellation.telemetryReason());
                throw e;
            }
        }
    }

    private ChatResponse answer(ChatRequest request, AnswerMode mode, @Nullable AnswerStreamSink sink,
                                ChatCancellation cancellation, ConversationStore.Lease conversation,
                                String messageId, ChatRun observed) {
        String conversationId = conversation.conversationId();
        String question = request.message().strip();
        ChatRequest.Options options = request.optionsOrDefault();
        List<ConversationTurn> history = conversation.history();

        // Asked once the history is known, which decides whether the question may come from the cache
        // at all, and before anything is spent on the question (docs/cache-plan.md §1).
        ChatAnswerCache.Decision cached = answerCache.lookup(question, mode, options, history);
        if (cached instanceof ChatAnswerCache.Hit(CachedAnswer entry)) {
            return fromCache(entry, question, options, cancellation, conversation, messageId, observed);
        }

        UserQuestion input = new UserQuestion(conversationId, messageId, question, history, options.topK(),
                options.documentIds(), mode, sink, cancellation);
        GroundedAnswer answer = AgentInvocation.create(agentPlatform, GroundedAnswer.class).invoke(input);

        // Where the v1 diagnostics stop counting, before everything below them.
        observed.agentFinished();
        observed.retrievalTrace(answer.retrievalTraceId());
        ChatTimings timings = observed.timings(answer.retrievalMs());
        // This run's own trace first: the shared ring is short, and a busy period must not decide
        // whether an answer can report what it retrieved (docs/observability-plan.md §4.2), nor whether
        // it can be cached together with the retrieval it was verified against.
        RetrievalResult retrieval = observed.diagnostics().find(answer.retrievalTraceId())
                .or(() -> traces.find(answer.retrievalTraceId())).orElse(null);

        // An abandoned run has no result to report: a half-written answer is not an answer, and it
        // must not enter the history the next question will be answered from, nor the cache.
        cancellation.abortIfCancelled(messageId);
        if (cached instanceof ChatAnswerCache.Miss miss) {
            answerCache.store(miss, question, answer, retrieval);
        }
        input.derivations().commit();
        recordExchange(conversation, messageId, question, answer.answer(), answer.citations());
        observed.succeeded(answer.grounding());
        log.info("Chat [{}] {} in {} ms ({} citations, retrieval {} ms{})", messageId, answer.grounding(),
                timings.totalMs(), answer.citations().size(), answer.retrievalMs(), sink != null ? ", streamed" : "");
        return new ChatResponse(conversationId, messageId, answer.answer(), answer.grounding(), answer.citations(),
                answer.notes(), timings, answer.retrievalTraceId(), options.diagnostics() ? retrieval : null);
    }

    /**
     * Answers from the cache (docs/cache-plan.md §3.4). To the conversation and to the caller this is an
     * answer like any other — a message of its own, recorded in the history, whose trace id leads to the
     * retrieval it was verified against. Only its timings and its span tell it apart.
     */
    private ChatResponse fromCache(CachedAnswer cached, String question, ChatRequest.Options options,
                                   ChatCancellation cancellation, ConversationStore.Lease conversation,
                                   String messageId, ChatRun observed) {
        ChatTimings timings = observed.servedFromCache(CacheObservations.Layer.ANSWER);
        RetrievalResult retrieval = cached.retrieval();
        observed.retrievalTrace(retrieval.traceId());
        // Back into the ring under its own id and time: whatever pushed it out since, the trace id of
        // this response has to lead to it.
        traces.record(retrieval);

        cancellation.abortIfCancelled(messageId);
        recordExchange(conversation, messageId, question, cached.answer(), cached.citations());
        observed.succeeded(cached.grounding());
        log.info("Chat [{}] {} served from the answer cache in {} ms ({} citations, age {} s, revision {})",
                messageId, cached.grounding(), timings.totalMs(), cached.citations().size(),
                Duration.between(cached.createdAt(), clock.instant()).toSeconds(), cached.revision());
        return new ChatResponse(conversation.conversationId(), messageId, cached.answer(), cached.grounding(),
                cached.citations(), cached.notes(), timings, retrieval.traceId(),
                options.diagnostics() ? retrieval : null);
    }

    private void recordExchange(ConversationStore.Lease conversation, String messageId, String question,
                                String answer, List<Citation> citations) {
        Instant now = clock.instant();
        if (!conversation.record(ConversationTurn.user(question, now), ConversationTurn.assistant(answer, citations, now))) {
            log.info("Conversation {} was deleted while [{}] ran; the exchange was not stored",
                    conversation.conversationId(), messageId);
        }
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
