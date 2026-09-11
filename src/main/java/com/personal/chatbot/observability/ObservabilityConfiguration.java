package com.personal.chatbot.observability;

import com.personal.chatbot.config.ChatbotProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashSet;
import java.util.List;

/**
 * The one place the application's own telemetry is wired (docs/observability-plan.md §3.1): the
 * capability facades and the schema policy of {@link MeterSchema}.
 *
 * <p>Nothing here decides whether tracing, sampling or an exporter is on. Metric values are published
 * whatever the trace pipeline is doing (§5.3), and a facade works the same with none of it configured.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    MonotonicClock monotonicClock() {
        return MonotonicClock.SYSTEM;
    }

    @Bean
    Observations observations(ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                              MonotonicClock clock) {
        return new Observations(observationRegistry, meterRegistry, clock);
    }

    @Bean
    ChatObservations chatObservations(Observations observations) {
        return new ChatObservations(observations);
    }

    @Bean
    RetrievalObservations retrievalObservations(Observations observations) {
        return new RetrievalObservations(observations);
    }

    @Bean
    IngestionObservations ingestionObservations(Observations observations) {
        return new IngestionObservations(observations);
    }

    @Bean
    SseObservations sseObservations(Observations observations) {
        return new SseObservations(observations);
    }

    @Bean
    CacheObservations cacheObservations(Observations observations) {
        return new CacheObservations(observations);
    }

    @Bean
    MeterFilter chatbotLabelSchema() {
        return MeterSchema.labels();
    }

    @Bean
    MeterFilter chatbotHistograms(ChatbotProperties.Observability settings) {
        return MeterSchema.histograms(settings.histograms());
    }

    @Bean
    MeterFilter configuredMetricModels(
            @Value("${embabel.models.default-llm}") String llm,
            @Value("${chatbot.embedding.onnx.model-name}") String onnx,
            @Value("${chatbot.embedding.ollama.model}") String ollama) {
        return MeterSchema.models(new HashSet<>(List.of(llm, onnx, ollama)));
    }

    @Bean
    MeterFilter providerModelCap() {
        return MeterFilter.maximumAllowableTags("gen_ai.", "gen_ai.response.model", 5, MeterFilter.deny());
    }

    @Bean
    MeterFilter httpUriCap() {
        return MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny());
    }

    @Bean
    MeterFilter applicationOperationCap() {
        return MeterFilter.maximumAllowableTags("chatbot.", "operation", 16, MeterFilter.deny());
    }
}
