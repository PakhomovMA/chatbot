package com.personal.chatbot.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.observability.CacheObservations;

import java.util.List;
import java.util.Optional;

import static com.personal.chatbot.observability.CacheObservations.Layer.DERIVATION;
import static com.personal.chatbot.observability.CacheObservations.Lookup.*;

/** Prompt-keyed transformations; no revision and no retrieved evidence. Shadow stores only digests. */
public final class CaffeineDerivationCache implements DerivationCache {
    private final Cache<String, List<String>> entries;
    private final ChatbotProperties.DerivationCache settings;
    private final String llm;
    private final PipelineFingerprint pipeline;
    private final CacheObservations observations;

    public CaffeineDerivationCache(ChatbotProperties.DerivationCache settings, String llm,
                                   PipelineFingerprint pipeline, CacheObservations observations, Ticker ticker) {
        if (settings.enabled() && settings.shadow()) {
            throw new IllegalArgumentException("Derivation serving and shadow are mutually exclusive");
        }
        this.settings = settings;
        this.llm = llm;
        this.pipeline = pipeline;
        this.observations = observations;
        // Charging at least this much per entry enforces maxEntries as well as the text budget.
        long entryFloor = Math.ceilDiv(settings.maxWeight().toBytes(), settings.maxEntries());
        this.entries = Caffeine.newBuilder().maximumWeight(settings.maxWeight().toBytes())
                .weigher((String _, List<String> value) -> (int) Math.min(Integer.MAX_VALUE,
                        Math.max(entryFloor, 256L + value.stream().mapToLong(v -> 2L * v.length() + 32).sum())))
                .expireAfterWrite(settings.ttl())
                .ticker(ticker).executor(Runnable::run)
                .removalListener((String _, List<String> _, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    switch (cause) {
                        case SIZE -> observations.evicted(DERIVATION, CacheObservations.Eviction.SIZE);
                        case EXPIRED -> observations.evicted(DERIVATION, CacheObservations.Eviction.EXPIRED);
                        default -> { }
                    }
                }).build();
        observations.entries(DERIVATION, entries, Cache::estimatedSize);
    }

    @Override
    public Attempt lookup(Derivation step, String prompt, String instructions, double temperature, UserQuestion question) {
        question.abortIfCancelled();
        if (!settings.enabled() && !settings.shadow()) {
            observations.derivationLookup(BYPASS, false);
            return NONE.lookup(step, prompt, instructions, temperature, question);
        }
        String key = CacheKeys.derivation(step, prompt, instructions, llm, temperature, pipeline);
        List<String> cached = entries.getIfPresent(key);
        observations.derivationLookup(cached != null ? HIT : MISS, settings.shadow());
        return new Attempt() {
            @Override
            public Optional<List<String>> value() {
                question.abortIfCancelled();
                return settings.shadow() ? Optional.empty() : Optional.ofNullable(cached);
            }

            @Override
            public void usable(List<String> value) {
                question.abortIfCancelled();
                if (value.isEmpty()) {
                    observations.store(DERIVATION, CacheObservations.Store.INELIGIBLE);
                    return;
                }
                // Hits never refresh expireAfterWrite, including would-hits in shadow.
                if (cached != null) return;
                List<String> copy = settings.shadow() ? List.of() : List.copyOf(value);
                question.derivations().add(() -> {
                    question.abortIfCancelled();
                    entries.put(key, copy);
                    observations.store(DERIVATION, CacheObservations.Store.STORED);
                });
            }
        };
    }

    void cleanUp() {
        entries.cleanUp();
    }
}
