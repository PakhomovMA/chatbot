package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.rag.tools.SearchDefaults;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.agents.CancellableTool;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.AnswerAttempt;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.index.LockedSearchOperations;
import com.personal.chatbot.service.retrieval.EvidenceCollector;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.utils.CosineScores;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Agentic research (docs/system-plan.md Phase 9c): the model drives retrieval itself through Embabel
 * {@link ToolishRag} tools over the guarded search operations of the Lucene store, never over Lucene
 * directly (INV-01). Every chunk it is shown is captured by an {@link EvidenceCollector}, so the
 * evidence the answer is verified against is exactly what the model saw (INV-02, INV-03).
 *
 * <p>Produces evidence and draft together, because the evidence only exists once the tool loop ends.
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
    private final MeterRegistry meterRegistry;

    public AgenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                             GroundingInstructions instructions, RetrievalTraceStore traces,
                             ChatbotProperties.Chat settings, ChatbotProperties.Retrieval retrievalSettings,
                             MeterRegistry meterRegistry) {
        this.searchOperations = searchOperations;
        this.prompt = prompt;
        this.instructions = instructions;
        this.traces = traces;
        this.settings = settings;
        this.retrievalSettings = retrievalSettings;
        this.meterRegistry = meterRegistry;
    }

    public AnswerAttempt research(UserQuestion question, OperationContext context) {
        question.notifyStage(AnswerStages.RESEARCHING);
        long started = System.nanoTime();
        AnswerStreamSink sink = question.stream();
        BooleanSupplier cancelled = sink != null ? sink::cancelled : () -> false;
        EvidenceCollector collector = collectorFor(sink);
        ToolishRag rag = new ToolishRag(REFERENCE_NAME, TOOL_DESCRIPTION, searchOperations)
                .withListener(collector)
                // Vector floor in Lucene's (1 + cos) / 2 scale; BM25 keeps Embabel's 0.0, where rank is the
                // only meaningful cutoff. Neighbour expansion is the same corpus decision as in the
                // deterministic branch, so both read chatbot.retrieval.expand-neighbours.
                .withSearchDefaults(new SearchDefaults(CosineScores.toLuceneScore(settings.agenticMinCosine()),
                        SearchDefaults.DEFAULT_TEXT_SIMILARITY_THRESHOLD, retrievalSettings.expandNeighbours()))
                .withGoal(GOAL);
        AgenticDraft draft;
        try {
            // withReference(rag) would register the tools twice (deprecated toolObject() plus tools()) under
            // two different prefixes; register the flat tool list and the prompt contribution explicitly.
            draft = context.ai()
                    .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()))
                    .withTools(CancellableTool.wrapAll(rag.tools(), cancelled, question.messageId()))
                    .withPromptContributors(List.of(instructions.agenticResearch(), rag))
                    .creating(AgenticDraft.class)
                    .fromPrompt(prompt.buildForAgentic(question.question(), question.history()));
        } finally {
            Timer.builder("chatbot.llm").tag("operation", "research-agentic").register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        if (cancelled.getAsBoolean()) {
            throw new ChatCancelledException(question.messageId());
        }

        List<RetrievedChunk> seen = collector.chunks();
        RetrievalResult trace = traceOf(question, collector, seen, (System.nanoTime() - started) / 1_000_000);
        traces.record(trace);
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(draft, collector);
        if (sink != null) {
            sink.delta(numbered.answer()); // the whole answer at once, with [n] markers like the deterministic stream
        }
        log.info("Agentic research for [{}]: {} searches, {} distinct chunks, sufficient={}", question.messageId(),
                collector.steps().size(), seen.size(), draft.evidenceSufficient());
        // Everything the model saw is evidence, so all of it counts as shown passages.
        return new AnswerAttempt(new Evidence(question, trace), numbered, seen.size());
    }

    private RetrievalResult traceOf(UserQuestion question, EvidenceCollector collector, List<RetrievedChunk> seen, long totalMs) {
        String query = collector.steps().stream().map(EvidenceCollector.SearchStep::query)
                .reduce((a, b) -> a + " | " + b).orElse(question.question());
        // Scores come from mixed tools, so sufficiency is decided by the model, not by a cosine floor.
        double maxCosine = seen.isEmpty() ? -1 : 1.0;
        return new RetrievalResult(UUID.randomUUID().toString(), query, RetrievalMode.HYBRID, seen.size(),
                collector.steps().size(), seen, !seen.isEmpty(), maxCosine,
                new RetrievalTimings(0, 0, 0, totalMs), Instant.now());
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
