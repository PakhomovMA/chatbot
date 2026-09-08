package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.tools.ResultsEvent;
import com.embabel.agent.rag.tools.ResultsListener;
import com.embabel.common.core.types.SimilarityResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link ResultsListener} for agentic retrieval (Phase 9c): records every chunk the model was shown
 * by any {@code ToolishRag} tool (vectorSearch, textSearch, broadenChunk, zoomOut), de-duplicated by
 * chunk id, so the evidence used for citation verification is exactly what the model saw (INV-02, INV-03).
 * Also keeps a log of the searches the model issued for diagnostics.
 *
 * <p>Embabel may run tool calls of one request on more than one thread, so both collections are
 * guarded by one monitor: a step and the chunks it contributed are recorded together, and a reader
 * never sees half of a tool result (docs/concurrency-plan.md C09). The progress callback runs
 * outside the monitor — it reaches an SSE connection, which must never be fed under a lock.
 */
public final class EvidenceCollector implements ResultsListener {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);

    /** One tool invocation as observed through its results. */
    public record SearchStep(String query, int results, long runningMs) {
    }

    private final Map<String, RetrievedChunk> chunks = new LinkedHashMap<>();
    private final List<SearchStep> steps = new ArrayList<>();
    private final Object lock = new Object();
    private final Consumer<SearchStep> onStep;

    public EvidenceCollector() {
        this(_ -> { });
    }

    /** @param onStep progress callback invoked after every tool invocation (used for streaming status) */
    public EvidenceCollector(Consumer<SearchStep> onStep) {
        this.onStep = onStep;
    }

    @Override
    public void onResultsEvent(ResultsEvent event) {
        SearchStep step = new SearchStep(event.getQuery(), event.getResults().size(), event.getRunningTime().toMillis());
        synchronized (lock) {
            steps.add(step);
            for (SimilarityResult<?> result : event.getResults()) {
                if (result.getMatch() instanceof Chunk chunk && !chunks.containsKey(chunk.getId())) {
                    chunks.put(chunk.getId(), ChunkMapper.toRetrievedChunk(chunk, null, null, result.getScore(), chunks.size() + 1));
                }
            }
            log.debug("Agentic search '{}' returned {} results ({} distinct chunks so far)", event.getQuery(),
                    event.getResults().size(), chunks.size());
        }
        onStep.accept(step);
    }

    /** Chunks in the order the model first saw them; {@code rank} is that order. */
    public List<RetrievedChunk> chunks() {
        synchronized (lock) {
            return List.copyOf(chunks.values());
        }
    }

    /** Seed only the passages actually included in the research prompt, before tools run. */
    public void addShownChunks(List<RetrievedChunk> shown) {
        synchronized (lock) {
            for (RetrievedChunk chunk : shown) {
                chunks.putIfAbsent(chunk.chunkId(), chunk.withRanking(chunk.fusedScore(), chunks.size() + 1));
            }
        }
    }

    public List<SearchStep> steps() {
        synchronized (lock) {
            return List.copyOf(steps);
        }
    }

    /** Position (1-based) of a chunk id in the collected evidence, or -1. */
    public int indexOf(String chunkId) {
        synchronized (lock) {
            int i = 0;
            for (String id : chunks.keySet()) {
                i++;
                if (id.equals(chunkId)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
