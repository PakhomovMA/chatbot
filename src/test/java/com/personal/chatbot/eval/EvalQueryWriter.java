package com.personal.chatbot.eval;

import com.personal.chatbot.models.agent.HypotheticalPassage;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.models.agent.StandaloneQuery;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.models.chat.AnswerLanguage;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The model half of {@code expandSearch}, for the eval only (docs/system-plan.md Phase 9a): it sends
 * the very same standing instructions and user prompt the application sends, straight to Ollama, so
 * the measured strategies are the shipped ones. The eval indexes with the real embedding model but
 * boots no Spring context, and this keeps it that way.
 *
 * <p>Structured output is asked for with Ollama's JSON mode plus an explicit shape in the prompt,
 * where the application relies on Embabel's data binding; the model, temperature and instructions are
 * identical, the wrapper is not.
 */
final class EvalQueryWriter implements AutoCloseable {

    private static final URI GENERATE = URI.create(baseUrl() + "/api/generate");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final GroundingInstructions instructions;
    private final GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO);
    private final String model;
    private final double temperature;
    private long lastCallMs;

    EvalQueryWriter(String model, double temperature, int queries) {
        this.model = model;
        this.temperature = temperature;
        this.instructions = new GroundingInstructions(4, queries);
    }

    static String baseUrl() {
        return System.getProperty("eval.ollamaUrl", "http://localhost:11434");
    }

    /** Whether Ollama answers at all; the model-driven strategies are skipped when it does not. */
    static boolean ollamaAvailable() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    List<String> rewrite(String question, int max) {
        RewrittenQueries rewritten = generate(instructions.queryRewrite().contribution(),
                prompt.buildForExpansion(question) + "\n\nReply with JSON: {\"queries\": [\"...\"]}", RewrittenQueries.class);
        return rewritten.queriesOrEmpty().stream()
                .filter(q -> q != null && !q.isBlank())
                .map(String::strip)
                .filter(q -> !q.equalsIgnoreCase(question.strip()))
                .limit(max)
                .toList();
    }

    List<String> hypothetical(String question) {
        HypotheticalPassage passage = generate(instructions.hypotheticalPassage().contribution(),
                prompt.buildForExpansion(question) + "\n\nReply with JSON: {\"passage\": \"...\"}", HypotheticalPassage.class);
        return passage.passage() == null || passage.passage().isBlank() ? List.of() : List.of(passage.passage().strip());
    }

    StandaloneQuery resolveConversation(UserQuestion question) {
        return generate(instructions.conversationRewrite().contribution(),
                prompt.buildForConversationRewrite(question.question(), question.history())
                        + "\n\nReply with JSON: {\"query\": \"...\"}", StandaloneQuery.class);
    }

    /** Wall time of the last model call, the price the branch pays before it can search again. */
    long lastCallMs() {
        return lastCallMs;
    }

    private <T> T generate(String system, String userPrompt, Class<T> type) {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "model", model, "system", system, "prompt", userPrompt, "stream", false, "think", false,
                "format", "json", "options", java.util.Map.of("temperature", temperature)));
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(GENERATE)
                            .timeout(Duration.ofMinutes(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Ollama answered " + response.statusCode() + ": " + response.body());
            }
            String generated = MAPPER.readTree(response.body()).path("response").asString();
            return MAPPER.readValue(generated, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Query expansion call failed: " + e, e);
        } finally {
            lastCallMs = (System.nanoTime() - started) / 1_000_000;
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
