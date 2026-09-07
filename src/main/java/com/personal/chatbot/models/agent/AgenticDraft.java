package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Structured output of the agentic research action (Phase 9c). The model cites the {@code chunkId}s
 * it saw in tool results; they are mapped to numbered evidence and verified afterwards.
 */
@JsonClassDescription("An answer to the user's question based only on passages found with the knowledge-base tools")
public record AgenticDraft(
        @JsonPropertyDescription("The answer in Markdown, in the language of the question. Reference passages inline as {{chunk:<chunkId>}} using chunk ids from the tool results.")
        String answer,
        @JsonPropertyDescription("Chunk ids (from the tool results) the answer relies on")
        List<String> citedChunkIds,
        @JsonPropertyDescription("true only if the passages found actually contain the information needed")
        boolean evidenceSufficient,
        @JsonPropertyDescription("What the knowledge base does not cover, or null")
        @Nullable String unansweredAspects
) {

    public List<String> citedChunkIdsOrEmpty() {
        return citedChunkIds != null ? citedChunkIds : List.of();
    }
}
