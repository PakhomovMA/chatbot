package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.StandaloneQuery;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.ConversationTurn;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Best-effort reference resolution before retrieval; model output is search text only (Phase 9b). */
public class ConversationQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(ConversationQueryRewriter.class);
    private static final Pattern REFERENCE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:it|its|they|them|their|this|that|these|those|former|latter|"
                    + "он|она|оно|они|его|её|ее|их|ему|ей|им|ним|ней|них|него|неё|нее|"
                    + "этот|эта|это|эти|этого|этой|этих|этом|такой|такая|такое|также)(?![\\p{L}\\p{N}_])");

    private final GroundedAnswerPrompt prompt;
    private final GroundingInstructions instructions;
    private final int historyTurns;
    private final MeterRegistry meters;

    public ConversationQueryRewriter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                     int historyTurns, MeterRegistry meters) {
        this.prompt = prompt;
        this.instructions = instructions;
        this.historyTurns = historyTurns;
        this.meters = meters;
    }

    /** A cheap gate: no model call for first turns, disabled history or long standalone questions. */
    public boolean shouldRewrite(UserQuestion question) {
        List<ConversationTurn> recent = question.history().subList(
                Math.max(0, question.history().size() - historyTurns), question.history().size());
        boolean hasContext = recent.stream().anyMatch(t -> !t.content().isBlank());
        String text = question.question().strip();
        return hasContext && ((text.length() <= 120 && text.split("\\s+").length <= 12)
                || REFERENCE.matcher(text).find());
    }

    public UserQuestion rewrite(UserQuestion question, OperationContext context) {
        question.abortIfCancelled();
        if (!shouldRewrite(question)) {
            return question;
        }
        question.notifyStage(AnswerStages.REWRITING);
        long started = System.nanoTime();
        String outcome = "fallback";
        try {
            StandaloneQuery result = context.ai().withLlm(LlmOptions.withDefaultLlm().withTemperature(0.0))
                    .withPromptContributor(instructions.conversationRewrite())
                    .creating(StandaloneQuery.class)
                    .fromPrompt(prompt.buildForConversationRewrite(question.question(), question.history()));
            question.abortIfCancelled();
            String query = result == null || result.query() == null ? "" : result.query().strip();
            // Broken/verbose output must not replace a usable question, nor be truncated into a different intent.
            if (query.isBlank() || query.length() > 1000 || query.codePoints().anyMatch(Character::isISOControl)) {
                return question;
            }
            outcome = query.equals(question.question()) ? "unchanged" : "rewritten";
            return question.withEffectiveQuery(query);
        } catch (Exception e) {
            if (ChatCancelledException.isCancellation(e)) {
                throw new ChatCancelledException(question.messageId(), "cancelled during query rewriting");
            }
            question.abortIfCancelled();
            log.warn("Conversation query rewriting failed for [{}]; using original question: {}", question.messageId(), e.toString());
            return question;
        } finally {
            Timer.builder("chatbot.llm").tag("operation", "conversation-query-rewrite").register(meters)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            meters.counter("chatbot.chat.query.rewrite", "outcome", outcome).increment();
            log.debug("Conversation query preparation for [{}]: {}", question.messageId(), outcome);
        }
    }
}
