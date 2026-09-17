package com.personal.chatbot.support;

import com.github.benmanes.caffeine.cache.Ticker;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.service.cache.CaffeineDerivationCache;
import com.personal.chatbot.service.cache.PipelineFingerprint;

import java.time.Duration;

public final class DerivationCaches {
    private DerivationCaches() { }

    public static CaffeineDerivationCache serving(TestObservations observations) {
        return new CaffeineDerivationCache(new ChatbotProperties.DerivationCache(true, false, Duration.ofDays(7), 5000, org.springframework.util.unit.DataSize.ofMegabytes(16)),
                "eval-model", new PipelineFingerprint("eval-pipeline"),
                new CacheObservations(observations.observations()), Ticker.systemTicker());
    }
}
