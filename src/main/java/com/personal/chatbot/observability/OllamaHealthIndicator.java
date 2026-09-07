package com.personal.chatbot.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code ollama} health: the server answers {@code /api/tags} and the configured chat model is installed.
 * DOWN when unreachable, OUT_OF_SERVICE when the model is missing, UNKNOWN when Ollama is not configured
 * (hermetic tests).
 */
@Component("ollama")
class OllamaHealthIndicator implements HealthIndicator {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient client;
    private final String baseUrl;
    private final String chatModel;

    OllamaHealthIndicator(Environment environment) {
        this.baseUrl = environment.getProperty("embabel.agent.platform.models.ollama.base-url", "");
        this.chatModel = environment.getProperty("embabel.models.default-llm", "");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder().baseUrl(baseUrl.isBlank() ? "http://localhost:11434" : baseUrl)
                .requestFactory(requestFactory).build();
    }

    @Override
    public Health health() {
        if (baseUrl.isBlank()) {
            return Health.unknown().withDetail("reason", "Ollama base-url not configured").build();
        }
        Map<String, Object> tags;
        try {
            tags = client.get().uri("/api/tags").retrieve().body(new ParameterizedTypeReference<>() {
            });
        } catch (RestClientException e) {
            return Health.down().withDetail("baseUrl", baseUrl).withDetail("error", e.getMessage()).build();
        }
        List<String> models = tags == null ? List.of() : ((List<?>) tags.getOrDefault("models", List.of())).stream()
                .map(m -> m instanceof Map<?, ?> map ? String.valueOf(map.get("name")) : String.valueOf(m)).toList();
        Health.Builder builder = models.contains(chatModel) ? Health.up() : Health.outOfService()
                .withDetail("reason", "chat model " + chatModel + " is not installed; run: ollama pull " + chatModel);
        return builder.withDetail("baseUrl", baseUrl).withDetail("chatModel", chatModel).withDetail("models", models).build();
    }
}
