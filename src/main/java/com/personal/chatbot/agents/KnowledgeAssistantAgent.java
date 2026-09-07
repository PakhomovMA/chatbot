package com.personal.chatbot.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.personal.chatbot.models.agent.AnswerAttempt;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.chat.AgenticResearcher;
import com.personal.chatbot.service.chat.AnswerDrafter;
import com.personal.chatbot.service.chat.AnswerStages;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The knowledge assistant (docs/system-plan.md §7, D10, D11, Phase 9c). Two ways to reach the goal,
 * chosen by the {@code agenticMode} / {@code deterministicMode} conditions:
 * <ul>
 *   <li><b>deterministic</b>: {@code retrieveEvidence} (one hybrid retrieval, INV-01) → {@code draftAnswer};</li>
 *   <li><b>agentic</b>: {@code researchIteratively} lets the model search the knowledge base itself.</li>
 * </ul>
 * Both branches end in an {@link AnswerAttempt} and share the single goal {@code verifyGrounding}, which
 * applies the same citation check either way. The work belongs to deterministic services; this class only
 * declares the plan and moves blackboard artifacts between them (INV-06, INV-07).
 */
@Agent(description = "Answers questions from the local knowledge base with verified citations")
public class KnowledgeAssistantAgent {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAssistantAgent.class);

    static final String AGENTIC_CONDITION = "agenticMode";
    static final String DETERMINISTIC_CONDITION = "deterministicMode";

    private final Retriever retrievalService;
    private final AnswerDrafter drafter;
    private final AgenticResearcher researcher;
    private final GroundingVerifier verifier;

    public KnowledgeAssistantAgent(Retriever retrievalService, AnswerDrafter drafter,
                                   AgenticResearcher researcher, GroundingVerifier verifier) {
        this.retrievalService = retrievalService;
        this.drafter = drafter;
        this.researcher = researcher;
        this.verifier = verifier;
    }

    // Two explicit conditions instead of a negated expression: Embabel's default expression parser
    // is empty, so precondition strings are plain condition names.
    @Condition(name = AGENTIC_CONDITION)
    public boolean agenticMode(UserQuestion question) {
        return question.mode() == AnswerMode.AGENTIC;
    }

    @Condition(name = DETERMINISTIC_CONDITION)
    public boolean deterministicMode(UserQuestion question) {
        return question.mode() != AnswerMode.AGENTIC;
    }

    // ---- deterministic path ----------------------------------------------------------------------

    @Action(description = "Retrieve evidence for the question from the knowledge base", readOnly = true, pre = DETERMINISTIC_CONDITION)
    public Evidence retrieveEvidence(UserQuestion question) {
        question.notifyStage(AnswerStages.RETRIEVING);
        RetrievalResult result = retrievalService.search(
                new RetrievalQuery(question.question(), question.topK(), null, question.documentIds()));
        log.debug("Retrieved {} hits for [{}] (sufficient={})", result.hits().size(), question.messageId(), result.evidenceSufficient());
        return new Evidence(question, result);
    }

    @Action(description = "Draft an answer that uses only the retrieved evidence", pre = DETERMINISTIC_CONDITION)
    public AnswerAttempt draftAnswer(Evidence evidence, OperationContext context) {
        return new AnswerAttempt(evidence, drafter.draft(evidence, context), drafter.passagesShown(evidence));
    }

    // ---- agentic path (ToolishRag) -----------------------------------------------------------------

    @Action(description = "Research the question with the knowledge-base search tools and draft an answer", pre = AGENTIC_CONDITION)
    public AnswerAttempt researchIteratively(UserQuestion question, OperationContext context) {
        return researcher.research(question, context);
    }

    // ---- shared verification -----------------------------------------------------------------------

    /**
     * The single goal of the agent: both branches produce an {@link AnswerAttempt}, so the planner
     * always has a reachable goal and never logs an unreachable one on replan.
     */
    @AchievesGoal(description = "A grounded answer whose citations were verified against the evidence")
    @Action(description = "Verify the draft's citations against the evidence and classify grounding", readOnly = true)
    public GroundedAnswer verifyGrounding(AnswerAttempt attempt) {
        Evidence evidence = attempt.evidence();
        evidence.question().notifyStage(AnswerStages.VERIFYING);
        GroundedAnswer answer = verifier.verify(evidence, attempt.draft(), attempt.passagesShown());
        log.debug("Answer for [{}]: {} with {} citations", evidence.question().messageId(), answer.grounding(), answer.citations().size());
        return answer;
    }
}
