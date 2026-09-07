package com.personal.chatbot.models.agent;

import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.chat.Grounding;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Goal artifact of the knowledge assistant agent: an answer whose citations were verified against the evidence. */
public record GroundedAnswer(
        String answer,
        Grounding grounding,
        List<Citation> citations,
        @Nullable String notes,
        String retrievalTraceId,
        long retrievalMs
) {
}
