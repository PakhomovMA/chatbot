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
}
