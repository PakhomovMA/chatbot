package com.personal.chatbot.models.agent;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;

import java.util.List;

/** Blackboard artifact: what deterministic retrieval found for the question (INV-01, INV-06). */
public record Evidence(UserQuestion question, RetrievalResult retrieval) {

    public List<RetrievedChunk> hits() {
        return retrieval.hits();
    }

    public boolean isEmpty() {
        return retrieval.hits().isEmpty();
    }

    /** Whether retrieval scores alone suggest the corpus covers the question (calibrated floor). */
    public boolean sufficientByScore() {
        return retrieval.evidenceSufficient();
    }
}
