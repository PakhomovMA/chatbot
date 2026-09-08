package com.personal.chatbot.controller;

import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.lifecycle.ActiveWork;
import com.personal.chatbot.service.sse.SseConnection;
import com.personal.chatbot.service.sse.SseConnections;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
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

    /** How long a streaming request may run in total; stages do not extend it. */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ChatService chatService;
    private final SseConnections connections;
    private final ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("chat-stream-", 0).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("chat-heartbeat", 0).factory());
    /** Requests still running. Its monitor makes admission atomic with the shutdown snapshot. */
    private final Set<ChatCancellation> running = ConcurrentHashMap.newKeySet();
    private boolean stopping;

    public ChatController(ChatService chatService, SseConnections connections) {
        this.chatService = chatService;
        this.connections = connections;
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
     * <p>A disconnect, the {@link #STREAM_TIMEOUT}, a connection that fell too far behind and shutdown
     * all end in the same cancellation. What that stops is cooperative: the streamed model call is
     * dropped through its subscription, and the other stages stop at the next retrieval, model or tool
     * boundary. Nothing here proves that generation already handed to Ollama has stopped on its side.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        ChatCancellation cancellation = start();
        SseConnection connection = null;
        ScheduledFuture<?> heartbeat = null;
        try {
            SseConnection opened = connections.open("chat", STREAM_TIMEOUT, cancellation::cancel);
            connection = opened;
            long interval = HEARTBEAT_INTERVAL.toMillis();
            ScheduledFuture<?> scheduled = heartbeats.scheduleAtFixedRate(opened::heartbeat, interval, interval,
                    TimeUnit.MILLISECONDS);
            heartbeat = scheduled;
            Future<?> work = streamExecutor.submit(() -> {
                try {
                    chatService.stream(request, event -> opened.send(event.type(), null, event), cancellation);
                } finally {
                    release(cancellation, scheduled, opened);
                }
            });
            // A request cancelled before its turn on the executor never runs, so nothing else would clean up.
            cancellation.onCancel(() -> {
                if (work.cancel(false)) {
                    release(cancellation, scheduled, opened);
                }
            });
            return opened.emitter();
        } catch (RuntimeException e) {
            // Shutdown between the check above and here: whatever was created is given back, so the
            // request is not left counted as running (docs/concurrency-plan.md C10).
            release(cancellation, heartbeat, connection);
            throw e instanceof RejectedExecutionException ? new ServiceStoppingException("chat") : e;
        }
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
        accepted.forEach(cancellation -> cancellation.cancel("application shutdown"));
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

    private ChatCancellation start() {
        synchronized (running) {
            if (stopping) {
                throw new ServiceStoppingException("chat");
            }
            ChatCancellation cancellation = new ChatCancellation();
            running.add(cancellation);
            return cancellation;
        }
    }

    /**
     * Idempotent: whichever path gets here first releases everything the request held. The heartbeat
     * is null when the request failed before one was scheduled.
     */
    private void release(ChatCancellation cancellation, @Nullable ScheduledFuture<?> heartbeat,
                         @Nullable SseConnection connection) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        running.remove(cancellation);
        if (connection != null) {
            connection.complete();
        }
    }
}
