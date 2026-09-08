package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.utils.AnswerLanguages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Sinks;

import java.util.concurrent.TimeUnit;

/**
 * Writes the answer draft for the deterministic branch (docs/system-plan.md D10): retrieved evidence
 * in, model draft out; where {@code compareSources} has related the sources, its comparison goes into
 * the prompt with them (Phase 9d). Streams token by token when the caller asked for it and the
 * platform supports it, and otherwise asks for a structured draft; both shapes end up as a
 * {@link GroundedAnswerDraft}, so the verifier cannot tell them apart.
 */
public class AnswerDrafter {

    /** The value the stop publisher carries; only its arrival matters. */
    private static final Object STOP = new Object();

    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final ChatbotProperties.Chat settings;
    private final MeterRegistry meterRegistry;

    public AnswerDrafter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                         ChatbotProperties.Chat settings, MeterRegistry meterRegistry) {
        this.prompt = prompt;
        this.instructions = instructions;
        this.settings = settings;
        this.meterRegistry = meterRegistry;
    }

    public GroundedAnswerDraft draft(Evidence evidence, OperationContext context) {
        UserQuestion question = evidence.question();
        if (evidence.isEmpty()) {
            return noEvidence(question);
        }
        question.notifyStage(AnswerStages.GENERATING);
        question.abortIfCancelled();
        PromptRunner runner = context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()));
        String userPrompt = prompt.build(question.question(), question.history(), evidence.hits(), evidence.comparison());
        long started = System.nanoTime();
        String operation = "draft-answer";
        try {
            AnswerStreamSink sink = question.stream();
            if (sink != null && runner.supportsStreaming()) {
                operation = "draft-answer-stream";
                return streamed(runner.withPromptContributor(instructions.streamingAnswer()), question, userPrompt, sink);
            }
            GroundedAnswerDraft draft = runner.withPromptContributor(instructions.groundedAnswer())
                    .creating(GroundedAnswerDraft.class)
                    .fromPrompt(userPrompt);
            if (sink != null) {
                sink.delta(draft.answer() != null ? draft.answer() : "");
            }
            return draft;
        } finally {
            Timer.builder("chatbot.llm").tag("operation", operation).register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
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
        Sinks.One<Object> stop = Sinks.one();
        question.cancellation().onCancel(() -> stop.tryEmitValue(STOP));
        streaming.withPrompt(userPrompt)
                .generateStream()
                .takeUntilOther(stop.asMono())
                .doOnNext(fragment -> {
                    text.append(fragment);
                    sink.delta(fragment);
                })
                .blockLast();
        question.abortIfCancelled();
        return StreamedDraftParser.parse(text.toString());
    }
}
