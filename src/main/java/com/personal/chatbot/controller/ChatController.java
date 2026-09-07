package com.personal.chatbot.controller;

import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.service.chat.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Chat API (docs/system-plan.md §8, D11): synchronous answers and a server-sent-events variant. */
@RestController
@RequestMapping("/api")
public class ChatController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ChatService chatService;
    private final ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("chat-stream-", 0).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("chat-heartbeat", 0).factory());

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * Same contract as {@link #chat}, delivered as SSE events {@code status}, {@code delta}, {@code final}
     * and {@code error}. The agent runs on a virtual thread. SSE comments are sent every
     * {@link #HEARTBEAT_INTERVAL} through silent phases (an agentic tool loop can run for minutes without
     * an event); a failed heartbeat write is also how a client that went away is noticed, after which
     * the agent run is cancelled at its next model or tool boundary.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        Runnable closed = () -> open.set(false);
        emitter.onCompletion(closed);
        emitter.onTimeout(closed);
        emitter.onError(_ -> closed.run());
        long interval = HEARTBEAT_INTERVAL.toMillis();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> sendHeartbeat(emitter, open),
                interval, interval, TimeUnit.MILLISECONDS);
        streamExecutor.execute(() -> {
            try {
                chatService.stream(request, event -> send(emitter, open, event), () -> !open.get());
            } finally {
                heartbeat.cancel(false);
                if (open.get()) {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    private static void sendHeartbeat(SseEmitter emitter, AtomicBoolean open) {
        if (!open.get()) {
            return;
        }
        try {
            synchronized (emitter) { // the agent thread writes events concurrently
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Client went away (heartbeat): {}", e.toString());
            open.set(false);
        }
    }

    private static void send(SseEmitter emitter, AtomicBoolean open, ChatStreamEvent event) {
        if (!open.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Client went away during streaming: {}", e.toString());
            open.set(false);
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
    public void close() {
        heartbeats.shutdownNow();
        streamExecutor.shutdownNow();
    }
}
