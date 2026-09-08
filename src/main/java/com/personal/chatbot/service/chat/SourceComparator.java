package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.SourceComparison;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The {@code compareSources} branch of the assistant (docs/system-plan.md Phase 9d): when a question
 * asks how two things relate and the evidence comes from several documents, the sources are compared
 * before the answer is written.
 *
 * <p>Retrieval returns passages in relevance order, and a model asked to answer straight from them
 * tends to follow the strongest one and quietly drop what the second document says — worst of all
 * where the two disagree, which is exactly what the reader needs to know. This branch reads the same
 * numbered passages once and comes back with what they say per aspect and where they conflict; the
 * result goes into the answer prompt as structure.
 *
 * <p>It adds no facts: every aspect must point at passages the model was shown, references outside
 * them are dropped ({@link SourceComparison#limitedTo}), and the answer is still verified against the
 * evidence (INV-03). A failed comparison leaves the evidence marked as compared and otherwise
 * untouched, so the answer is written exactly as it would have been without the branch.
 */
public class SourceComparator {

    private static final Logger log = LoggerFactory.getLogger(SourceComparator.class);

    /** Questions that ask for two things to be held against each other, rather than for one fact. */
    private static final Pattern COMPARING = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:compare|compared|comparison|difference|differences|differ|differs|"
                    + "different|versus|vs|contrast|instead of|rather than|"
                    + "сравни\\p{L}*|сравнен\\p{L}*|разниц\\p{L}*|отлича\\p{L}*|отличи\\p{L}*|различ\\p{L}*|вместо)"
                    + "(?![\\p{L}\\p{N}_])");

    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final ChatbotProperties.Chat settings;
    private final MeterRegistry meterRegistry;

    public SourceComparator(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                            ChatbotProperties.Chat settings, MeterRegistry meterRegistry) {
        this.prompt = prompt;
        this.instructions = instructions;
        this.settings = settings;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Whether the sources of this evidence are worth comparing: the branch is on, the question asks
     * for a comparison, the passages the model will actually see come from at least two documents,
     * and they have not been compared yet — so the branch runs at most once per question.
     */
    public boolean shouldCompare(Evidence evidence) {
        if (!settings.compareSources().enabled() || evidence.compared() || evidence.isEmpty()) {
            return false;
        }
        return COMPARING.matcher(evidence.question().question()).find()
                && documentsShown(evidence) >= settings.compareSources().minDocuments();
    }

    /** @return the same evidence, always marked as compared, with the comparison when there is one */
    public Evidence compare(Evidence evidence, OperationContext context) {
        UserQuestion question = evidence.question();
        question.abortIfCancelled();
        question.notifyStage(AnswerStages.COMPARING);
        long started = System.nanoTime();
        String outcome = "failed";
        try {
            SourceComparison drafted = context.ai()
                    .withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()))
                    .withPromptContributor(instructions.sourceComparison())
                    .creating(SourceComparison.class)
                    .fromPrompt(prompt.buildForComparison(question.question(), evidence.hits()));
            question.abortIfCancelled();
            SourceComparison comparison = drafted == null ? SourceComparison.none()
                    : drafted.limitedTo(prompt.includedHits(evidence.hits()), settings.compareSources().maxAspects());
            outcome = comparison.isEmpty() ? "none" : comparison.hasConflict() ? "conflict" : "agreement";
            log.debug("Compared {} sources for [{}]: {}", documentsShown(evidence), question.messageId(),
                    comparison.aspectsOrEmpty());
            return evidence.withComparison(comparison);
        } catch (Exception e) {
            if (ChatCancelledException.isCancellation(e)) {
                throw new ChatCancelledException(question.messageId(), "cancelled while comparing the sources");
            }
            question.abortIfCancelled();
            log.warn("Comparing the sources of [{}] failed; answering from the passages alone: {}",
                    question.messageId(), e.toString());
            // Marked all the same: an unmarked evidence would send the planner back into this branch.
            return evidence.withComparison(SourceComparison.none());
        } finally {
            Timer.builder("chatbot.llm").tag("operation", "compare-sources").register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            meterRegistry.counter("chatbot.chat.comparison", "outcome", outcome).increment();
        }
    }

    /** Documents behind the passages that fit the prompt budget: the ones the model can compare. */
    private long documentsShown(Evidence evidence) {
        List<RetrievedChunk> hits = evidence.hits();
        return hits.subList(0, prompt.includedHits(hits)).stream()
                .map(hit -> hit.provenance().documentId()).distinct().count();
    }
}
