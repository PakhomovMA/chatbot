package com.personal.chatbot.service.chat;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.tools.ResultsEvent;
import com.embabel.agent.rag.tools.SearchTools;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.SimpleSimilaritySearchResult;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.service.retrieval.EvidenceCollector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticDraftMapperTest {

    private static final SearchTools SOURCE = new SearchTools() {
    };

    static Chunk chunk(String id, String text) {
        return Chunk.create(text, "parent", Map.of(
                ProvenanceChunkTransformer.DOCUMENT_ID, "doc", ProvenanceChunkTransformer.DOCUMENT_TITLE, "Doc",
                ProvenanceChunkTransformer.SECTION_PATH, "Restart", ProvenanceChunkTransformer.URTEXT, text), id, text, null);
    }

    static ResultsEvent event(String query, List<SimilarityResult<? extends Retrievable>> results) {
        return new ResultsEvent(SOURCE, query, results, Duration.ofMillis(5), Instant.now());
    }

    static SimilarityResult<? extends Retrievable> hit(Chunk chunk, double score) {
        return new SimpleSimilaritySearchResult<>(chunk, score);
    }

    static EvidenceCollector collectorWith(Chunk... chunks) {
        EvidenceCollector collector = new EvidenceCollector();
        List<SimilarityResult<? extends Retrievable>> results = new ArrayList<>();
        for (Chunk chunk : chunks) {
            results.add(hit(chunk, 0.7));
        }
        collector.onResultsEvent(event("q", results));
        return collector;
    }

    @Test
    void collectorDeduplicatesAndKeepsFirstSeenOrder() {
        EvidenceCollector collector = collectorWith(chunk("a", "first"), chunk("b", "second"));
        collector.onResultsEvent(event("again", List.of(hit(chunk("b", "second"), 0.9), hit(chunk("c", "third"), 0.5))));
        assertThat(collector.chunks()).extracting(c -> c.chunkId()).containsExactly("a", "b", "c");
        assertThat(collector.chunks()).extracting(c -> c.rank()).containsExactly(1, 2, 3);
        assertThat(collector.chunks().getFirst().provenance().documentTitle()).isEqualTo("Doc");
        assertThat(collector.steps()).extracting(EvidenceCollector.SearchStep::query).containsExactly("q", "again");
        assertThat(collector.indexOf("c")).isEqualTo(3);
        assertThat(collector.indexOf("zzz")).isEqualTo(-1);
    }

    @Test
    void seededEvidenceAndToolResultsShareOneCitationNamespace() {
        EvidenceCollector seed = collectorWith(chunk("a", "seed"), chunk("b", "second"));
        EvidenceCollector collector = new EvidenceCollector();
        collector.addShownChunks(seed.chunks());
        collector.onResultsEvent(event("follow-up", List.of(hit(chunk("a", "seed"), 0.8),
                hit(chunk("c", "new tool result"), 0.7))));
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(new AgenticDraft(
                "Seed {{chunk:a}} and follow-up {{chunk:c}}.", List.of("a", "c"), true, null), collector);
        assertThat(collector.chunks()).extracting(c -> c.chunkId()).containsExactly("a", "b", "c");
        assertThat(numbered.answer()).isEqualTo("Seed [1] and follow-up [3].");
        assertThat(numbered.citedEvidence()).containsExactly(1, 3);
    }

    @Test
    void chunkReferencesBecomeNumberedMarkersAndUnknownOnesAreDropped() {
        EvidenceCollector collector = collectorWith(chunk("doc:1:0", "restart"), chunk("doc:1:1", "rollback"));
        AgenticDraft draft = new AgenticDraft(
                "Restart with systemctl {{chunk:doc:1:1}} . Then roll back {{ chunk: doc:1:0 }}. Unknown {{chunk:nope}} claim.",
                List.of("doc:1:0", "ghost"), true, null);
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(draft, collector);
        assertThat(numbered.answer()).isEqualTo("Restart with systemctl [2]. Then roll back [1]. Unknown claim.");
        assertThat(numbered.citedEvidence()).containsExactly(1, 2);
        assertThat(numbered.evidenceSufficient()).isTrue();
    }

    @Test
    void theLabelTheModelEchoesFromThePassagesIsAcceptedTooAndNeverReachesTheReader() {
        EvidenceCollector collector = collectorWith(chunk("f92321f9-d29f-4cb3-9c90-976decbd632a:1:0", "post-training"),
                chunk("a3f3e189-69ba-4ae9-a260-306a710fc699:1:27", "2.8 trillion parameters"));
        AgenticDraft draft = new AgenticDraft(
                "GLM improves through post-training {{chunkId: f92321f9-d29f-4cb3-9c90-976decbd632a:1:0}}. "
                        + "Kimi has 2.8 trillion parameters {{Chunk ID: a3f3e189-69ba-4ae9-a260-306a710fc699:1:27}}. "
                        + "Both {{chunk_ids: f92321f9-d29f-4cb3-9c90-976decbd632a:1:0, a3f3e189-69ba-4ae9-a260-306a710fc699:1:27}}. "
                        + "Bare {{a3f3e189-69ba-4ae9-a260-306a710fc699:1:27}}.",
                List.of(), true, null);
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(draft, collector);
        assertThat(numbered.answer()).isEqualTo("GLM improves through post-training [1]. "
                + "Kimi has 2.8 trillion parameters [2]. Both [1][2]. Bare [2].");
        assertThat(numbered.answer()).doesNotContain("{{");
        assertThat(numbered.citedEvidence()).containsExactly(1, 2);
    }

    @Test
    void aTemplateSnippetQuotedFromADocumentIsLeftAlone() {
        EvidenceCollector collector = collectorWith(chunk("doc:1:0", "config"));
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(new AgenticDraft(
                "Set the greeting to {{ user.name }} in the template {{chunk:doc:1:0}}.", List.of(), true, null), collector);
        assertThat(numbered.answer()).isEqualTo("Set the greeting to {{ user.name }} in the template [1].");
    }

    @Test
    void insufficientDraftIsPreserved() {
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(
                new AgenticDraft("Nothing about ports.", null, false, "the port"), collectorWith());
        assertThat(numbered.answer()).isEqualTo("Nothing about ports.");
        assertThat(numbered.citedEvidence()).isEmpty();
        assertThat(numbered.evidenceSufficient()).isFalse();
        assertThat(numbered.unansweredAspects()).isEqualTo("the port");
    }
}
