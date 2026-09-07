package com.personal.chatbot.models.knowledge;

import java.time.Instant;

/** Why a document ended up {@link DocumentStatus#FAILED}. */
public record DocumentError(String stage, String message, Instant at) {
}
