package com.personal.chatbot.service.chat;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.personal.chatbot.exceptions.ConversationNotFoundException;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatTimings;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.ConversationView;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Chat entry point (docs/system-plan.md §2.2): resolves the conversation, runs the knowledge assistant
 * agent through Embabel and maps its goal artifact to the API response. Frontend-facing types only
 * cross this boundary (INV-10).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentPlatform agentPlatform;
    private final ConversationStore conversations;
    private final RetrievalTraceStore traces;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ChatService(AgentPlatform agentPlatform, ConversationStore conversations, RetrievalTraceStore traces,
                       MeterRegistry meterRegistry, Clock clock) {
        this.agentPlatform = agentPlatform;
        this.conversations = conversations;
        this.traces = traces;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public ChatResponse chat(ChatRequest request) {
        long started = System.nanoTime();
        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String question = request.message().strip();
        ChatRequest.Options options = request.optionsOrDefault();
        List<ConversationTurn> history = conversations.history(conversationId);

        UserQuestion input = new UserQuestion(conversationId, messageId, question, history, options.topK(), options.documentIds());
        GroundedAnswer answer = AgentInvocation.create(agentPlatform, GroundedAnswer.class).invoke(input);

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        ChatTimings timings = new ChatTimings(answer.retrievalMs(), Math.max(0, totalMs - answer.retrievalMs()), totalMs);
        RetrievalResult diagnostics = options.diagnostics() ? traces.find(answer.retrievalTraceId()).orElse(null) : null;

        Instant now = clock.instant();
        conversations.append(conversationId, ConversationTurn.user(question, now));
        conversations.append(conversationId, ConversationTurn.assistant(answer.answer(), answer.citations(), now));
        Timer.builder("chatbot.chat").tag("grounding", answer.grounding().name().toLowerCase()).register(meterRegistry)
                .record(totalMs, TimeUnit.MILLISECONDS);
        log.info("Chat [{}] {} in {} ms ({} citations, retrieval {} ms)", messageId, answer.grounding(), totalMs,
                answer.citations().size(), answer.retrievalMs());
        return new ChatResponse(conversationId, messageId, answer.answer(), answer.grounding(), answer.citations(),
                answer.notes(), timings, answer.retrievalTraceId(), diagnostics);
    }

    public ConversationView conversation(String conversationId) {
        return conversations.find(conversationId).orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    public void deleteConversation(String conversationId) {
        if (!conversations.delete(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
    }
}
