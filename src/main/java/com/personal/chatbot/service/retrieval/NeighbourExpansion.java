package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ContentElement;
import com.personal.chatbot.models.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Widens each hit with the chunks that surround it in reading order (docs/system-plan.md §6, post-filter 5).
 * The store groups by container section, so a neighbour is the adjacent passage of the same document
 * and may belong to a neighbouring sub-section.
 *
 * <p>Chunking an oversized section can separate a passage from the sentence that governs it, and no
 * ranking change fixes that: the right chunk was already first. Expansion happens after fusion and
 * after {@code topK}, so it never displaces a ranked hit — the neighbours are continuation context,
 * carry no scores of their own and keep their hit's rank. The evidence budget in the prompt is what
 * limits how much of it the model actually sees.
 */
public class NeighbourExpansion {

    /** Chunks around {@code chunkId} in its container section, in sequence order, including that chunk. */
    @FunctionalInterface
    public interface Expander {
        List<ContentElement> around(String chunkId, int chunksEachSide);
    }

    private final Expander expander;
    private final int chunksEachSide;

    public NeighbourExpansion(Expander expander, int chunksEachSide) {
        this.expander = expander;
        this.chunksEachSide = chunksEachSide;
    }

    public boolean enabled() {
        return chunksEachSide > 0;
    }

    /**
     * @return the hits in ranking order, each followed by its section neighbours in reading order;
     * every chunk appears once, and a chunk that is itself a hit is never demoted to a neighbour
     */
    public List<RetrievedChunk> expand(List<RetrievedChunk> hits) {
        if (!enabled() || hits.isEmpty()) {
            return hits;
        }
        Set<String> hitIds = new HashSet<>(hits.size());
        for (RetrievedChunk hit : hits) {
            hitIds.add(hit.chunkId());
        }
        Set<String> emitted = new HashSet<>();
        List<RetrievedChunk> expanded = new ArrayList<>(hits.size() * (1 + 2 * chunksEachSide));
        for (RetrievedChunk hit : hits) {
            for (ContentElement element : expander.around(hit.chunkId(), chunksEachSide)) {
                if (element.getId().equals(hit.chunkId())) {
                    if (emitted.add(hit.chunkId())) {
                        expanded.add(hit);
                    }
                } else if (element instanceof Chunk chunk && !hitIds.contains(chunk.getId()) && emitted.add(chunk.getId())) {
                    expanded.add(ChunkMapper.toNeighbour(chunk, hit));
                }
            }
            // The store returns nothing when a chunk carries no section metadata; the hit still counts.
            if (emitted.add(hit.chunkId())) {
                expanded.add(hit);
            }
        }
        return List.copyOf(expanded);
    }
}
