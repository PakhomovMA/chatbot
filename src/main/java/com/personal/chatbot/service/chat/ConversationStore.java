package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.ConversationView;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short conversation memory (docs/system-plan.md D12): in-memory only, bounded per conversation by
 * turns, globally by conversation count and by idle time. Nothing here survives a restart by design.
 */
public class ConversationStore {

    private final int maxTurns;
    private final int maxConversations;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    private static final class Conversation {
        final Instant createdAt;
        Instant updatedAt;
        final List<ConversationTurn> turns = new ArrayList<>();

        Conversation(Instant now) {
            this.createdAt = now;
            this.updatedAt = now;
        }
    }

    public ConversationStore(int maxTurns, int maxConversations, Duration ttl, Clock clock) {
        this.maxTurns = maxTurns;
        this.maxConversations = maxConversations;
        this.ttl = ttl;
        this.clock = clock;
    }

    public void append(String conversationId, ConversationTurn turn) {
        Instant now = clock.instant();
        evictExpired(now);
        Conversation conversation = conversations.computeIfAbsent(conversationId, _ -> new Conversation(now));
        synchronized (conversation) {
            conversation.turns.add(turn);
            while (conversation.turns.size() > maxTurns) {
                conversation.turns.removeFirst();
            }
            conversation.updatedAt = now;
        }
        evictOverflow();
    }

    /** Turns oldest first, at most {@code maxTurns}. Empty for unknown conversations. */
    public List<ConversationTurn> history(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            return List.copyOf(conversation.turns);
        }
    }

    public Optional<ConversationView> find(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return Optional.empty();
        }
        synchronized (conversation) {
            return Optional.of(new ConversationView(conversationId, List.copyOf(conversation.turns),
                    conversation.createdAt, conversation.updatedAt));
        }
    }

    public boolean delete(String conversationId) {
        return conversations.remove(conversationId) != null;
    }

    public int size() {
        return conversations.size();
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(ttl);
        conversations.entrySet().removeIf(e -> e.getValue().updatedAt.isBefore(cutoff));
    }

    private void evictOverflow() {
        while (conversations.size() > maxConversations) {
            conversations.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().updatedAt))
                    .ifPresent(oldest -> conversations.remove(oldest.getKey()));
        }
    }
}
