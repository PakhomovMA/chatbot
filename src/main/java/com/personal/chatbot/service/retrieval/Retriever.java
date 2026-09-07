package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;

/**
 * The only way into the knowledge base for anything above the infrastructure layer, agent actions
 * and LLM tools included (INV-01). One deterministic operation, so nothing that holds this contract
 * can reach Lucene, change the index or influence how retrieval works.
 */
public interface Retriever {

    RetrievalResult search(RetrievalQuery query);
}
