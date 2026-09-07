package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.AnswerStreamSink;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Writes the answer draft for the deterministic branch (docs/system-plan.md D10): retrieved evidence
 * in, model draft out. Streams token by token when the caller asked for it and the platform supports
 * it, and otherwise asks for a structured draft; both shapes end up as a {@link GroundedAnswerDraft},
 * so the verifier cannot tell them apart.
 */
public class AnswerDrafter {

    static final String NO_EVIDENCE_ANSWER = "I could not find anything about this in the knowledge base.";

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
        PromptRunner runner = context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()));
        String userPrompt = prompt.build(question.question(), question.history(), evidence.hits());
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

    private GroundedAnswerDraft noEvidence(UserQuestion question) {
        GroundedAnswerDraft draft = GroundedAnswerDraft.insufficient(NO_EVIDENCE_ANSWER, "No relevant passages were retrieved.");
        AnswerStreamSink sink = question.stream();
        if (sink != null) {
            sink.stage(AnswerStages.GENERATING);
            sink.delta(draft.answer());
        }
        return draft;
    }

    private GroundedAnswerDraft streamed(PromptRunner runner, UserQuestion question, String userPrompt,
                                         @Nullable AnswerStreamSink sink) {
        StringBuilder text = new StringBuilder();
        StreamingPromptRunner.Streaming streaming = (StreamingPromptRunner.Streaming) runner.streaming();
        streaming.withPrompt(userPrompt)
                .generateStream()
                // Cancelling the Flux closes the streaming call to the model, so an abandoned request
                // stops costing tokens as soon as the client disconnect is noticed.
                .takeWhile(_ -> sink == null || !sink.cancelled())
                .doOnNext(fragment -> {
                    text.append(fragment);
                    if (sink != null) {
                        sink.delta(fragment);
                    }
                })
                .blockLast();
        if (sink != null && sink.cancelled()) {
            throw new ChatCancelledException(question.messageId());
        }
        return StreamedDraftParser.parse(text.toString());
    }
}
