package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.agent.core.support.InvalidLlmReturnFormatException;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.observability.AiOperation;
import com.personal.chatbot.observability.ExecutionContext;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.utils.AnswerLanguages;
import reactor.core.publisher.Sinks;

/**
 * Writes the answer draft for the deterministic branch (docs/system-plan.md D10): retrieved evidence
 * in, model draft out; where {@code compareSources} has related the sources, its comparison goes into
 * the prompt with them (Phase 9d). Streams token by token when the caller asked for it and the
 * platform supports it, and otherwise asks for a structured draft; both shapes end up as a
 * {@link GroundedAnswerDraft}, so the verifier cannot tell them apart.
 *
 * <p>A model that writes the answer as prose where JSON was asked for has not failed to answer, and
 * {@link ProseAnswerRecovery} keeps that answer rather than spending further generations on the same
 * question.
 */
public class AnswerDrafter {

    /** The value the stop publisher carries; only its arrival matters. */
    private static final Object STOP = new Object();

    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final ChatbotProperties.Chat settings;
    private final ChatObservations observations;

    public AnswerDrafter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                         ChatbotProperties.Chat settings, ChatObservations observations) {
        this.prompt = prompt;
        this.instructions = instructions;
        this.settings = settings;
        this.observations = observations;
    }

    public GroundedAnswerDraft draft(Evidence evidence, OperationContext context) {
        UserQuestion question = evidence.question();
        if (evidence.isEmpty()) {
            // The application writes this one itself, so there is no AI operation to measure.
            return noEvidence(question);
        }
        question.notifyStage(AnswerStages.GENERATING);
        question.abortIfCancelled();
        try (Measured operation = observations.startAiOperation(AiOperation.DRAFT_ANSWER)) {
            try {
                PromptRunner runner = context.ai()
                        .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()));
                String userPrompt = prompt.build(question.question(), question.history(), evidence.hits(),
                        evidence.comparison());
                operation.legacyBegins(); // where chatbot.llm has always started: after the prompt is built
                AnswerStreamSink sink = question.stream();
                if (sink != null && runner.supportsStreaming()) {
                    operation.operation(AiOperation.DRAFT_ANSWER_STREAM);
                    GroundedAnswerDraft streamed = streamed(
                            runner.withPromptContributor(instructions.streamingAnswer()), question, userPrompt, sink);
                    operation.succeeded();
                    return streamed;
                }
                GroundedAnswerDraft draft;
                try {
                    draft = runner.withPromptContributor(instructions.groundedAnswer())
                            .creating(GroundedAnswerDraft.class)
                            .fromPrompt(userPrompt);
                } catch (InvalidLlmReturnFormatException e) {
                    // Prose where JSON was asked for is still an answer; recovering it is not a failure
                    // of the request, but it is not the operation working as intended either.
                    draft = ProseAnswerRecovery.answerOrRethrow(e);
                    operation.recovered(e, question.cancellation().telemetryReason());
                }
                if (sink != null) {
                    sink.delta(draft.answer() != null ? draft.answer() : "");
                }
                operation.succeeded(); // ignored when the draft above had to be recovered
                return draft;
            } catch (Exception e) {
                operation.failed(e, question.cancellation().telemetryReason());
                throw e;
            }
        }
    }

    /** How many of the evidence hits fit the prompt budget and may therefore be cited. */
    public int passagesShown(Evidence evidence) {
        return prompt.includedHits(evidence.hits());
    }

    /** Written by the application, not by the model, so it follows the question's language itself. */
    private GroundedAnswerDraft noEvidence(UserQuestion question) {
        AnswerLanguage language = prompt.languageFor(question.question());
        GroundedAnswerDraft draft = GroundedAnswerDraft.insufficient(
                AnswerLanguages.noEvidenceAnswer(language), AnswerLanguages.noEvidenceNote(language));
        AnswerStreamSink sink = question.stream();
        if (sink != null) {
            sink.stage(AnswerStages.GENERATING);
            sink.delta(draft.answer());
        }
        return draft;
    }

    /**
     * Cancelling the Flux closes the streaming call to the model, so an abandoned request stops
     * costing tokens as soon as the disconnect is noticed. The stop arrives as a publisher of its
     * own rather than as a test on the next fragment: a model that has gone quiet would otherwise
     * hold the subscription — and this thread — until it decided to speak again.
     */
    private GroundedAnswerDraft streamed(PromptRunner runner, UserQuestion question, String userPrompt,
                                         AnswerStreamSink sink) {
        StringBuilder text = new StringBuilder();
        StreamingPromptRunner.Streaming streaming = (StreamingPromptRunner.Streaming) runner.streaming();
        ExecutionContext context = ExecutionContext.capture();
        Sinks.One<Object> stop = Sinks.one();
        question.cancellation().onCancel(context.wrap(() -> stop.tryEmitValue(STOP)));
        streaming.withPrompt(userPrompt)
                .generateStream()
                .takeUntilOther(stop.asMono())
                .doOnNext(fragment -> context.wrap(() -> {
                    text.append(fragment);
                    sink.delta(fragment);
                }).run())
                .contextWrite(context::reactor)
                .blockLast();
        question.abortIfCancelled();
        return StreamedDraftParser.parse(text.toString());
    }
}
