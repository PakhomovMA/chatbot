package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationStoreTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void keepsLastTurnsPerConversation() {
        ConversationStore store = new ConversationStore(3, 10, Duration.ofHours(1), clock);
        for (int i = 1; i <= 5; i++) {
            store.append("c1", ConversationTurn.user("m" + i, now.get()));
        }
        assertThat(store.history("c1")).extracting(ConversationTurn::content).containsExactly("m3", "m4", "m5");
        assertThat(store.find("c1")).isPresent();
        assertThat(store.history("missing")).isEmpty();
        assertThat(store.delete("c1")).isTrue();
        assertThat(store.delete("c1")).isFalse();
    }

    @Test
    void expiresIdleConversationsAndBoundsTheirNumber() {
        ConversationStore store = new ConversationStore(10, 2, Duration.ofMinutes(30), clock);
        store.append("old", ConversationTurn.user("a", now.get()));
        now.set(now.get().plus(Duration.ofMinutes(31)));
        store.append("fresh", ConversationTurn.user("b", now.get()));
        assertThat(store.find("old")).isEmpty();

        store.append("second", ConversationTurn.user("c", now.get()));
        now.set(now.get().plusSeconds(1));
        store.append("third", ConversationTurn.assistant("d", List.of(), now.get()));
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("fresh")).isEmpty();
        assertThat(store.find("third")).isPresent();
    }
}
