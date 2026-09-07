package com.personal.chatbot.models.retrieval;

/** Which Lucene facets contribute to a search (docs/system-plan.md §6). */
public enum RetrievalMode {
    /** Vector k-NN and BM25 fused with reciprocal rank fusion (default). */
    HYBRID,
    VECTOR,
    TEXT
}
