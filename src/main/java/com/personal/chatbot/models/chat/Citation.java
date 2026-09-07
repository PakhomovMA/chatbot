package com.personal.chatbot.models.chat;

import java.util.List;

/**
 * A verified reference from the answer to a retrieved chunk (docs/system-plan.md §4, INV-02).
 *
 * @param marker the {@code [n]} number used in the answer text
 * @param quote  the cited chunk's original text, truncated for the UI
 * @param score  fused retrieval score of the chunk
 */
public record Citation(
        int marker,
        String documentId,
        String documentTitle,
        String sectionTitle,
        List<String> sectionPath,
        String chunkId,
        String quote,
        double score
) {
}
