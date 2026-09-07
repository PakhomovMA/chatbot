package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamedDraftParserTest {

    @Test
    void collectsMarkersFromText() {
        GroundedAnswerDraft draft = StreamedDraftParser.parse("Restart with `systemctl` [2]. Then verify [1] and [2].\n");
        assertThat(draft.answer()).isEqualTo("Restart with `systemctl` [2]. Then verify [1] and [2].");
        assertThat(draft.citedEvidence()).containsExactly(1, 2);
        assertThat(draft.evidenceSufficient()).isTrue();
        assertThat(draft.unansweredAspects()).isNull();
    }

    @Test
    void trailingInsufficientLineMarksTheDraft() {
        GroundedAnswerDraft draft = StreamedDraftParser.parse(
                "The runbook covers restarts [1] but not the port.\n\n`INSUFFICIENT: the listening port`\n");
        assertThat(draft.answer()).isEqualTo("The runbook covers restarts [1] but not the port.");
        assertThat(draft.evidenceSufficient()).isFalse();
        assertThat(draft.unansweredAspects()).isEqualTo("the listening port");
        assertThat(draft.citedEvidence()).containsExactly(1);

        GroundedAnswerDraft bare = StreamedDraftParser.parse("Nothing here.\nINSUFFICIENT:");
        assertThat(bare.evidenceSufficient()).isFalse();
        assertThat(bare.unansweredAspects()).isEqualTo("not covered by the evidence");
    }
}
