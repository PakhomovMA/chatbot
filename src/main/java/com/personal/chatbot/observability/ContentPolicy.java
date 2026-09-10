package com.personal.chatbot.observability;

/** Content capture is opt-in; every destination still goes through TelemetrySanitizer. */
public enum ContentPolicy { METADATA_ONLY, REDACTED_CONTENT }
