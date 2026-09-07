package com.personal.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Local-first RAG / knowledge assistant. Embabel platform, Ollama models, Lucene RAG store and
 * Tika parsing are wired by their Spring Boot auto-configurations (no {@code @EnableAgents}:
 * it is deprecated for removal in Embabel 1.5.1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatbotApplication {

    static void main(String[] args) {
        SpringApplication.run(ChatbotApplication.class, args);
    }

}
