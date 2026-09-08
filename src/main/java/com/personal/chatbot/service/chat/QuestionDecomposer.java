package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.SubQuestions;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The {@code decomposeQuestion} branch of the assistant (docs/system-plan.md Phase 9d): a question
 * that asks for several things is split into its parts, each part is retrieved on its own, and the
 * passes are merged into one evidence list.
 *
 * <p>Asking such a question as a single query makes its parts compete for the same candidate budget,
 * and the part the corpus covers less well loses: the evidence then answers half the question
 * convincingly and says nothing about the rest. Only the split is the model's work here, and the
 * parts are used for nothing but search text — evidence still comes from deterministic retrieval
 * (INV-01) and the answer is still verified against it (INV-03).
 *
 * <p>Like every other model-driven branch this one is best-effort: a failed or useless split leaves
 * the question searched once, which is what would have happened without the branch at all.
 */
public class QuestionDecomposer {

    private static final Logger log = LoggerFactory.getLogger(QuestionDecomposer.class);

    /**
     * Words that join the parts of a question or ask for two things to be held against each other.
     * A cheap gate in front of the model call, in the spirit of the conversation-rewrite gate: it may
     * fire on a question that turns out to be single, and the model then returns no parts.
     */
    private static final Pattern SEVERAL_PARTS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:and|as well as|both|plus|versus|vs|compare|compared|comparison|"
                    + "difference|differences|differ|differs|between|"
                    + "и|а также|также|плюс|сравни\\p{L}*|сравнен\\p{L}*|разниц\\p{L}*|отлича\\p{L}*|отличи\\p{L}*|между)"
                    + "(?![\\p{L}\\p{N}_])");

    /** Below this a question is too short to be asking for two things, whatever words it uses. */
    private static final int SHORTEST_COMPOSITE_WORDS = 8;

    private final Retriever retriever;
    private final SubQuestionSearch search;
    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final ChatbotProperties.Chat settings;
    private final MeterRegistry meterRegistry;

    public QuestionDecomposer(Retriever retriever, SubQuestionSearch search, GroundedAnswerPrompt prompt,
                              GroundingInstructions instructions, ChatbotProperties.Chat settings,
                              MeterRegistry meterRegistry) {
        this.retriever = retriever;
        this.search = search;
        this.prompt = prompt;
        this.instructions = instructions;
        this.settings = settings;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Whether this question looks like it asks for more than one thing. False switches the branch off
     * entirely, so an ordinary question is retrieved exactly as it was before this phase, with no
     * model call in front of it.
     */
    public boolean shouldDecompose(UserQuestion question) {
        if (!settings.decompose().enabled()) {
            return false;
        }
        String text = question.effectiveQuery().strip();
        boolean twoQuestions = text.chars().filter(c -> c == '?').count() >= 2;
        return twoQuestions
                || (text.split("\\s+").length >= SHORTEST_COMPOSITE_WORDS && SEVERAL_PARTS.matcher(text).find());
    }

    /**
     * @return evidence merged from a pass per part; when the model finds no parts to split off, the
     * plain single-pass evidence, which the widening branch of Phase 9a may still work on
     */
    public Evidence decompose(UserQuestion question, OperationContext context) {
        question.abortIfCancelled();
        long started = System.nanoTime();
        List<String> parts = partsOf(question, context);
        long buildMs = (System.nanoTime() - started) / 1_000_000;
        question.notifyStage(AnswerStages.RETRIEVING);
        question.abortIfCancelled();
        RetrievalResult result;
        String outcome;
        try {
            result = search.search(question.retrievalQuery(), parts == null ? List.of() : parts, buildMs,
                    queries -> passes(queries, context));
            outcome = result.decomposed() ? "split" : parts == null ? "failed" : "single";
        } catch (Exception e) {
            // A pass that fails takes the whole split with it: the question is answered from the
            // evidence one search finds, which is what it would have had without this branch.
            if (ChatCancelledException.isCancellation(e)) {
                throw new ChatCancelledException(question.messageId(), "cancelled while searching for the parts");
            }
            question.abortIfCancelled();
            log.warn("Searching the parts of [{}] failed; searching the question as a whole: {}",
                    question.messageId(), e.toString());
            result = retriever.search(question.retrievalQuery());
            outcome = "failed";
        }
        meterRegistry.counter("chatbot.chat.decomposition", "outcome", outcome).increment();
        return new Evidence(question, result);
    }

    /** @return the parts of the question, empty when it asks for one thing, null when the call failed */
    private @Nullable List<String> partsOf(UserQuestion question, OperationContext context) {
        question.notifyStage(AnswerStages.DECOMPOSING);
        long started = System.nanoTime();
        try {
            SubQuestions split = context.ai()
                    .withLlm(LlmOptions.withDefaultLlm().withTemperature(0.0))
                    .withPromptContributor(instructions.questionDecomposition())
                    .creating(SubQuestions.class)
                    .fromPrompt(prompt.buildForDecomposition(question.effectiveQuery()));
            question.abortIfCancelled();
            List<String> parts = split == null ? List.of() : split.questionsOrEmpty().stream()
                    .filter(part -> part != null && !part.isBlank())
                    .map(String::strip)
                    .limit(settings.decompose().maxSubQuestions())
                    .toList();
            // One part is the question again, said differently; that is the widening branch's job, not this one.
            List<String> useful = parts.size() < 2 ? List.of() : parts;
            log.debug("Decomposed [{}] into {}", question.messageId(), useful);
            return useful;
        } catch (Exception e) {
            if (ChatCancelledException.isCancellation(e)) {
                throw new ChatCancelledException(question.messageId(), "cancelled while splitting the question");
            }
            question.abortIfCancelled();
            log.warn("Question decomposition failed for [{}]; searching the question as a whole: {}",
                    question.messageId(), e.toString());
            return null;
        } finally {
            Timer.builder("chatbot.llm").tag("operation", "decompose-question").register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * The parts are independent searches over the same index, so they run in parallel through the
     * platform's asyncer, which carries the agent process onto the worker threads. Each search takes
     * the index read lock and sets its own embedding mode, so nothing here is shared between them.
     */
    private List<RetrievalResult> passes(List<RetrievalQuery> queries, OperationContext context) {
        if (queries.size() == 1) {
            return List.of(retriever.search(queries.getFirst()));
        }
        return context.parallelMap(queries, Math.min(queries.size(), settings.decompose().maxConcurrentSearches()),
                retriever::search);
    }
}
