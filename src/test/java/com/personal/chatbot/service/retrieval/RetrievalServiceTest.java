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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    @TempDir
    Path dir;

    private LuceneIndexStore store;
    private RetrievalTraceStore traces;
    private RetrievalService service;

    @BeforeEach
    void setUp() throws Exception {
        store = IndexStores.store(null, new FakeTextEmbedder(32)).open();
        traces = new RetrievalTraceStore(50);
        service = new RetrievalService(store, traces, properties(0.3), new SimpleMeterRegistry());
        store.writeDocument(parsed("runbook", "Payments Runbook", TestDocuments.MARKDOWN));
        store.writeDocument(parsed("deploy", "Deployment Guide", DEPLOY_DOC));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ChatbotProperties.Retrieval properties(double sufficientCosine) {
        return new ChatbotProperties.Retrieval(5, 3, 60, -1.0, 0.0, sufficientCosine, 50);
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

        RetrievalService strict = new RetrievalService(store, traces, properties(0.999), new SimpleMeterRegistry());
        assertThat(strict.search(RetrievalQuery.of("systemctl restart payments")).evidenceSufficient()).isFalse();
        assertThat(service.search(new RetrievalQuery("systemctl", null, RetrievalMode.TEXT, null)).evidenceSufficient()).isFalse();
    }

    @Test
    void unavailableIndexIsReported() {
        store.close();
        try (LuceneIndexStore other = IndexStores.store(null, new FakeTextEmbedder(8)).open()) {
            RetrievalService empty = new RetrievalService(other, traces, properties(0.5), new SimpleMeterRegistry());
            assertThat(empty.search(RetrievalQuery.of("anything")).hits()).isEmpty();
        }
        LuceneIndexStore closed = IndexStores.store(null, new FakeTextEmbedder(8));
        RetrievalService unopened = new RetrievalService(closed, traces, properties(0.5), new SimpleMeterRegistry());
        assertThatThrownBy(() -> unopened.search(RetrievalQuery.of("x"))).isInstanceOf(IndexUnavailableException.class);
    }
}
