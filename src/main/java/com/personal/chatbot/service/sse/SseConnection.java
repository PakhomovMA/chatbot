package com.personal.chatbot.service.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One server-sent-events connection with a sender of its own (docs/concurrency-plan.md C06).
 * Producers — the ingestion worker, the agent thread, the heartbeat scheduler — hand an event over
 * and move on; the network write happens on this connection's sender, so a client that reads slowly
 * holds up nobody but itself.
 *
 * <p>The buffer is bounded. A connection that fills it is too far behind to catch up, so it is
 * dropped and the work behind it cancelled — better than quietly losing the middle of an answer.
 * Events of one connection keep their order, and the emitter is completed only after the queue has
 * drained, so a terminal event is never cut off.
 */
public final class SseConnection {

    private static final Logger log = LoggerFactory.getLogger(SseConnection.class);
    private static final String HEARTBEAT = "keep-alive";

    /** What a connection can be asked to deliver, in order. */
    private sealed interface Item {
        record Event(String name, @Nullable String id, Object data) implements Item {
        }

        record Comment(String text) implements Item {
        }

        /** No more events; complete the response once everything before it has gone out. */
        record End() implements Item {
        }
    }

    private final String stream;
    private final SseEmitter emitter;
    private final BlockingQueue<Item> queue;
    private final Consumer<String> onAbandoned;
    private final Runnable onFinished;
    private final Counter overflows;
    private final Timer sends;

    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean heartbeatPending = new AtomicBoolean();
    private volatile @Nullable Future<?> sender;

    SseConnection(String stream, SseEmitter emitter, int bufferSize, Consumer<String> onAbandoned, Runnable onFinished,
                  Counter overflows, Timer sends) {
        this.stream = stream;
        this.emitter = emitter;
        this.queue = new ArrayBlockingQueue<>(bufferSize);
        this.onAbandoned = onAbandoned;
        this.onFinished = onFinished;
        this.overflows = overflows;
        this.sends = sends;
        emitter.onCompletion(() -> abandon("client closed the stream"));
        emitter.onTimeout(() -> abandon("stream timed out"));
        emitter.onError(e -> abandon("stream failed: " + e));
    }

    /** Hands the connection to its sender; called once, before anyone else can see it. */
    SseConnection start(ExecutorService senders) {
        try {
            sender = senders.submit(this::deliverUntilDone);
        } catch (RejectedExecutionException e) {
            end("server is shutting down");
            completeQuietly();
            onFinished.run();
        }
        return this;
    }

    /** The emitter to return from the controller; never write to it directly. */
    public SseEmitter emitter() {
        return emitter;
    }

    public boolean isOpen() {
        return !finished.get();
    }

    /** Queues an event. Returns without waiting, whatever the client is doing. */
    public void send(String name, @Nullable String id, Object data) {
        offer(new Item.Event(name, id, data));
    }

    public void comment(String text) {
        offer(new Item.Comment(text));
    }

    /**
     * Queues a keep-alive unless one is still waiting: a client that is behind should not be sent a
     * backlog of heartbeats on top of the events it has not read yet.
     */
    public void heartbeat() {
        if (heartbeatPending.compareAndSet(false, true)) {
            offer(new Item.Comment(HEARTBEAT));
        }
    }

    /** Ends the stream once everything already queued has been delivered. */
    public void complete() {
        offer(new Item.End());
    }

    /**
     * Drops the connection now: the work behind it is cancelled and the queued events are discarded.
     * Never touches the emitter from the caller's thread — the sender owns it, and it may be stuck
     * writing to a socket nobody is reading.
     */
    public void abandon(String reason) {
        if (end(reason) && sender != null) {
            sender.cancel(true);
        }
    }

    /**
     * Ends the connection once, whoever notices first: the producer that overflowed the buffer, the
     * container reporting a disconnect, or the sender finding the socket gone.
     *
     * @return true if this call is the one that ended it
     */
    private boolean end(String reason) {
        if (!finished.compareAndSet(false, true)) {
            return false;
        }
        log.debug("Dropping {} SSE connection: {}", stream, reason);
        queue.clear();
        onAbandoned.accept(reason);
        return true;
    }

    private void offer(Item item) {
        if (finished.get()) {
            return;
        }
        if (!queue.offer(item)) {
            overflows.increment();
            log.warn("{} SSE connection fell behind by more than {} events; dropping it", stream, queue.size());
            abandon("send buffer full");
        }
    }

    private void deliverUntilDone() {
        try {
            while (true) {
                Item item = queue.take();
                if (item instanceof Item.End) {
                    return;
                }
                if (!deliver(item)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finished.set(true);
            completeQuietly();
            onFinished.run();
        }
    }

    /** @return false once the client is gone and there is no point in trying again */
    private boolean deliver(Item item) {
        long started = System.nanoTime();
        try {
            switch (item) {
                case Item.Event event -> emitter.send(builderFor(event));
                case Item.Comment comment -> {
                    emitter.send(SseEmitter.event().comment(comment.text()));
                    heartbeatPending.set(false);
                }
                case Item.End _ -> throw new IllegalStateException("End is handled by the sender loop");
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Client of the {} stream went away: {}", stream, e.toString());
            end("client went away"); // no cancel(true) here: this is the sender's own thread
            return false;
        } finally {
            sends.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private static SseEmitter.SseEventBuilder builderFor(Item.Event event) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event.name());
        return (event.id() != null ? builder.id(event.id()) : builder).data(event.data(), MediaType.APPLICATION_JSON);
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.trace("Emitter of the {} stream was already closed: {}", stream, e.toString());
        }
    }
}
