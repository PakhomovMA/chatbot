package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.knowledge.Document;

import java.util.List;

public record DocumentPage(List<Document> items, long total, int page, int size) {
}
