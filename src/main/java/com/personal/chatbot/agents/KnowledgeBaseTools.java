package com.personal.chatbot.agents;

import com.embabel.agent.api.annotation.LlmTool;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.utils.Texts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Tool boundary for annotation-driven agentic actions (docs/system-plan.md §7, INV-01): compact,
 * read-only wrappers over {@link Retriever} and the document registry, never over Lucene.
 *
 * <p><b>Not reachable by any model today.</b> Embabel does not scan beans for {@code @LlmTool}
 * (verified against 1.5.1: tool groups are MCP-only), so these methods run only once something
 * passes this bean to a prompt runner via {@code withToolObject} / {@code withTools}. The agentic
 * branch delivered in Phase 9c took the other route — {@code ToolishRag} over the store's guarded
 * search operations — so this class stands ready for the remaining Phase 9 steps and is otherwise
 * dead weight. Wire it or drop it; do not assume it is live.
 */
@Component
public class KnowledgeBaseTools {

    static final int MAX_TOOL_HITS = 5;
    static final int SNIPPET_CHARS = 400;

    private final Retriever retrievalService;
    private final DocumentRegistry registry;

    public KnowledgeBaseTools(Retriever retrievalService, DocumentRegistry registry) {
        this.retrievalService = retrievalService;
        this.registry = registry;
    }

    @LlmTool(description = "Search the knowledge base. Returns numbered passages with document, section and chunk id.")
    public String searchKnowledgeBase(
            @LlmTool.Param(description = "what to look for; a question or keywords") String query,
            @LlmTool.Param(description = "number of passages, 1-5", required = false) Integer topK) {
        int k = topK == null ? MAX_TOOL_HITS : Math.clamp(topK, 1, MAX_TOOL_HITS);
        RetrievalResult result = retrievalService.search(new RetrievalQuery(query, k, null, null));
        if (result.hits().isEmpty()) {
            return "No passages found.";
        }
        StringBuilder out = new StringBuilder();
        for (RetrievedChunk hit : result.hits()) {
            out.append('[').append(hit.rank()).append("] chunk ").append(hit.chunkId())
                    .append(" | document \"").append(hit.provenance().documentTitle()).append('"');
            if (!hit.provenance().sectionPath().isEmpty()) {
                out.append(" › ").append(String.join(" › ", hit.provenance().sectionPath()));
            }
            out.append('\n').append(Texts.singleLine(hit.text(), SNIPPET_CHARS)).append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    @LlmTool(description = "Describe a knowledge-base document by id: title, file, status and size.")
    public String getDocument(@LlmTool.Param(description = "document id") String documentId) {
        Optional<Document> document = registry.findById(documentId);
        return document.map(d -> "Document " + d.id() + ": \"" + d.title() + "\" (" + d.originalFilename() + ", "
                        + d.mediaType() + ", " + d.sizeBytes() + " bytes, status " + d.status() + ", version " + d.version()
                        + (d.chunkCount() != null ? ", " + d.chunkCount() + " chunks" : "") + ")")
                .orElse("No document with id " + documentId + ".");
    }

    @LlmTool(description = "List the documents in the knowledge base with their ids and titles.")
    public String listDocuments() {
        List<Document> documents = registry.findAll();
        if (documents.isEmpty()) {
            return "The knowledge base is empty.";
        }
        StringBuilder out = new StringBuilder();
        for (Document document : documents) {
            out.append(document.id()).append(" — ").append(document.title()).append(" (").append(document.status()).append(")\n");
        }
        return out.toString().stripTrailing();
    }
}
