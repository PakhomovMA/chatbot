package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.HypotheticalPassage;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.observability.AiOperation;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.service.retrieval.SearchExpander;
import com.personal.chatbot.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code expandSearch} branch of the assistant (docs/system-plan.md Phase 9a): decides that the
 * first retrieval is too weak to answer from, produces wider queries for the configured strategy and
 * lets {@link SearchExpander} search and merge them.
 *
 * <p>Only the query text is the model's work here, and it is used for nothing else: REWRITE asks for
 * other ways to ask the question, HYDE for the passage that would answer it (invented on purpose),
 * NEIGHBOURS asks nothing and simply reads a wider window around the same hits. Evidence still comes
 * from deterministic retrieval (INV-01), and the answer is still verified against it (INV-03).
 *
 * <p>The branch is best-effort: if the model call fails, the question is answered from the evidence
 * the first pass found, which is what would have happened without the branch at all.
 */
public class EvidenceExpander {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExpander.class);

    private final SearchExpander expander;
    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final ChatbotProperties.Chat settings;
    private final ChatObservations observations;
    private final RetrievalObservations retrievalObservations;

    public EvidenceExpander(SearchExpander expander, GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                            ChatbotProperties.Chat settings, ChatObservations observations,
                            RetrievalObservations retrievalObservations) {
        this.expander = expander;
        this.prompt = prompt;
        this.instructions = instructions;
        this.settings = settings;
        this.observations = observations;
        this.retrievalObservations = retrievalObservations;
    }

    /**
     * Whether the question should be searched again before it is answered. False whenever the branch
     * is switched off, the evidence already clears the sufficiency floor, or the search was widened
     * once already — the second pass runs at most once per question.
     */
    public boolean shouldExpand(Evidence evidence) {
        return settings.expandSearch().enabled() && expander.worthExpanding(evidence.retrieval());
    }

    /** @return evidence merged from both passes; always marked as expanded, even when nothing was added */
    public Evidence expand(Evidence evidence, OperationContext context) {
        UserQuestion question = evidence.question();
        ExpansionStrategy strategy = settings.expandSearch().strategy();
        question.abortIfCancelled();
        question.notifyStage(AnswerStages.EXPANDING);
        long started = System.nanoTime();
        List<String> queries = queriesFor(strategy, question, context);
        long buildMs = (System.nanoTime() - started) / 1_000_000;
        RetrievalResult widened = expander.expand(question.retrievalQuery(), evidence.retrieval(), strategy, queries, buildMs);
        retrievalObservations.expansion(strategy, widened.evidenceSufficient());
        return new Evidence(question, widened);
    }

    private List<String> queriesFor(ExpansionStrategy strategy, UserQuestion question, OperationContext context) {
        try {
            return switch (strategy) {
                case NONE -> List.of();
                case NEIGHBOURS -> List.of(question.effectiveQuery());
                case REWRITE -> rewrite(question, context);
                case HYDE -> hypothetical(question, context);
            };
        } catch (Exception e) {
            // A failed widening must not cost the answer: fall back to what the first pass found.
            log.warn("Query expansion ({}) failed for [{}]: {}", strategy, question.messageId(), e.toString());
            return List.of();
        }
    }

    private List<String> rewrite(UserQuestion question, OperationContext context) {
        return observed(AiOperation.EXPAND_SEARCH_REWRITE, question, () -> {
            RewrittenQueries rewritten = runner(context).withPromptContributor(instructions.queryRewrite())
                    .creating(RewrittenQueries.class)
                    .fromPrompt(prompt.buildForExpansion(question.effectiveQuery()));
            List<String> queries = rewritten.queriesOrEmpty().stream()
                    .filter(q -> q != null && !q.isBlank())
                    .map(String::strip)
                    .filter(q -> !q.equalsIgnoreCase(question.effectiveQuery().strip()))
                    .limit(settings.expandSearch().queries())
                    .toList();
            log.debug("Rewrote [{}] into {}", question.messageId(), queries);
            return queries;
        });
    }

    private List<String> hypothetical(UserQuestion question, OperationContext context) {
        return observed(AiOperation.EXPAND_SEARCH_HYDE, question, () -> {
            HypotheticalPassage passage = runner(context).withPromptContributor(instructions.hypotheticalPassage())
                    .creating(HypotheticalPassage.class)
                    .fromPrompt(prompt.buildForExpansion(question.effectiveQuery()));
            if (passage.passage() == null || passage.passage().isBlank()) {
                return List.of();
            }
            log.debug("Hypothetical passage for [{}]: {}", question.messageId(), Texts.singleLine(passage.passage(), 200));
            return List.of(passage.passage());
        });
    }

    private PromptRunner runner(OperationContext context) {
        return context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(settings.temperature()));
    }

    /**
     * The model call of a widening strategy, together with the validation of what it came back with.
     * Its caller answers from the first pass when this fails, so a failure here is a fallback rather
     * than a failed request — unless the request itself was abandoned, which is what actually ended
     * the call then.
     */
    private <T> T observed(AiOperation operation, UserQuestion question, Supplier<T> call) {
        try (Measured measured = observations.startAiOperation(operation)) {
            try {
                T result = call.get();
                measured.succeeded();
                return result;
            } catch (Exception e) {
                measured.recovered(e, question.cancellation().reason());
                throw e;
            }
        }
    }
}
