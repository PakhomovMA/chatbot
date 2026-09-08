package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.ConversationView;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short conversation memory (docs/system-plan.md D12): in-memory only, bounded per conversation by
 * turns, globally by conversation count and by idle time. Nothing here survives a restart by design.
 *
 * <p>One monitor guards lookup, append, TTL, eviction and delete together, so nothing can be evicted
 * between a decision and the write that depends on it. A request works through a {@link Lease}: the
 * lease serialises the requests of one conversation (other conversations are unaffected), keeps the
 * conversation it is using from being evicted, and notices a deletion that happened while the
 * request was running, so a late answer cannot bring a deleted conversation back. The monitor is
 * held inside this class's own methods only — never across the work a lease protects.
 */
public class ConversationStore {

    private final int maxTurns;
    private final int maxConversations;
    private final Duration ttl;
    private final Clock clock;

    private final Object monitor = new Object();
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    /** Coordination for conversations with requests in flight; dropped when the last one leaves. */
    private final Map<String, Turnstile> inFlight = new HashMap<>();

    private static final class Conversation {
        final Instant createdAt;
        Instant updatedAt;
        final List<ConversationTurn> turns = new ArrayList<>();

        Conversation(Instant now) {
            this.createdAt = now;
            this.updatedAt = now;
        }
    }

    /**
     * Serialises the requests of one conversation and dates the leases a deletion invalidates. A
     * {@link ReentrantLock} rather than a monitor because a lease is taken in one method and released
     * in another, which a synchronized block cannot express (docs/concurrency-plan.md C09).
     */
    private static final class Turnstile {
        final ReentrantLock lock = new ReentrantLock();
        int holders;
        long epoch;
    }

    public ConversationStore(int maxTurns, int maxConversations, Duration ttl, Clock clock) {
        this.maxTurns = maxTurns;
        this.maxConversations = maxConversations;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * Exclusive access to one conversation for the duration of a request: reading the history and
     * recording the exchange cannot interleave with another request for the same conversation, and
     * the conversation stays out of reach of eviction until the lease is closed. Always close it in
     * a try-with-resources — the limits are re-applied when the last request lets go.
     */
    public final class Lease implements AutoCloseable {

        private final String conversationId;
        private final Turnstile turnstile;
        private final long epoch;
        private boolean closed;

        private Lease(String conversationId, Turnstile turnstile, long epoch) {
            this.conversationId = conversationId;
            this.turnstile = turnstile;
            this.epoch = epoch;
        }

        public String conversationId() {
            return conversationId;
        }

        /** Turns oldest first, at most {@code maxTurns}; empty for a conversation without history. */
        public List<ConversationTurn> history() {
            synchronized (monitor) {
                Conversation conversation = conversations.get(conversationId);
                return conversation == null ? List.of() : List.copyOf(conversation.turns);
            }
        }

        /**
         * Stores a question and its answer as one step, so concurrent requests cannot interleave
         * halves of an exchange.
         *
         * @return false if the conversation was deleted while this request was running; the exchange
         * is then dropped rather than recreating what the user asked to remove
         */
        public boolean record(ConversationTurn question, ConversationTurn answer) {
            synchronized (monitor) {
                if (turnstile.epoch != epoch) {
                    return false;
                }
                Instant now = clock.instant();
                Conversation conversation = conversations.computeIfAbsent(conversationId, _ -> new Conversation(now));
                conversation.turns.add(question);
                conversation.turns.add(answer);
                while (conversation.turns.size() > maxTurns) {
                    conversation.turns.removeFirst();
                }
                conversation.updatedAt = now;
                applyLimits(now);
                return true;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            synchronized (monitor) {
                if (--turnstile.holders == 0) {
                    inFlight.remove(conversationId, turnstile);
                    applyLimits(clock.instant());
                }
            }
            turnstile.lock.unlock();
        }
    }

    /**
     * Claims a conversation for one request, waiting for any request already working on it. Blocks
     * outside the store monitor, so other conversations keep moving.
     */
    public Lease begin(String conversationId) {
        Turnstile turnstile;
        synchronized (monitor) {
            evictExpired(clock.instant());
            turnstile = inFlight.computeIfAbsent(conversationId, _ -> new Turnstile());
            turnstile.holders++;
        }
        turnstile.lock.lock();
        synchronized (monitor) {
            // Read after locking: a deletion while we waited concerns the previous request, not this one.
            return new Lease(conversationId, turnstile, turnstile.epoch);
        }
    }

    public Optional<ConversationView> find(String conversationId) {
        synchronized (monitor) {
            Conversation conversation = conversations.get(conversationId);
            return conversation == null ? Optional.empty()
                    : Optional.of(new ConversationView(conversationId, List.copyOf(conversation.turns),
                    conversation.createdAt, conversation.updatedAt));
        }
    }

    /**
     * Forgets a conversation and invalidates the requests still running on it, so their answers are
     * not written back.
     *
     * @return false if there was neither stored history nor a request in flight
     */
    public boolean delete(String conversationId) {
        synchronized (monitor) {
            boolean removed = conversations.remove(conversationId) != null;
            Turnstile turnstile = inFlight.get(conversationId);
            if (turnstile != null) {
                turnstile.epoch++;
            }
            return removed || turnstile != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return conversations.size();
        }
    }

    /** Must be called under the monitor. */
    private void applyLimits(Instant now) {
        evictExpired(now);
        evictOverflow();
    }

    /** Must be called under the monitor. */
    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(ttl);
        conversations.entrySet().removeIf(e -> !inFlight.containsKey(e.getKey()) && e.getValue().updatedAt.isBefore(cutoff));
    }

    /** Must be called under the monitor. */
    private void evictOverflow() {
        while (conversations.size() > maxConversations) {
            String oldest = conversations.entrySet().stream()
                    .filter(e -> !inFlight.containsKey(e.getKey()))
                    .min(Comparator.comparing(e -> e.getValue().updatedAt))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return; // every conversation is in use; the limit is re-applied as their leases close
            }
            conversations.remove(oldest);
        }
    }
}
