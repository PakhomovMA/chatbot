package com.personal.chatbot.service.cache;

/**
 * Where an answer entry is valid (docs/cache-plan.md §3.1): the knowledge-base revision it was computed
 * at and the pipeline it was computed with. A run takes its scope once, at lookup, and stores its result
 * under that same scope only if the revision has not moved since — the stale-put guard.
 */
public record CacheScope(long revision, PipelineFingerprint pipeline) {
}
