package com.personal.chatbot.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.agent.rag.tools.SearchDefaults;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.chat.AgenticDraftMapper;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.chat.StreamedDraftParser;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.retrieval.EvidenceCollector;
import com.personal.chatbot.service.retrieval.RetrievalService;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The knowledge assistant (docs/system-plan.md §7, D10, D11, Phase 9c). Two ways to reach the goal,
 * chosen by the {@code agenticMode} / {@code deterministicMode} conditions:
 * <ul>
 *   <li><b>deterministic</b>: {@code retrieveEvidence} (one hybrid retrieval, INV-01) → {@code draftAnswer}
 *       (structured or streamed) → {@code verifyGrounding};</li>
 *   <li><b>agentic</b>: {@code researchIteratively} lets the model search the knowledge base itself through
 *       Embabel {@link ToolishRag} over the same Lucene store; every chunk it sees is captured by an
 *       {@link EvidenceCollector}, so {@code verifyAgenticGrounding} applies the same citation check.</li>
 * </ul>
 * Retrieval infrastructure is untouched in both modes (INV-06, INV-07); the model never reaches Lucene
 * except through the store's guarded search operations.
 */
@Agent(description = "Answers questions from the local knowledge base with verified citations")
public class KnowledgeAssistantAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAssistantAgent.class);
    static final String NO_EVIDENCE_ANSWER = "I could not find anything about this in the knowledge base.";
    public static final String STAGE_RETRIEVING = "retrieving";
    public static final String STAGE_GENERATING = "generating";
    public static final String STAGE_RESEARCHING = "researching";
    public static final String STAGE_VERIFYING = "verifying";
    static final String AGENTIC_CONDITION = "agenticMode";
    static final String DETERMINISTIC_CONDITION = "deterministicMode";
    /** Also the tool-name prefix: ToolishRag exposes knowledge_base_vectorSearch, knowledge_base_textSearch, ... */
    static final String REFERENCE_NAME = "knowledge_base";

    private final RetrievalService retrievalService;
    private final LuceneIndexStore indexStore;
    private final RetrievalTraceStore traces;
    private final GroundedAnswerPrompt prompt;
    private final GroundingVerifier verifier;
    private final ChatbotProperties.Chat settings;
    private final MeterRegistry meterRegistry;

    public KnowledgeAssistantAgent(RetrievalService retrievalService, LuceneIndexStore indexStore, RetrievalTraceStore traces,
                                   GroundedAnswerPrompt prompt, GroundingVerifier verifier, ChatbotProperties properties,
                                   MeterRegistry meterRegistry) {
        this.retrievalService = retrievalService;
        this.indexStore = indexStore;
        this.traces = traces;
        this.prompt = prompt;
        this.verifier = verifier;
        this.settings = properties.chat();
        this.meterRegistry = meterRegistry;
    }

    // Two explicit conditions instead of a negated expression: Embabel's default expression parser
    // is empty, so precondition strings are plain condition names.
    @Condition(name = AGENTIC_CONDITION)
    public boolean agenticMode(UserQuestion question) {
        return question.mode() == AnswerMode.AGENTIC;
    }

    @Condition(name = DETERMINISTIC_CONDITION)
    public boolean deterministicMode(UserQuestion question) {
        return question.mode() != AnswerMode.AGENTIC;
    }

    // ---- deterministic path ----------------------------------------------------------------------

    @Action(description = "Retrieve evidence for the question from the knowledge base", readOnly = true, pre = DETERMINISTIC_CONDITION)
    public Evidence retrieveEvidence(UserQuestion question) {
        question.notifyStage(STAGE_RETRIEVING);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(question.question(), question.topK(), null, question.documentIds()));
        log.debug("Retrieved {} hits for [{}] (sufficient={})", result.hits().size(), question.messageId(), result.evidenceSufficient());
        return new Evidence(question, result);
    }

    @Action(description = "Draft an answer that uses only the retrieved evidence", pre = DETERMINISTIC_CONDITION)
    public GroundedAnswerDraft draftAnswer(Evidence evidence, OperationContext context) {
        UserQuestion question = evidence.question();
        if (evidence.isEmpty()) {
            GroundedAnswerDraft draft = GroundedAnswerDraft.insufficient(NO_EVIDENCE_ANSWER, "No relevant passages were retrieved.");
            AnswerStreamSink sink = question.stream();
            if (sink != null) {
                sink.stage(STAGE_GENERATING);
                sink.delta(draft.answer());
            }
            return draft;
        }
        question.notifyStage(STAGE_GENERATING);
        PromptRunner runner = context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()));
        long started = System.nanoTime();
        String operation = "draft-answer";
        try {
            AnswerStreamSink sink = question.stream();
            if (sink != null && runner.supportsStreaming()) {
                operation = "draft-answer-stream";
                return streamDraft(runner, question, evidence, sink);
            }
            GroundedAnswerDraft draft = runner.creating(GroundedAnswerDraft.class)
                    .fromPrompt(prompt.build(question.question(), question.history(), evidence.hits()));
            if (sink != null) {
                sink.delta(draft.answer() != null ? draft.answer() : "");
            }
            return draft;
        } finally {
            Timer.builder("chatbot.llm").tag("operation", operation).register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private GroundedAnswerDraft streamDraft(PromptRunner runner, UserQuestion question, Evidence evidence, AnswerStreamSink sink) {
        StringBuilder text = new StringBuilder();
        StreamingPromptRunner.Streaming streaming = (StreamingPromptRunner.Streaming) runner.streaming();
        streaming.withPrompt(prompt.buildForStreaming(question.question(), question.history(), evidence.hits()))
                .generateStream()
                // Cancelling the Flux closes the streaming call to the model, so an abandoned request
                // stops costing tokens as soon as the client disconnect is noticed.
                .takeWhile(_ -> !sink.cancelled())
                .doOnNext(fragment -> {
                    text.append(fragment);
                    sink.delta(fragment);
                })
                .blockLast();
        if (sink.cancelled()) {
            throw new ChatCancelledException(question.messageId());
        }
        return StreamedDraftParser.parse(text.toString());
    }

    // ---- agentic path (ToolishRag) -----------------------------------------------------------------

    /**
     * Agentic RAG: the model drives retrieval through {@link ToolishRag} tools. Produces both the evidence
     * (everything the model saw, via {@link EvidenceCollector}) and the draft in one action, because the
     * evidence only exists after the tool loop has finished.
     */
    @Action(description = "Research the question with the knowledge-base search tools and draft an answer", pre = AGENTIC_CONDITION)
    public AgenticResearch researchIteratively(UserQuestion question, OperationContext context) {
        question.notifyStage(STAGE_RESEARCHING);
        long started = System.nanoTime();
        AnswerStreamSink sink = question.stream();
        BooleanSupplier cancelled = sink != null ? sink::cancelled : () -> false;
        AtomicInteger searches = new AtomicInteger();
        // Narrate every tool call to a streaming client: the tool loop is otherwise silent for as long
        // as the model takes, and the final answer arrives in one piece (no token streaming here).
        EvidenceCollector collector = new EvidenceCollector(step -> {
            if (sink != null) {
                sink.stage(STAGE_RESEARCHING, "search %d: \"%s\" (%d passages)".formatted(searches.incrementAndGet(), step.query(), step.results()));
            }
        });
        ToolishRag rag = new ToolishRag(REFERENCE_NAME,
                "Search tools over the team's internal documentation. Use them to find passages before answering.",
                indexStore.searchOperations())
                .withListener(collector)
                .withSearchDefaults(new SearchDefaults(RetrievalService.toLuceneScore(settings.agenticMinCosine()), 0.0, 0))
                .withGoal("Find passages that answer the question, then stop. Prefer few precise searches over many.");
        AgenticDraft draft;
        try {
            // withReference(rag) would register the tools twice (deprecated toolObject() plus tools()) under
            // two different prefixes; register the flat tool list and the prompt contribution explicitly.
            draft = context.ai()
                    .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()))
                    .withTools(CancellableTool.wrapAll(rag.tools(), cancelled, question.messageId()))
                    .withPromptContributor(rag)
                    .creating(AgenticDraft.class)
                    .fromPrompt(prompt.buildForAgentic(question.question(), question.history(), settings.agenticMaxSearches()));
        } finally {
            Timer.builder("chatbot.llm").tag("operation", "research-agentic").register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        if (cancelled.getAsBoolean()) {
            throw new ChatCancelledException(question.messageId());
        }
        List<RetrievedChunk> seen = collector.chunks();
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        double maxCosine = seen.isEmpty() ? -1 : 1.0; // scores come from mixed tools; sufficiency is decided by the model
        RetrievalResult trace = new RetrievalResult(UUID.randomUUID().toString(),
                collector.steps().stream().map(EvidenceCollector.SearchStep::query).reduce((a, b) -> a + " | " + b).orElse(question.question()),
                RetrievalMode.HYBRID, seen.size(), collector.steps().size(), seen, !seen.isEmpty(), maxCosine,
                new RetrievalTimings(0, 0, 0, totalMs), Instant.now());
        traces.record(trace);
        GroundedAnswerDraft numbered = AgenticDraftMapper.toNumbered(draft, collector);
        if (sink != null) {
            sink.delta(numbered.answer()); // the whole answer at once, with [n] markers like the deterministic stream
        }
        log.info("Agentic research for [{}]: {} searches, {} distinct chunks, sufficient={}", question.messageId(),
                collector.steps().size(), seen.size(), draft.evidenceSufficient());
        return new AgenticResearch(new Evidence(question, trace), numbered);
    }

    /**
     * Evidence and draft produced together by the tool loop. A dedicated type (rather than two
     * blackboard objects) because GOAP plans from declared effects: the planner must see one action
     * whose output satisfies the goal action's input.
     */
    public record AgenticResearch(Evidence evidence, GroundedAnswerDraft draft) {
    }

    @AchievesGoal(description = "A grounded answer whose citations were verified against the evidence the model retrieved itself")
    @Action(description = "Verify the researched draft's citations against everything the model saw", readOnly = true, pre = AGENTIC_CONDITION)
    public GroundedAnswer verifyAgenticGrounding(AgenticResearch research) {
        Evidence evidence = research.evidence();
        evidence.question().notifyStage(STAGE_VERIFYING);
        GroundedAnswer answer = verifier.verify(evidence, research.draft(), evidence.hits().size());
        log.debug("Agentic answer for [{}]: {} with {} citations", evidence.question().messageId(), answer.grounding(), answer.citations().size());
        return answer;
    }

    // ---- shared verification -----------------------------------------------------------------------

    @AchievesGoal(description = "A grounded answer whose citations were verified against the evidence")
    @Action(description = "Verify the draft's citations against the evidence and classify grounding", readOnly = true, pre = DETERMINISTIC_CONDITION)
    public GroundedAnswer verifyGrounding(Evidence evidence, GroundedAnswerDraft draft) {
        evidence.question().notifyStage(STAGE_VERIFYING);
        GroundedAnswer answer = verifier.verify(evidence, draft, prompt.includedHits(evidence.hits()));
        log.debug("Answer for [{}]: {} with {} citations", evidence.question().messageId(), answer.grounding(), answer.citations().size());
        return answer;
    }
}
