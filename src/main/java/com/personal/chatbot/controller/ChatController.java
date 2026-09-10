package com.personal.chatbot.controller;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.ExecutionContext;
import com.personal.chatbot.observability.Outcome;
import com.personal.chatbot.observability.Cancellations;
import java.util.concurrent.atomic.AtomicBoolean;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.lifecycle.ActiveWork;
import com.personal.chatbot.service.sse.SseConnection;
import com.personal.chatbot.service.sse.SseConnections;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Chat API (docs/system-plan.md §8, D11): synchronous answers and a server-sent-events variant.
 *
 * <p>The controller owns the threads behind the streaming variant, so it is also the {@link ActiveWork}
 * that stops them (docs/concurrency-plan.md C08).
 */
@RestController
@RequestMapping("/api")
public class ChatController implements ActiveWork {

    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    /**
     * How much longer than the stream timeout the container keeps the response open. The deadline is
     * ours to enforce, because the container's is silent: it completes the response from its own
     * thread, and the client is left with a stream that simply stops. The grace period is only there
     * so that the two never race — by the time it could matter, the error event has been sent.
     */
    static final Duration STREAM_TIMEOUT_GRACE = Duration.ofSeconds(30);
    static final String TIMED_OUT = "The assistant did not finish in time and the request was stopped.";

    private final ChatService chatService;
    private final SseConnections connections;
    private final ChatObservations observations;
    /** How long a streaming request may run in total; stages do not extend it. */
    private final Duration streamTimeout;
    private final AnswerMode defaultMode;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService heartbeats;
    /** Requests still running. Its monitor makes admission atomic with the shutdown snapshot. */
    private final Set<ChatCancellation> running = ConcurrentHashMap.newKeySet();
    private boolean stopping;

