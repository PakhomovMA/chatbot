package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationStoreTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    /** One exchange through a lease, the way a chat request does it. */
    private static void exchange(ConversationStore store, String conversationId, String question, String answer) {
        try (ConversationStore.Lease lease = store.begin(conversationId)) {
            lease.record(ConversationTurn.user(question, Instant.now()), ConversationTurn.assistant(answer, List.of(), Instant.now()));
        }
    }

    @Test
    void keepsLastTurnsPerConversation() {
        ConversationStore store = new ConversationStore(3, 10, Duration.ofHours(1), clock);
        for (int i = 1; i <= 3; i++) {
            exchange(store, "c1", "q" + i, "a" + i);
        }

        try (ConversationStore.Lease lease = store.begin("c1")) {
            assertThat(lease.history()).extracting(ConversationTurn::content).containsExactly("a2", "q3", "a3");
        }
        try (ConversationStore.Lease lease = store.begin("missing")) {
            assertThat(lease.history()).isEmpty();
        }
        assertThat(store.find("c1")).isPresent();
        assertThat(store.delete("c1")).isTrue();
        assertThat(store.delete("c1")).isFalse();
    }

    @Test
    void expiresIdleConversationsAndBoundsTheirNumber() {
        ConversationStore store = new ConversationStore(10, 2, Duration.ofMinutes(30), clock);
        exchange(store, "old", "a", "a!");
        now.set(now.get().plus(Duration.ofMinutes(31)));
        exchange(store, "fresh", "b", "b!");
        assertThat(store.find("old")).isEmpty();

        exchange(store, "second", "c", "c!");
        now.set(now.get().plusSeconds(1));
        exchange(store, "third", "d", "d!");
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("fresh")).isEmpty();
        assertThat(store.find("third")).isPresent();
    }

    @Test
    void aConversationInUseIsNotEvictedAndTheLimitIsAppliedWhenItIsReleased() {
        ConversationStore store = new ConversationStore(10, 1, Duration.ofMinutes(30), clock);
        exchange(store, "in-use", "q", "a");

        try (ConversationStore.Lease held = store.begin("in-use")) {
            now.set(now.get().plus(Duration.ofHours(2)));
            exchange(store, "other", "q", "a");
            assertThat(store.find("in-use")).isPresent();
            assertThat(held.history()).isNotEmpty();
        }

        // Released: the idle conversation is now over both its TTL and the size limit.
        exchange(store, "later", "q", "a");
        assertThat(store.find("in-use")).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void exchangesOfOneConversationDoNotInterleave() throws Exception {
        ConversationStore store = new ConversationStore(100, 10, Duration.ofHours(1), clock);
        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> historySizes = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            threads.add(Thread.ofPlatform().start(() -> {
                awaitLatch(start);
                try (ConversationStore.Lease lease = store.begin("c1")) {
                    historySizes.add(lease.history().size());
                    Thread.yield();
                    lease.record(ConversationTurn.user("q" + n, Instant.now()),
                            ConversationTurn.assistant("a" + n, List.of(), Instant.now()));
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(20));
        }

        // Every request saw whole exchanges, and each one saw more than the request before it.
        assertThat(historySizes).hasSize(writers).allMatch(size -> size % 2 == 0)
                .containsExactlyInAnyOrder(0, 2, 4, 6);
        List<ConversationTurn> turns = store.find("c1").orElseThrow().messages();
        assertThat(turns).hasSize(2 * writers);
        for (int i = 0; i < turns.size(); i += 2) {
            assertThat(turns.get(i).role()).isEqualTo(ConversationTurn.Role.USER);
            assertThat(turns.get(i + 1).role()).isEqualTo(ConversationTurn.Role.ASSISTANT);
            assertThat(turns.get(i + 1).content()).isEqualTo("a" + turns.get(i).content().substring(1));
        }
    }

    @Test
    void anAnswerThatArrivesAfterADeleteDoesNotBringTheConversationBack() {
        ConversationStore store = new ConversationStore(10, 10, Duration.ofHours(1), clock);
        exchange(store, "c1", "first", "answer");

        try (ConversationStore.Lease running = store.begin("c1")) {
            assertThat(store.delete("c1")).isTrue();
            assertThat(running.record(ConversationTurn.user("late", Instant.now()),
                    ConversationTurn.assistant("late answer", List.of(), Instant.now()))).isFalse();
        }

        assertThat(store.find("c1")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void aDeleteOnlyInvalidatesTheRequestsThatWereAlreadyRunning() {
        ConversationStore store = new ConversationStore(10, 10, Duration.ofHours(1), clock);
        exchange(store, "c1", "first", "answer");
        store.delete("c1");

        exchange(store, "c1", "after", "answer again");

        assertThat(store.find("c1")).isPresent();
        assertThat(store.find("c1").orElseThrow().messages()).extracting(ConversationTurn::content)
                .containsExactly("after", "answer again");
    }

    /**
     * C10: a request that registered before the deletion is one of the requests the deletion reports
     * as affected, even if it was still queueing behind another one when it happened.
     */
    @Test
    void aRequestThatWasWaitingWhenTheConversationWasDeletedDoesNotBringItBack() throws Exception {
        ConversationStore store = new ConversationStore(10, 10, Duration.ofHours(1), clock);
        exchange(store, "c1", "first", "answer");
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean recorded = new AtomicBoolean(true);

        try (ConversationStore.Lease running = store.begin("c1")) {
            Thread queued = Thread.ofPlatform().start(() -> {
                ConversationStore.Lease lease = store.begin("c1", () -> {
                    waiting.countDown(); // polled only once the turnstile is registered and held by the other request
                    return false;
                }).orElseThrow();
                try (lease) {
                    recorded.set(lease.record(ConversationTurn.user("late", Instant.now()),
                            ConversationTurn.assistant("late answer", List.of(), Instant.now())));
                }
            });
            assertThat(waiting.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(store.delete("c1")).as("the request in flight is reported as invalidated").isTrue();
            running.close();
            queued.join(Duration.ofSeconds(20));
            assertThat(queued.isAlive()).isFalse();
        }

        assertThat(recorded).isFalse();
        assertThat(store.find("c1")).isEmpty();
        assertThat(store.size()).isZero();
    }

    /** A caller that is gone stops waiting for the request ahead of it, and leaves nothing behind. */
    @Test
    void aWaitingRequestGivesUpOnceItsCallerIsGone() throws Exception {
        ConversationStore store = new ConversationStore(10, 1, Duration.ofMinutes(30), clock);
        exchange(store, "c1", "q", "a");
        AtomicBoolean gone = new AtomicBoolean();
        CountDownLatch polled = new CountDownLatch(1);
        AtomicReference<Object> claimed = new AtomicReference<>();

        try (ConversationStore.Lease running = store.begin("c1")) {
            Thread queued = Thread.ofPlatform().start(() -> claimed.set(store.begin("c1", () -> {
                polled.countDown();
                return gone.get();
            })));
            assertThat(polled.await(20, TimeUnit.SECONDS)).isTrue();
            gone.set(true);

            queued.join(Duration.ofSeconds(20));
            assertThat(queued.isAlive()).as("waiting must end with the caller, not with the request ahead").isFalse();
            assertThat(claimed.get()).isEqualTo(java.util.Optional.empty());
        }

        // Nothing was left registered: the conversation is evictable again.
        now.set(now.get().plus(Duration.ofHours(2)));
        exchange(store, "other", "q", "a");
        assertThat(store.find("c1")).isEmpty();
    }

    /** Shutdown interrupts the threads it could not stop; a wait for a turnstile has to answer that. */
    @Test
    void anInterruptEndsTheWaitAndKeepsTheFlag() throws Exception {
        ConversationStore store = new ConversationStore(10, 10, Duration.ofHours(1), clock);
        AtomicReference<Object> claimed = new AtomicReference<>();
        AtomicBoolean interruptedAfterwards = new AtomicBoolean();
        CountDownLatch polled = new CountDownLatch(1);

        try (ConversationStore.Lease running = store.begin("c1")) {
            Thread queued = Thread.ofPlatform().start(() -> {
                claimed.set(store.begin("c1", () -> {
                    polled.countDown();
                    return false;
                }));
                interruptedAfterwards.set(Thread.currentThread().isInterrupted());
            });
            assertThat(polled.await(20, TimeUnit.SECONDS)).isTrue();
            queued.interrupt();

            queued.join(Duration.ofSeconds(20));
            assertThat(queued.isAlive()).isFalse();
            assertThat(claimed.get()).isEqualTo(java.util.Optional.empty());
            assertThat(interruptedAfterwards).isTrue();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the start signal");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
