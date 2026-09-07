package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.models.retrieval.SearchExpansion;
import com.personal.chatbot.utils.RankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The retrieval half of {@code expandSearch} (docs/system-plan.md Phase 9a): runs the extra queries a
 * strategy produced and fuses their results with the first pass into one evidence list. Deciding
 * <em>whether</em> to widen and <em>how</em> to phrase the extra queries happens above this class;
 * everything here is deterministic and goes through {@link Retriever} like any other search (INV-01).
 *
 * <p>Passes are merged by reciprocal rank, the same rule that fuses the vector and lexical facets, so
 * a chunk that several passes agree on rises above one that only the weak first pass found. The merged
 * hits therefore carry an RRF-over-passes {@code fusedScore}; the per-facet scores stay as the pass
 * that found the chunk reported them, and a hit keeps the neighbours it was returned with.
 */
public class SearchExpander {

    private static final Logger log = LoggerFactory.getLogger(SearchExpander.class);

    private final Retriever retriever;
    private final RetrievalTraceStore traces;
    private final ChatbotProperties.Retrieval settings;

    public SearchExpander(Retriever retriever, RetrievalTraceStore traces, ChatbotProperties.Retrieval settings) {
        this.retriever = retriever;
        this.traces = traces;
        this.settings = settings;
    }

    /**
     * True when a second pass could still help: the evidence is weak and has not been widened yet.
     *
     * <p>A pass that returned nothing at all is not widened. With the noise floors at their defaults an
     * empty result means the corpus — or the document filter the question came with — has nothing to
     * give, and no rephrasing changes that; the answer path already handles empty evidence without
     * spending a model call.
     */
    public boolean worthExpanding(RetrievalResult first) {
        return !first.hits().isEmpty() && !first.evidenceSufficient() && !first.expanded();
    }

    /**
     * Searches once per extra query and merges everything with {@code first}.
     *
     * @param queries      the extra queries; empty means the strategy produced nothing to search with
     * @param queryBuildMs time already spent producing those queries (a model call, for most strategies)
     * @return a new result, recorded as its own trace, always carrying a {@link SearchExpansion} marker
     * so that the answer path can see the widening has happened and does not repeat it
     */
    public RetrievalResult expand(RetrievalQuery original, RetrievalResult first, ExpansionStrategy strategy,
                                  List<String> queries, long queryBuildMs) {
        long started = System.nanoTime();
        List<String> extra = queries.stream().map(String::strip).filter(q -> !q.isBlank()).distinct().toList();
        if (strategy == ExpansionStrategy.NEIGHBOURS && extra.size() > 1) {
            // The query text is not what this strategy varies, so more than one query would repeat a search.
            extra = extra.subList(0, 1);
        }
        if (extra.isEmpty()) {
            log.debug("Expansion {} produced no query for '{}'", strategy, first.query());
            return marked(first, SearchExpansion.none(strategy, queryBuildMs));
        }
        List<RetrievalResult> passes = new ArrayList<>(extra.size() + 1);
        passes.add(first);
        for (String query : extra) {
            passes.add(retriever.search(queryFor(original, query, strategy)));
        }
        long tookMs = queryBuildMs + (System.nanoTime() - started) / 1_000_000;
        RetrievalResult merged = merge(passes, strategy, extra, first.topK(), tookMs);
        traces.record(merged);
        log.info("Expanded search [{}] {} with {} queries: {} hits (+{} new), sufficient {} -> {} in {} ms",
                merged.traceId(), strategy, extra.size(), merged.hits().size(), merged.expansion().addedHits(),
                first.evidenceSufficient(), merged.evidenceSufficient(), tookMs);
        return merged;
    }

    /** NEIGHBOURS keeps the question and widens the reading window; the others replace the query text. */
    private RetrievalQuery queryFor(RetrievalQuery original, String query, ExpansionStrategy strategy) {
        return strategy == ExpansionStrategy.NEIGHBOURS
                ? original.withExpandNeighbours(Math.max(1, settings.expandNeighbours()))
                : original.withQuery(query);
    }

    private RetrievalResult merge(List<RetrievalResult> passes, ExpansionStrategy strategy, List<String> extraQueries,
                                  int topK, long tookMs) {
        Map<String, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<String, List<RetrievedChunk>> neighbours = new LinkedHashMap<>();
        List<List<String>> rankings = new ArrayList<>(passes.size());
        for (RetrievalResult pass : passes) {
            List<String> ranking = new ArrayList<>();
            for (RetrievedChunk chunk : pass.hits()) {
                if (chunk.isHit()) {
                    byId.putIfAbsent(chunk.chunkId(), chunk);
                    ranking.add(chunk.chunkId());
                } else {
                    neighbours.computeIfAbsent(chunk.neighbourOf(), _ -> new ArrayList<>()).add(chunk);
                }
            }
            rankings.add(ranking);
        }
        Set<String> firstPass = new LinkedHashSet<>(rankings.getFirst());

        List<RetrievedChunk> hits = new ArrayList<>();
        int added = 0;
        int rank = 0;
        for (RankFusion.Fused fused : RankFusion.reciprocalRank(rankings, settings.rrfK())) {
            if (rank == topK) {
                break;
            }
            RetrievedChunk hit = byId.get(fused.key()).withRanking(fused.score(), ++rank);
            hits.add(hit);
            if (!firstPass.contains(hit.chunkId())) {
                added++;
            }
            for (RetrievedChunk neighbour : neighbours.getOrDefault(hit.chunkId(), List.of())) {
                if (!byId.containsKey(neighbour.chunkId())) {
                    hits.add(neighbour.withRanking(hit.fusedScore(), hit.rank()));
                }
            }
        }
        double maxVector = RetrievalService.maxVectorScore(hits);
        RetrievalResult first = passes.getFirst();
        return new RetrievalResult(UUID.randomUUID().toString(),
                String.join(" | ", prepend(first.query(), extraQueries).stream().distinct().toList()), first.mode(), topK,
                passes.stream().mapToInt(RetrievalResult::candidates).sum(), List.copyOf(hits),
                RetrievalService.sufficient(hits, maxVector, settings.sufficientCosine()), maxVector,
                totalTimings(passes, tookMs), Instant.now(),
                new SearchExpansion(strategy, extraQueries, added, tookMs));
    }

    /** The unchanged first pass, marked as widened so the answer path does not try again. */
    private RetrievalResult marked(RetrievalResult first, SearchExpansion expansion) {
        RetrievalResult marked = new RetrievalResult(UUID.randomUUID().toString(), first.query(), first.mode(),
                first.topK(), first.candidates(), first.hits(), first.evidenceSufficient(), first.maxVectorScore(),
                first.timings(), Instant.now(), expansion);
        traces.record(marked);
        return marked;
    }

    private static RetrievalTimings totalTimings(List<RetrievalResult> passes, long tookMs) {
        return new RetrievalTimings(
                passes.stream().mapToLong(p -> p.timings().vectorMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().textMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().fusionMs()).sum(),
                passes.getFirst().timings().totalMs() + tookMs);
    }

    private static List<String> prepend(String head, List<String> tail) {
        List<String> all = new ArrayList<>(tail.size() + 1);
        all.add(head);
        all.addAll(tail);
        return all;
    }
}
