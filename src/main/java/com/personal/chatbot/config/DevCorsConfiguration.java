package com.personal.chatbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Vite dev server (port 5173) to call the API during frontend development
 * ({@code SPRING_PROFILES_ACTIVE=dev}). Not active otherwise: the packaged app serves the UI itself,
 * same origin (docs/system-plan.md D13).
 */
@Configuration
@Profile("dev")
class DevCorsConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
