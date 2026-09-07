package com.personal.chatbot.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.chat.StreamedDraftParser;
import com.personal.chatbot.service.retrieval.RetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The knowledge assistant (docs/system-plan.md §7, D10, D11): a deterministic retrieve → generate → verify
 * flow expressed as Embabel actions so that agentic extensions (query rewriting, expandSearch,
 * compareSources, tools) can be added as further actions without touching the RAG infrastructure (INV-07).
 *
 * <p>Retrieval is a plain service call (INV-01): the model never sees Lucene. The model only turns the
 * numbered evidence into a draft; {@link GroundingVerifier} decides what counts as grounded (INV-03).
 * When the question carries a stream sink and the model supports streaming, the draft is generated as
 * free text and pushed to the sink token by token; otherwise structured output is used.
 */
@Agent(description = "Answers questions from the local knowledge base with verified citations")
public class KnowledgeAssistantAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAssistantAgent.class);
    static final String NO_EVIDENCE_ANSWER = "I could not find anything about this in the knowledge base.";
    public static final String STAGE_RETRIEVING = "retrieving";
    public static final String STAGE_GENERATING = "generating";
    public static final String STAGE_VERIFYING = "verifying";

    private final RetrievalService retrievalService;
    private final GroundedAnswerPrompt prompt;
    private final GroundingVerifier verifier;
    private final ChatbotProperties.Chat settings;
    private final MeterRegistry meterRegistry;

    public KnowledgeAssistantAgent(RetrievalService retrievalService, GroundedAnswerPrompt prompt, GroundingVerifier verifier,
                                   ChatbotProperties properties, MeterRegistry meterRegistry) {
        this.retrievalService = retrievalService;
        this.prompt = prompt;
        this.verifier = verifier;
        this.settings = properties.chat();
        this.meterRegistry = meterRegistry;
    }

    @Action(description = "Retrieve evidence for the question from the knowledge base", readOnly = true)
    public Evidence retrieveEvidence(UserQuestion question) {
        question.notifyStage(STAGE_RETRIEVING);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(question.question(), question.topK(), null, question.documentIds()));
        log.debug("Retrieved {} hits for [{}] (sufficient={})", result.hits().size(), question.messageId(), result.evidenceSufficient());
        return new Evidence(question, result);
    }

    @Action(description = "Draft an answer that uses only the retrieved evidence")
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
                .doOnNext(fragment -> {
                    text.append(fragment);
                    sink.delta(fragment);
                })
                .blockLast();
        return StreamedDraftParser.parse(text.toString());
    }

    @AchievesGoal(description = "A grounded answer whose citations were verified against the evidence")
    @Action(description = "Verify the draft's citations against the evidence and classify grounding", readOnly = true)
    public GroundedAnswer verifyGrounding(Evidence evidence, GroundedAnswerDraft draft) {
        evidence.question().notifyStage(STAGE_VERIFYING);
        GroundedAnswer answer = verifier.verify(evidence, draft, prompt.includedHits(evidence.hits()));
        log.debug("Answer for [{}]: {} with {} citations", evidence.question().messageId(), answer.grounding(), answer.citations().size());
        return answer;
    }
}
