package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.service.retrieval.EvidenceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAttributorTest {

    @Test
    void creditsChunksWhoseDistinctiveTokensTheAnswerReuses() {
        var restart = AgenticDraftMapperTest.chunk("d:1:0", "Restart one replica at a time: `kubectl rollout restart deployment/payments-api -n payments`. Watch the rollout with `kubectl rollout status`.");
        var secrets = AgenticDraftMapperTest.chunk("d:1:1", "Secrets are stored in HashiCorp Vault under secret/<service> and rotated every ninety days by vault-rotator.");
        List<Integer> credited = EvidenceAttributor.attribute(
                "Run `kubectl rollout restart deployment/payments-api -n payments` and watch `kubectl rollout status`.",
                AgenticDraftMapperTest.collectorWith(restart, secrets).chunks());
        assertThat(credited).containsExactly(1);
        assertThat(EvidenceAttributor.attribute("Bananas ripen after harvest.", AgenticDraftMapperTest.collectorWith(restart, secrets).chunks())).isEmpty();
    }

    @Test
    void mapperFallsBackToAttributionWhenTheModelForgotReferences() {
        var restart = AgenticDraftMapperTest.chunk("d:1:0", "Restart one replica at a time: `kubectl rollout restart deployment/payments-api -n payments`.");
        EvidenceCollector collector = AgenticDraftMapperTest.collectorWith(restart);
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(
                new AgenticDraft("Restart with `kubectl rollout restart deployment/payments-api -n payments`.", List.of(), true, null), collector);
        assertThat(numbered.answer()).endsWith(" [1]");
        assertThat(numbered.citedEvidence()).containsExactly(1);

        GroundedAnswerDraft insufficient = AgenticDraftMapper.toNumbered(
                new AgenticDraft("Restart with `kubectl rollout restart deployment/payments-api -n payments`.", List.of(), false, "the port"), collector);
        assertThat(insufficient.citedEvidence()).isEmpty();
        assertThat(insufficient.answer()).doesNotContain("[1]");
    }
}
