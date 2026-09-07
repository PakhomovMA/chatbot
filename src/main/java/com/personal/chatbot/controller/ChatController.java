package com.personal.chatbot.controller;

import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.sse.SseConnection;
import com.personal.chatbot.service.sse.SseConnections;
import jakarta.validation.Valid;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Chat API (docs/system-plan.md §8, D11): synchronous answers and a server-sent-events variant. */
@RestController
@RequestMapping("/api")
public class ChatController implements AutoCloseable {

    /** How long a streaming request may run in total; stages do not extend it. */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ChatService chatService;
    private final SseConnections connections;
    private final ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("chat-stream-", 0).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("chat-heartbeat", 0).factory());
    /** Requests still running, so shutdown ends them instead of waiting for them. */
    private final Set<ChatCancellation> running = ConcurrentHashMap.newKeySet();

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
        SseConnection connection = connections.open("chat", STREAM_TIMEOUT, cancellation::cancel);
        long interval = HEARTBEAT_INTERVAL.toMillis();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(connection::heartbeat, interval, interval,
                TimeUnit.MILLISECONDS);
        Future<?> work = streamExecutor.submit(() -> {
            try {
                chatService.stream(request, event -> connection.send(event.type(), null, event), cancellation);
            } finally {
                release(cancellation, heartbeat, connection);
            }
        });
        // A request cancelled before its turn on the executor never runs, so nothing else would clean up.
        cancellation.onCancel(() -> {
            if (work.cancel(false)) {
                release(cancellation, heartbeat, connection);
            }
        });
        return connection.emitter();
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
    public void close() {
        running.forEach(cancellation -> cancellation.cancel("application shutdown"));
        heartbeats.shutdownNow();
        streamExecutor.shutdownNow();
    }

    private ChatCancellation start() {
        ChatCancellation cancellation = new ChatCancellation();
        running.add(cancellation);
        return cancellation;
    }

    /** Idempotent: whichever of the two paths gets here first releases everything the request held. */
    private void release(ChatCancellation cancellation, ScheduledFuture<?> heartbeat, SseConnection connection) {
        heartbeat.cancel(false);
        running.remove(cancellation);
        connection.complete();
    }
}
