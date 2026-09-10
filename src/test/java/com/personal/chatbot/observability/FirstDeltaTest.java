package com.personal.chatbot.observability;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class FirstDeltaTest {
    @Test
    void firstDeltaIsRecordedOnceAndNoDeltaCreatesNoArtificialZero() {
        var observed = TestObservations.create();
        var first = observed.chatObservations().startStream(AnswerMode.DETERMINISTIC);
        var noDelta = observed.chatObservations().startStream(AnswerMode.AGENTIC);
        observed.advance(Duration.ofSeconds(3));
        assertThat(observed.meters().find("chatbot.sse.first.delta").timers()).isEmpty();
        first.delta();
        observed.advance(Duration.ofSeconds(7));
        first.delta();
        var timer = observed.meters().get("chatbot.sse.first.delta").tag("answer.mode", "deterministic").timer();
        first.finished(Outcome.SUCCESS);
        first.finished(Outcome.CANCELLED);
        noDelta.finished(Outcome.SUCCESS);
        assertThat(observed.meters().get("chatbot.sse.completed").tag("outcome", "no_delta").counter().count()).isEqualTo(1);
        assertThat(observed.meters().get("chatbot.sse.completed").tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(observed.meters().find("chatbot.sse.completed").tag("outcome", "cancelled").counter()).isNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(observed.meters().find("chatbot.sse.first.delta").tag("answer.mode", "agentic").timer()).isNull();
    }
}
