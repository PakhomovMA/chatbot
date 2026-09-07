package com.personal.chatbot.controller;

import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.service.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Chat API (docs/system-plan.md §8). Streaming arrives in Phase 7. */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @GetMapping("/conversations/{id}")
    public ConversationView conversation(@PathVariable String id) {
        return chatService.conversation(id);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@PathVariable String id) {
        chatService.deleteConversation(id);
    }
}
