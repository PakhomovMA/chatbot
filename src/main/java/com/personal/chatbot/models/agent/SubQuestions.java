package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Model output of {@code decomposeQuestion} (docs/system-plan.md Phase 9d): the parts a multi-part
 * question falls into. Like the expansion strategies of Phase 9a, nothing here reaches the answer —
 * the strings are search queries and nothing else, and each is retrieved on its own.
 */
@JsonClassDescription("The independent parts of a question that asks for more than one thing")
public record SubQuestions(
        @JsonPropertyDescription("One self-contained search question per part, or an empty list when the question asks for a single thing")
        @Nullable List<String> questions
) {

    public List<String> questionsOrEmpty() {
        return questions != null ? questions : List.of();
    }
}
