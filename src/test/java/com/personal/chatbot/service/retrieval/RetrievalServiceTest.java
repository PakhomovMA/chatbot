package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.personal.chatbot.support.TestObservations;

/** Phase 4 gate: hybrid retrieval over an in-memory store with deterministic fake embeddings. */
class RetrievalServiceTest {

    private static final String DEPLOY_DOC = """
            # Deployment Guide

            ## Pipeline

            Every merge to main triggers the pipeline. The canary receives five percent of traffic for thirty minutes.

            ## Secrets

            Secrets are stored in Vault under the path secret/payments and rotated every ninety days.
            The rotation job is called vault-rotator and runs on Sundays.
            """;

    /** One section long enough for the 400-character chunker to split it into several chunks. */
    private static final String HANDBOOK_DOC = """
            # On-call Handbook

            ## Escalation

            The primary on-call acknowledges a page within five minutes and starts the incident channel.
            If there is no acknowledgement the pager escalates to the secondary after ten minutes.
            The secondary on-call has the same responsibilities and the same tooling access as the primary.
            When both are unavailable the incident commander of the week is paged directly by the duty manager.
            Escalation to the engineering manager happens only after thirty minutes without an acknowledgement.
            Every escalation step is recorded in the incident timeline together with the name of the responder.
            The timeline is exported to the postmortem document once the incident is closed by the commander.
            """;

    @TempDir
    Path dir;

    private LuceneIndexStore store;
    private RetrievalTraceStore traces;
    private RetrievalService service;

