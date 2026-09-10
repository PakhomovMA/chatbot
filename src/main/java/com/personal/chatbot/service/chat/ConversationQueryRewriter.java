package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.StandaloneQuery;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.observability.AiOperation;
import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort reference resolution before retrieval; model output is search text only (Phase 9b). */
public class ConversationQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(ConversationQueryRewriter.class);
    private static final Pattern REFERENCE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:it|its|they|them|their|this|that|these|those|former|latter|"
                    + "он|она|оно|они|его|её|ее|их|ему|ей|им|ним|ней|них|него|неё|нее|"
                    + "этот|эта|это|эти|этого|этой|этих|этом|такой|такая|такое|также)(?![\\p{L}\\p{N}_])");
    /** A question that opens as a continuation of the last one, without naming what it continues. */
    private static final Pattern CONTINUATION = Pattern.compile(
            "(?iu)^(?:а|и|но|ну|ещё|еще|and|but|so|then|also|ok|okay)(?![\\p{L}\\p{N}_])");
    /** Words of a question, punctuation trimmed; identifiers keep the characters they are written with. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/-]*");

    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final int historyTurns;
    private final Duration timeout;
    private final ChatObservations observations;

    public ConversationQueryRewriter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                     int historyTurns, Duration timeout, ChatObservations observations) {
        this.prompt = prompt;
        this.instructions = instructions;
        this.historyTurns = historyTurns;
        this.timeout = timeout;
        this.observations = observations;
    }

    /**
     * A cheap gate: no model call for first turns, disabled history or long standalone questions —
     * and none for a short question that already names what it is about.
     *
     * <p>Length alone used to decide it, which made every short question with any history worth a
     * model call, including "Расскажи про GLM-5.3": nothing to resolve, and 132 s spent resolving it
     * on a busy local model (docs/eval-log.md, 2026-09-10). A question is left alone when it points at
     * nothing earlier — no reference word, no opening that continues the previous turn — and names
     * something the search can use: an identifier, a version, a product name. Everything the branch
     * was built for still goes through, because a question that needs its history says so with one of
     * those words.
     */
    public boolean shouldRewrite(UserQuestion question) {
        List<ConversationTurn> recent = question.history().subList(
                Math.max(0, question.history().size() - historyTurns), question.history().size());
        boolean hasContext = recent.stream().anyMatch(t -> !t.content().isBlank());
        if (!hasContext) {
            return false;
        }
        String text = question.question().strip();
        if (REFERENCE.matcher(text).find() || CONTINUATION.matcher(text).find()) {
            return true;
        }
        return text.length() <= 120 && text.split("\\s+").length <= 12 && !namesSomethingSpecific(text);
    }

    /**
     * Whether the question names something a search can go on by itself: a token carrying a digit
     * ({@code GLM-5.3}, {@code SEV-1}), an inner capital ({@code GLM}, {@code PagerDuty}) or a
     * capitalised name that is not merely the first word of the sentence.
     */
    static boolean namesSomethingSpecific(String text) {
        Matcher words = WORD.matcher(text);
        boolean first = true;
        while (words.find()) {
            String word = words.group();
            boolean hasDigit = word.chars().anyMatch(Character::isDigit);
            boolean hasLetter = word.chars().anyMatch(Character::isLetter);
            boolean innerCapital = word.codePoints().skip(1).anyMatch(Character::isUpperCase);
            boolean name = !first && word.length() > 1 && Character.isUpperCase(word.codePointAt(0));
            if ((hasDigit && hasLetter) || innerCapital || name) {
                return true;
            }
            first = false;
        }
        return false;
    }

    public UserQuestion rewrite(UserQuestion question, OperationContext context) {
        question.abortIfCancelled();
        if (!shouldRewrite(question)) {
            return question;
        }
        question.notifyStage(AnswerStages.REWRITING);
        // Whatever the attempt ends in — including a cancellation, as it always has (metric catalog,
        // chatbot.chat.query.rewrite) — the branch reports one decision about the search text.
        ChatObservations.RewriteOutcome outcome = ChatObservations.RewriteOutcome.FALLBACK;
        try (Measured operation = observations.startAiOperation(AiOperation.CONVERSATION_QUERY_REWRITE)) {
            operation.legacyBegins();
            try {
                // Bounded, unlike the answer it prepares: this call only makes the search text better, and
                // waiting for it longer than the platform default costs the user the answer itself. A
                // question the model does not resolve in time is searched as it was asked, which is what
                // happens on any other failure here. Embabel may still retry once around this bound.
                StandaloneQuery result = context.ai()
                        .withLlm(LlmOptions.withDefaultLlm().withTemperature(0.0).withTimeout(timeout))
                        .withPromptContributor(instructions.conversationRewrite())
                        .creating(StandaloneQuery.class)
                        .fromPrompt(prompt.buildForConversationRewrite(question.question(), question.history()));
                question.abortIfCancelled();
                String query = result == null || result.query() == null ? "" : result.query().strip();
                // Broken/verbose output must not replace a usable question, nor be truncated into a different intent.
                if (query.isBlank() || query.length() > 1000 || query.codePoints().anyMatch(Character::isISOControl)) {
                    operation.finished(Outcome.FALLBACK);
                    return question;
                }
                outcome = query.equals(question.question())
                        ? ChatObservations.RewriteOutcome.UNCHANGED : ChatObservations.RewriteOutcome.REWRITTEN;
                operation.succeeded();
                return question.withEffectiveQuery(query);
            } catch (Exception e) {
                operation.recovered(e, question.cancellation().telemetryReason());
                if (ChatCancelledException.isCancellation(e)) {
                    throw new ChatCancelledException(question.messageId(), "cancelled during query rewriting");
                }
                question.abortIfCancelled();
                log.warn("Conversation query rewriting failed for [{}]; using original question: {}", question.messageId(), e.toString());
                return question;
            } finally {
                observations.queryRewrite(outcome);
                log.debug("Conversation query preparation for [{}]: {}", question.messageId(), outcome);
            }
        }
    }
}
