package com.personal.chatbot.service.cache;

import java.util.Optional;

/**
 * Finished answers by their {@link CacheKeys#answer key} (docs/cache-plan.md §1). Only the store: whether
 * a request may be answered from it and whether a result may be kept is decided by its caller, which
 * knows the history, the grounding and the revision the run started at.
 */
public interface AnswerCache {

    Optional<CachedAnswer> find(String key);

    void put(String key, CachedAnswer answer);
}