    @BeforeEach
    void setUp() throws Exception {
        store = IndexStores.store(null, new FakeTextEmbedder(32)).open();
        traces = new RetrievalTraceStore(50);
        service = new RetrievalService(store, traces, properties(0.3), TestObservations.retrieval());
        store.writeDocument(parsed("runbook", "Payments Runbook", TestDocuments.MARKDOWN));
        store.writeDocument(parsed("deploy", "Deployment Guide", DEPLOY_DOC));
        store.writeDocument(parsed("handbook", "On-call Handbook", HANDBOOK_DOC));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ChatbotProperties.Retrieval properties(double sufficientCosine) {
        return properties(sufficientCosine, 0);
    }

    private ChatbotProperties.Retrieval properties(double sufficientCosine, int expandNeighbours) {
        return new ChatbotProperties.Retrieval(5, 3, 60, -1.0, 0.0, sufficientCosine, expandNeighbours, 1.0, 50);
    }

    private NavigableDocument parsed(String id, String title, String markdown) throws Exception {
        Path file = dir.resolve(id + ".md");
        Files.writeString(file, markdown);
        Document document = new Document(id, title, id + ".md", "text/markdown", 1, "h-" + id, 1, Instant.now(), Instant.now(),
                DocumentStatus.READY, null, null, null, null, null);
        return new DocumentParser().parse(document, file);
    }

    @Test
    void exactPhraseIsFoundByTextFacetAndCarriesProvenance() {
        RetrievalResult result = service.search(RetrievalQuery.of("vault-rotator"));
        assertThat(result.mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.hits()).isNotEmpty();
        RetrievedChunk top = result.hits().getFirst();
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.text()).contains("vault-rotator").doesNotStartWith("Document:");
        assertThat(top.textScore()).isNotNull().isPositive();
        assertThat(top.provenance().documentId()).isEqualTo("deploy");
        assertThat(top.provenance().documentTitle()).isEqualTo("Deployment Guide");
        assertThat(top.provenance().sectionPath()).contains("Secrets");
        assertThat(top.provenance().chunkId()).startsWith("deploy:1:");
        assertThat(result.hits()).extracting(RetrievedChunk::rank).isSorted();
        assertThat(traces.find(result.traceId())).contains(result);
        assertThat(result.timings().totalMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void vectorFacetRanksSemanticallyOverlappingChunkFirst() {
        RetrievalResult result = service.search(new RetrievalQuery("restart payment service host", null, RetrievalMode.VECTOR, null));
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().getFirst().text()).contains("systemctl restart payments");
        assertThat(result.hits()).allSatisfy(hit -> {
            assertThat(hit.vectorScore()).isNotNull().isBetween(-1.0, 1.0);
            assertThat(hit.textScore()).isNull();
        });
        assertThat(result.maxVectorScore()).isEqualTo(result.hits().getFirst().vectorScore());
    }

    @Test
    void hybridCombinesBothFacets() {
        RetrievalResult result = service.search(new RetrievalQuery("rotate secrets in Vault every ninety days", 3, RetrievalMode.HYBRID, null));
        assertThat(result.topK()).isEqualTo(3);
        assertThat(result.candidates()).isEqualTo(9);
        assertThat(result.hits()).hasSizeLessThanOrEqualTo(3);
        RetrievedChunk top = result.hits().getFirst();
        assertThat(top.text()).contains("rotated every ninety days");
        assertThat(top.vectorScore()).isNotNull();
        assertThat(top.textScore()).isNotNull();
        assertThat(top.fusedScore()).isGreaterThan(result.hits().getLast().fusedScore());
    }

    @Test
    void documentFilterRestrictsHits() {
        RetrievalResult result = service.search(new RetrievalQuery("payments", null, null, Set.of("runbook")));
        assertThat(result.hits()).isNotEmpty().allMatch(hit -> hit.provenance().documentId().equals("runbook"));
        assertThat(result.candidates()).isEqualTo(30);
        assertThat(service.search(new RetrievalQuery("payments", null, null, Set.of("nope"))).hits()).isEmpty();
    }

    @Test
    void sufficiencyFollowsTheCalibratedFloor() {
        RetrievalResult lenient = service.search(RetrievalQuery.of("systemctl restart payments"));
        assertThat(lenient.maxVectorScore()).isGreaterThan(0.3);
        assertThat(lenient.evidenceSufficient()).isTrue();

        RetrievalService strict = new RetrievalService(store, traces, properties(0.999), TestObservations.retrieval());
        assertThat(strict.search(RetrievalQuery.of("systemctl restart payments")).evidenceSufficient()).isFalse();
        assertThat(service.search(new RetrievalQuery("systemctl", null, RetrievalMode.TEXT, null)).evidenceSufficient()).isFalse();
    }

    @Test
    void neighbourExpansionAddsSectionContextAroundTheHitsWithoutChangingTheRanking() {
        RetrievalQuery query = new RetrievalQuery("secondary on-call responsibilities", 2, RetrievalMode.VECTOR, null);
        List<RetrievedChunk> plain = service.search(query).hits();

        RetrievalService expanding = new RetrievalService(store, traces, properties(0.3, 1), TestObservations.retrieval());
        List<RetrievedChunk> expanded = expanding.search(query).hits();

        assertThat(expanded).filteredOn(RetrievedChunk::isHit).containsExactlyElementsOf(plain);
        assertThat(expanded).hasSizeGreaterThan(plain.size());
        assertThat(expanded).filteredOn(chunk -> !chunk.isHit()).isNotEmpty().allSatisfy(neighbour -> {
            assertThat(neighbour.vectorScore()).isNull();
            assertThat(neighbour.textScore()).isNull();
            RetrievedChunk hit = plain.stream().filter(h -> h.chunkId().equals(neighbour.neighbourOf())).findFirst().orElseThrow();
            // Context is adjacent in the hit's own document (the store widens within the container
            // section, which can span sub-sections) and carries the hit's rank.
            assertThat(neighbour.provenance().documentId()).isEqualTo(hit.provenance().documentId());
            assertThat(neighbour.rank()).isEqualTo(hit.rank());
        });
        assertThat(expanded).extracting(RetrievedChunk::chunkId).doesNotHaveDuplicates();

        // The width is also a per-query option (Phase 9a needs it per pass, the playground exposes it).
        assertThat(service.search(query.withExpandNeighbours(1)).hits()).isEqualTo(expanded);
        assertThat(expanding.search(query.withExpandNeighbours(0)).hits()).isEqualTo(plain);
    }

    @Test
    void unavailableIndexIsReported() {
        store.close();
        try (LuceneIndexStore other = IndexStores.store(null, new FakeTextEmbedder(8)).open()) {
            RetrievalService empty = new RetrievalService(other, traces, properties(0.5), TestObservations.retrieval());
            assertThat(empty.search(RetrievalQuery.of("anything")).hits()).isEmpty();
        }
        LuceneIndexStore closed = IndexStores.store(null, new FakeTextEmbedder(8));
        RetrievalService unopened = new RetrievalService(closed, traces, properties(0.5), TestObservations.retrieval());
        assertThatThrownBy(() -> unopened.search(RetrievalQuery.of("x"))).isInstanceOf(IndexUnavailableException.class);
    }
}
