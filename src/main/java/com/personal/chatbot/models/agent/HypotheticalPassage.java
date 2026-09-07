package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

/**
 * Model output of the HYDE expansion strategy (docs/system-plan.md Phase 9a): the passage the
 * documentation would contain if it answered the question. It is invented on purpose and is used
 * only as the text of a search query, never as evidence and never shown to the user (INV-03).
 */
@JsonClassDescription("The passage a documentation page would contain if it answered the question")
public record HypotheticalPassage(
        @JsonPropertyDescription("Two to four sentences of documentation prose, in the vocabulary such a page would use")
        @Nullable String passage
) {
}
