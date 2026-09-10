package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.filter.PropertyFilter;
import com.embabel.agent.rag.tools.SearchDefaults;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.agent.core.support.InvalidLlmReturnFormatException;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.agents.CancellableTool;
import com.personal.chatbot.agents.SearchBudget;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.AnswerAttempt;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.observability.AiOperation;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.service.index.LockedSearchOperations;
import com.personal.chatbot.service.index.ScopedSearchOperations;
import com.personal.chatbot.service.index.SectionCatalog;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.service.retrieval.EvidenceCollector;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.utils.AnswerLanguages;
import com.personal.chatbot.utils.CitationMarkers;
import com.personal.chatbot.utils.CosineScores;
import com.personal.chatbot.utils.Throwables;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agentic research (docs/system-plan.md Phase 9c): the model drives retrieval itself through Embabel
 * {@link ToolishRag} tools over the guarded search operations of the Lucene store, never over Lucene
 * directly (INV-01). Every chunk it is shown is captured by an {@link EvidenceCollector}, so the
 * evidence the answer is verified against is exactly what the model saw (INV-02, INV-03).
 *
 * <p>Produces evidence and draft together, because the evidence only exists once the tool loop ends.
 *
 * <p>The model is free to decide how to search, but not whether to. A tool loop that ends without a
 * single passage — a local model reading a long history often skips the tools and answers from it —
 * is answered by the deterministic retrieval of {@link Retriever} instead, and the answer is written
 * again over those passages. Nothing about verification changes: the citations are checked against
 * the evidence that reached the prompt, whichever of the two produced it (INV-03).
 */
public class AgenticResearcher {

    private static final Logger log = LoggerFactory.getLogger(AgenticResearcher.class);

    /** Also the tool-name prefix: ToolishRag exposes knowledge_base_vectorSearch, knowledge_base_textSearch, ... */
    static final String REFERENCE_NAME = "knowledge_base";
    private static final String TOOL_DESCRIPTION =
            "Search tools over the team's internal documentation. Use them to find passages before answering.";
    private static final String GOAL = "Find passages that answer the question, then stop. Prefer few precise searches over many.";

    private final LockedSearchOperations searchOperations;
    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final RetrievalTraceStore traces;
    private final ChatbotProperties.Chat settings;
    private final ChatbotProperties.Retrieval retrievalSettings;
    private final ChatObservations observations;
    private final QuestionDecomposer decomposer;
    private final SectionCatalog catalog;
    private final Retriever retriever;

