package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.knowledge.DocumentStatus;

/** @param duplicate true when identical content was already registered and no new document was created. */
public record UploadResponse(String documentId, DocumentStatus status, int version, boolean duplicate) {
}