    @Autowired
    public ChatController(ChatService chatService, SseConnections connections, ChatbotProperties.Chat settings,
                          ChatObservations observations) {
        this(chatService, connections, settings, observations,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-stream-", 0).factory()),
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("chat-heartbeat", 0).factory()));
    }

    ChatController(ChatService chatService, SseConnections connections, ChatbotProperties.Chat settings,
                   ChatObservations observations, ExecutorService streamExecutor, ScheduledExecutorService heartbeats) {
        this.streamExecutor = streamExecutor;
        this.heartbeats = heartbeats;
        this.chatService = chatService;
        this.connections = connections;
        this.observations = observations;
        this.streamTimeout = settings.streamTimeout();
        this.defaultMode = settings.mode();
        // Accepted runs that have not finished, whether or not a connection is still open for them.
        observations.trackActiveRuns(running::size);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        ChatCancellation cancellation = start();
        try {
            return chatService.chat(request, cancellation);
        } finally {
            running.remove(cancellation);
        }
    }

    /**
     * Same contract as {@link #chat}, delivered as SSE events {@code status}, {@code delta}, {@code final}
     * and {@code error}. The agent runs on a virtual thread and hands its events to the connection,
     * which writes them on a sender of its own. SSE comments are sent every {@link #HEARTBEAT_INTERVAL}
     * through silent phases (an agentic tool loop can run for minutes without an event); the scheduler
     * only queues them, so one unreachable client cannot delay the heartbeats of the others.
     *
     * <p>A disconnect, the stream timeout, a connection that fell too far behind and shutdown
     * all end in the same cancellation. What that stops is cooperative: the streamed model call is
     * dropped through its subscription, and the other stages stop at the next retrieval, model or tool
     * boundary. Nothing here proves that generation already handed to Ollama has stopped on its side.
     *
     * <p>The timeout is the one case of the four where somebody is still listening, so it is also the
     * one that owes an explanation: the deadline below sends an {@code error} event before it cancels.
     * A client that left gets nothing, as before — there is nobody to tell.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        ChatCancellation cancellation = start();
        ChatObservations.Stream stream = observations.startStream(answerMode(request));
        cancellation.onCancel(() -> stream.finished(Cancellations.outcomeOf(cancellation.telemetryReason())));
        SseConnection connection = null;
        List<ScheduledFuture<?>> timers = List.of();
        try {
            SseConnection opened = connections.open("chat", streamTimeout.plus(STREAM_TIMEOUT_GRACE), reason -> cancellation.cancel(switch (reason) {
                        case "stream timed out" -> ChatCancellation.Cause.TIMEOUT;
                        case "send buffer full" -> ChatCancellation.Cause.OVERFLOW;
                        case "server is shutting down" -> ChatCancellation.Cause.REJECTED;
                        case "application shutdown" -> ChatCancellation.Cause.SHUTDOWN;
                        default -> ChatCancellation.Cause.CLIENT_DISCONNECT;
                    }, reason));
            connection = opened;
            if (!opened.isOpen()) throw new RejectedExecutionException("SSE sender was refused");
            ExecutionContext context = ExecutionContext.capture();
            AtomicBoolean claimed = new AtomicBoolean();
            long interval = HEARTBEAT_INTERVAL.toMillis();
            List<ScheduledFuture<?>> scheduled = List.of(
                    heartbeats.scheduleAtFixedRate(context.wrap(opened::heartbeat), interval, interval, TimeUnit.MILLISECONDS),
                    heartbeats.schedule(context.wrap(() -> timeOut(opened, cancellation)), streamTimeout.toMillis(),
                            TimeUnit.MILLISECONDS));
            timers = scheduled;
            Future<?> work = streamExecutor.submit(context.wrap(() -> {
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    chatService.stream(request, event -> {
                        boolean queued = opened.send(event.type(), null, event);
                        if (queued && event instanceof ChatStreamEvent.Delta(String text) && !text.isEmpty()) {
                            stream.delta();
                        }
                        if (queued && event instanceof ChatStreamEvent.Final) stream.finished(Outcome.SUCCESS);
                        if (queued && event instanceof ChatStreamEvent.Error) stream.finished(Outcome.ERROR);
                    }, cancellation);
                } finally {
                    release(cancellation, scheduled, opened);
                }
            }));
            // A request cancelled before its turn on the executor never runs, so nothing else would clean up.
            cancellation.onCancel(() -> {
                if (claimed.compareAndSet(false, true)) {
                    work.cancel(false);
                    context.wrap(() -> {
                        observations.endedBeforeStart(true, answerMode(request),
                                Cancellations.outcomeOf(cancellation.telemetryReason()));
                        release(cancellation, scheduled, opened);
                    }).run();
                }
            });
            return opened.emitter();
        } catch (RuntimeException e) {
            // Shutdown between the check above and here: whatever was created is given back, so the
            // request is not left counted as running (docs/concurrency-plan.md C10).
            stream.finished(e instanceof RejectedExecutionException ? Outcome.REJECTED : Outcome.ERROR);
            release(cancellation, timers, connection);
            if (e instanceof RejectedExecutionException) {
                // Accepted and then refused a thread of its own: the run never started, but its caller
                // was told the request failed, so it is one of the accepted runs (metric-catalog §5.2).
                observations.rejectedAfterAdmission(true, answerMode(request));
                throw new ServiceStoppingException("chat");
            }
            throw e;
        }
    }

    /**
     * The server's own deadline. The event is queued before the run is cancelled, and the connection
     * is completed only once its queue has drained, so the client reads the error rather than a stream
     * that stopped. A request that has already finished has cancelled this timer.
     */
    private void timeOut(SseConnection connection, ChatCancellation cancellation) {
        if (cancellation.isCancelled()) {
            return;
        }
        ChatStreamEvent.Error event = new ChatStreamEvent.Error(TIMED_OUT);
        connection.send(event.type(), null, event);
        cancellation.cancel(ChatCancellation.Cause.TIMEOUT, "stream timed out after " + streamTimeout);
    }

    /** What the run would have answered with; the same default the chat service applies. */
    private AnswerMode answerMode(ChatRequest request) {
        AnswerMode requested = request.optionsOrDefault().mode();
        return requested != null ? requested : defaultMode;
    }

    @GetMapping("/conversations/{id}")
    public ConversationView conversation(@PathVariable String id) {
        return chatService.conversation(id);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@PathVariable String id) {
        chatService.deleteConversation(id);
    }

    @Override
    public String name() {
        return "chat";
    }

    /** Refuses new questions and cancels the ones being answered; both are idempotent. */
    @Override
    public void stopAccepting() {
        List<ChatCancellation> accepted;
        synchronized (running) {
            stopping = true;
            accepted = List.copyOf(running);
        }
        // Cancellation invokes callbacks; never run them under the admission monitor.
        accepted.forEach(cancellation -> cancellation.cancel(ChatCancellation.Cause.SHUTDOWN, "application shutdown"));
        heartbeats.shutdown();
        streamExecutor.shutdown();
    }

    /**
     * Waits for the streaming requests, which run on threads of this controller. A synchronous
     * request runs on a container thread instead: cancelling it above is what lets it end early, and
     * draining it is the server's own graceful shutdown — hence the second condition, which reports
     * such a request as still running rather than pretending the work is over.
     */
    @Override
    public boolean awaitQuiet(Duration timeout) {
        try {
            return streamExecutor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS) && running.isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Interrupts the streaming threads; a synchronous request on a container thread is out of reach. */
    @Override
    public void interruptActive() {
        streamExecutor.shutdownNow();
        heartbeats.shutdownNow();
    }

    /** Admission: from here the request is an accepted run, and is measured as one. */
    private ChatCancellation start() {
        ChatCancellation cancellation = new ChatCancellation();
        synchronized (running) {
            if (!stopping) {
                running.add(cancellation);
                return cancellation;
            }
        }
        // Counted apart from the accepted runs: a question that was never taken on did not fail one.
        observations.rejected(ChatObservations.Rejection.STOPPING);
        throw new ServiceStoppingException("chat");
    }

    /**
     * Idempotent: whichever path gets here first releases everything the request held. The timers are
     * empty when the request failed before any was scheduled.
     */
    private void release(ChatCancellation cancellation, List<ScheduledFuture<?>> timers,
                         @Nullable SseConnection connection) {
        timers.forEach(timer -> timer.cancel(false));
        running.remove(cancellation);
        if (connection != null) {
            connection.complete();
        }
    }
}
