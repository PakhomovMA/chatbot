package com.personal.chatbot.service.index;

import com.personal.chatbot.models.index.IndexInfo;
import com.personal.chatbot.models.index.IndexState;

/**
 * Read-only view of the index for status, health and metrics. Separate from
 * {@link KnowledgeIndexWriter} so that reporting an index cannot accidentally change one.
 */
public interface IndexStatus {

    IndexInfo info();

    IndexState state();

    /**
     * Version of what a search can find (docs/cache-plan.md §3.1): it grows with every operation that
     * may have changed the content and with nothing that only reads. Kept in memory, so it starts over
     * with the process — as do the caches scoped by it.
     */
    long revision();
}
