package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the language model returns (structured output). Verification happens afterwards in
 * {@link com.personal.chatbot.service.chat.GroundingVerifier}; nothing here is trusted as-is.
 */
@JsonClassDescription("An answer to the user's question that uses only the numbered evidence passages")
public record GroundedAnswerDraft(
        @JsonPropertyDescription("The answer in Markdown, in the language of the question. Cite evidence inline as [n] where n is the passage number.")
        String answer,
        @JsonPropertyDescription("Numbers of every evidence passage the answer relies on")
        List<Integer> citedEvidence,
        @JsonPropertyDescription("true only if the evidence passages actually contain the information needed to answer")
        boolean evidenceSufficient,
        @JsonPropertyDescription("Aspects of the question the evidence does not cover, or null if none")
        @Nullable String unansweredAspects
) {

    public static GroundedAnswerDraft insufficient(String answer, @Nullable String unansweredAspects) {
        return new GroundedAnswerDraft(answer, List.of(), false, unansweredAspects);
    }

    public List<Integer> citedEvidenceOrEmpty() {
        return citedEvidence != null ? citedEvidence : List.of();
    }
}
