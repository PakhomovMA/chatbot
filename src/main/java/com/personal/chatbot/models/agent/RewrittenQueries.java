package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Model output of the REWRITE expansion strategy (docs/system-plan.md Phase 9a): other ways to ask
 * the same question. Nothing here reaches the answer — the strings are only used as search queries.
 */
@JsonClassDescription("Alternative search queries for a question the first search could not answer")
public record RewrittenQueries(
        @JsonPropertyDescription("Search queries, each a short phrase in the words the documentation would use")
        @Nullable List<String> queries
) {

    public List<String> queriesOrEmpty() {
        return queries != null ? queries : List.of();
    }
}
