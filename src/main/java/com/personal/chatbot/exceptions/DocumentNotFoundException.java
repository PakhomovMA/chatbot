package com.personal.chatbot.exceptions;

public class DocumentNotFoundException extends RuntimeException {

    private final String documentId;

    public DocumentNotFoundException(String documentId) {
        super("Document not found: " + documentId);
        this.documentId = documentId;
    }

    public String documentId() {
        return documentId;
    }
}