    public AgenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                             GroundingInstructions instructions, RetrievalTraceStore traces,
                             ChatbotProperties.Chat settings, ChatbotProperties.Retrieval retrievalSettings,
                             ChatObservations observations, QuestionDecomposer decomposer, SectionCatalog catalog,
                             Retriever retriever) {
        this.retriever = retriever;
        this.searchOperations = searchOperations;
        this.prompt = prompt;
        this.instructions = instructions;
        this.traces = traces;
        this.settings = settings;
        this.retrievalSettings = retrievalSettings;
        this.observations = observations;
        this.decomposer = decomposer;
        this.catalog = catalog;
    }

    /**
     * The whole branch is one AI operation: the seed decomposition, the tool loop, the mapping and the
     * repair below are its parts, not operations of the request in their own right. The narrower window
     * the legacy {@code chatbot.llm} timer measured — the tool loop alone — is marked inside it.
     */
    public AnswerAttempt research(UserQuestion question, OperationContext context) {
        try (Measured research = observations.startAiOperation(AiOperation.RESEARCH_AGENTIC)) {
            try {
                AnswerAttempt attempt = attempt(question, context, research);
                research.succeeded();
                return attempt;
            } catch (Exception e) {
                research.failed(e, question.cancellation().reason());
                throw e;
            }
        }
    }

    private AnswerAttempt attempt(UserQuestion question, OperationContext context, Measured research) {
        question.abortIfCancelled();
        RetrievalResult seed = decomposer.shouldDecompose(question)
                ? decomposer.decompose(question, context).retrieval() : null;
        question.notifyStage(AnswerStages.RESEARCHING);
        AnswerStreamSink sink = question.stream();
        EvidenceCollector collector = collectorFor(sink);
        List<RetrievedChunk> shownSeed = seed == null ? List.of()
                : seed.hits().subList(0, prompt.includedHits(seed.hits()));
        collector.addShownChunks(shownSeed);
        Set<String> scope = question.documentIds() == null || question.documentIds().isEmpty()
                ? null : question.documentIds();
        // The section tools hang off this per-request view rather than off the store's own, so switching
        // them off hands ToolishRag the plain view and it builds exactly the four search tools of Phase 9c.
        ScopedSearchOperations scoped = settings.sectionTools().enabled()
                ? new ScopedSearchOperations(searchOperations, catalog, scope) : null;
        ToolishRag rag = new ToolishRag(REFERENCE_NAME, TOOL_DESCRIPTION, scoped != null ? scoped : searchOperations)
                .withListener(collector)
                // Vector floor in Lucene's (1 + cos) / 2 scale; BM25 keeps Embabel's 0.0, where rank is the
                // only meaningful cutoff. Neighbour expansion is the same corpus decision as in the
                // deterministic branch, so both read chatbot.retrieval.expand-neighbours.
                .withSearchDefaults(new SearchDefaults(CosineScores.toLuceneScore(settings.agenticMinCosine()),
                        SearchDefaults.DEFAULT_TEXT_SIMILARITY_THRESHOLD, retrievalSettings.expandNeighbours()))
                // Embabel would let one readSection return 25000 characters, four times the evidence budget.
                .withMaxReadSectionChars(settings.sectionTools().readCharBudget())
                .withGoal(GOAL);
        if (scope != null) {
            // The document filter of the request, which until now only the deterministic branch honoured.
            // Embabel applies it underneath the search tools, where the model can neither see nor lift it;
            // the section tools are filtered by ScopedSearchOperations instead, because Embabel builds
            // those — and the expansion tools — without a filter.
            rag = rag.withMetadataFilter(new PropertyFilter.In(ProvenanceChunkTransformer.DOCUMENT_ID, List.copyOf(scope)));
        }
        AgenticDraft draft;
        question.abortIfCancelled();
        research.legacyBegins();
        // The searches the model may run are a budget, not a line in the prompt: the instructions name
        // the same number, and this is what holds the model to it.
        SearchBudget budget = new SearchBudget(settings.agenticMaxSearches(), question.messageId(), observations);
        try {
            // withReference(rag) would register the tools twice (deprecated toolObject() plus tools()) under
            // two different prefixes; register the flat tool list and the prompt contribution explicitly.
            draft = context.ai()
                    .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()))
                    .withTools(CancellableTool.wrapAll(budget.limit(rag.tools()), question.cancellation(), question.messageId()))
                    .withPromptContributors(List.of(instructions.agenticResearch(), rag))
                    .creating(AgenticDraft.class)
                    .fromPrompt(prompt.buildForAgentic(question.question(), question.effectiveQuery(), question.history(), shownSeed));
        } catch (InvalidLlmReturnFormatException e) {
            // The same model that answers a deterministic prompt in prose does it here too
            // (docs/eval-log.md), and here the prose cannot be recovered: it references chunk ids that
            // only mean something next to the tool results. What survives is the evidence the tool loop
            // already collected, so the draft is dropped and the answer is written again below over
            // numbered passages — a failed binding is not a failed request.
            log.info("Agentic draft for [{}] did not bind ({}); answering from the collected evidence instead",
                    question.messageId(), Throwables.rootMessage(e));
            observations.agenticDraftUnparsable();
            draft = new AgenticDraft("", List.of(), true, null);
        } finally {
            // The legacy timer stopped here, before the mapping, the fallback and the repair below.
            research.legacyEnds();
        }
        question.abortIfCancelled();

        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(draft, collector);
        List<RetrievedChunk> seen = collector.chunks();
        // An answer about the knowledge base itself rests on the table of contents, not on passages, so
        // it has nothing to cite. It is published only when the model did nothing but read that catalogue:
        // having searched and found nothing is still no licence to answer (INV-03).
        boolean corpusOverview = seen.isEmpty() && scoped != null && scoped.onlyBrowsedCatalogue()
                && numbered.evidenceSufficient() && numbered.answer() != null && !numbered.answer().isBlank();
        // A model that never touched the knowledge base leaves nothing to verify against, and the guard
        // below would throw the whole request away — reliably so on a follow-up question, where it reads
        // its own earlier answer in the history and believes it already knows the corpus. Retrieval is
        // not the model's privilege in this application, so the question is then retrieved the
        // deterministic way (INV-01) and the answer written over those passages by the repair below.
        // A search that came back empty is a finding and is left alone: it stays INSUFFICIENT_EVIDENCE.
        boolean touchedNothing = scoped != null ? scoped.usedNothing() : collector.steps().isEmpty();
        RetrievalResult fallback = null;
        if (seen.isEmpty() && touchedNothing && !corpusOverview) {
            question.notifyStage(AnswerStages.RETRIEVING);
            question.abortIfCancelled();
            fallback = retriever.search(question.retrievalQuery());
            seen = fallback.hits();
            observations.agenticFallback(!seen.isEmpty());
            log.info("Agentic research for [{}] used no knowledge-base tool; deterministic retrieval found {} passages",
                    question.messageId(), seen.size());
        }
        // The fallback result carries a trace of its own, recorded by the retrieval service.
        RetrievalResult trace = fallback != null ? fallback
                : traceOf(question, collector, seen, seed, research.elapsed().toMillis());
        if (fallback == null) {
            traces.record(trace);
        }
        int passagesShown = seen.size();
        if (!seen.isEmpty() && numbered.citedEvidenceOrEmpty().isEmpty()) {
            // Do not publish an uncited tool-loop answer. Re-read the actual evidence in a bounded,
            // numbered prompt; the speculative draft is deliberately not part of that prompt.
            question.notifyStage(AnswerStages.GENERATING);
            question.abortIfCancelled();
            try (Measured repair = observations.startAiOperation(AiOperation.REPAIR_AGENTIC_ANSWER)) {
                try {
                    numbered = context.ai()
                            .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()))
                            .withPromptContributor(instructions.groundedAnswer())
                            .creating(GroundedAnswerDraft.class)
                            .fromPrompt(prompt.build(question.question(), question.history(), seen));
                    repair.succeeded();
                } catch (InvalidLlmReturnFormatException e) {
                    numbered = ProseAnswerRecovery.answerOrRethrow(e);
                    repair.recovered(e, question.cancellation().reason());
                } catch (Exception e) {
                    repair.failed(e, question.cancellation().reason());
                    throw e;
                }
            }
            question.abortIfCancelled();
            passagesShown = prompt.includedHits(seen);
        }
        if (seen.isEmpty() && !corpusOverview && numbered.evidenceSufficient()) {
            // The model answered as if it had passages, and there are none — neither its own nor any the
            // fallback found. Say that, instead of blaming the citations of an answer that never had
            // anything to cite. A model that reported the gap itself keeps its own wording.
            AnswerLanguage language = prompt.languageFor(question.question());
            numbered = GroundedAnswerDraft.insufficient(AnswerLanguages.noEvidenceAnswer(language),
                    AnswerLanguages.noEvidenceNote(language));
        } else if (!corpusOverview && numbered.evidenceSufficient() && !hasValidCitation(numbered, passagesShown)) {
            String missing = prompt.languageFor(question.question()) == AnswerLanguage.RU
                    ? "Не удалось подтвердить ответ ссылками на найденные фрагменты."
                    : "The answer could not be supported with citations to the retrieved passages.";
            numbered = GroundedAnswerDraft.insufficient(missing, missing);
        }
        if (sink != null) {
            sink.delta(numbered.answer()); // the whole answer at once, with [n] markers like the deterministic stream
        }
        log.info("Agentic research for [{}]: {} tool searches, {} seed passages, {} distinct chunks, tools={}, "
                        + "sufficient={}{}", question.messageId(), collector.steps().size(), shownSeed.size(), seen.size(),
                scoped != null ? scoped.toolsUsed() : "search only", numbered.evidenceSufficient(),
                fallback != null ? ", retrieved deterministically" : "");
        return new AnswerAttempt(new Evidence(question, trace), numbered, passagesShown, corpusOverview);
    }

    private static boolean hasValidCitation(GroundedAnswerDraft draft, int passagesShown) {
        var cited = CitationMarkers.collect(draft.answer() == null ? "" : draft.answer());
        cited.addAll(draft.citedEvidenceOrEmpty());
        return cited.stream().anyMatch(n -> n >= 1 && n <= passagesShown);
    }

    private RetrievalResult traceOf(UserQuestion question, EvidenceCollector collector, List<RetrievedChunk> seen,
                                   @Nullable RetrievalResult seed, long totalMs) {
        String query = collector.steps().stream().map(EvidenceCollector.SearchStep::query)
                .reduce((a, b) -> a + " | " + b).orElse("");
        query = seed == null ? (query.isEmpty() ? question.question() : query)
                : seed.query() + (query.isEmpty() ? "" : " | " + query);
        // Scores come from mixed tools, so sufficiency is decided by the model, not by a cosine floor.
        double maxCosine = seen.isEmpty() ? -1 : 1.0;
        int seedSearches = seed == null ? 0 : seed.decomposition() == null ? 1
                : seed.decomposition().subQuestions().size() + 1;
        return new RetrievalResult(UUID.randomUUID().toString(), query, RetrievalMode.HYBRID, seen.size(),
                seedSearches + collector.steps().size(), seen, !seen.isEmpty(), maxCosine,
                new RetrievalTimings(0, 0, 0, totalMs), Instant.now(), null,
                seed == null ? null : seed.decomposition());
    }

    /**
     * Narrates every tool call to a streaming client: the tool loop is otherwise silent for as long as
     * the model takes, and the final answer arrives in one piece (no token streaming here).
     */
    private static EvidenceCollector collectorFor(@Nullable AnswerStreamSink sink) {
        if (sink == null) {
            return new EvidenceCollector();
        }
        AtomicInteger searches = new AtomicInteger();
        return new EvidenceCollector(step -> sink.stage(AnswerStages.RESEARCHING,
                "search %d: \"%s\" (%d passages)".formatted(searches.incrementAndGet(), step.query(), step.results())));
    }
}
