package com.personal.chatbot.service.sse;

import com.personal.chatbot.config.ChatbotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** C08: open event streams end with the application instead of holding its shutdown open. */
class SseConnectionsTest {

    private final SseConnections connections = new SseConnections(new SimpleMeterRegistry(), new ChatbotProperties.Sse(8));
    private final List<String> abandoned = new CopyOnWriteArrayList<>();

    @Test
    void stoppingEndsOpenStreamsAndCancelsTheWorkBehindThem() {
        SseConnection connection = connections.open("chat", Duration.ofMinutes(1), abandoned::add);
        assertThat(connection.isOpen()).isTrue();

        connections.stopAccepting();

        assertThat(connections.awaitQuiet(Duration.ofSeconds(5))).isTrue();
        assertThat(connection.isOpen()).isFalse();
        assertThat(abandoned).containsExactly("application shutdown");
    }

    @Test
    void aStreamOpenedAfterTheStopGetsNoSenderAndCloses() {
        connections.stopAccepting();

        SseConnection late = connections.open("chat", Duration.ofMinutes(1), abandoned::add);

        assertThat(late.isOpen()).isFalse();
        assertThat(abandoned).containsExactly("server is shutting down");
    }
}
