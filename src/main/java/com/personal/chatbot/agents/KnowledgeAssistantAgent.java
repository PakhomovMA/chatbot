package com.personal.chatbot.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.OperationContext;
import com.personal.chatbot.models.agent.AnswerAttempt;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.PreparedQuestion;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.chat.AgenticResearcher;
import com.personal.chatbot.service.chat.AnswerDrafter;
import com.personal.chatbot.service.chat.AnswerStages;
import com.personal.chatbot.service.chat.ConversationQueryRewriter;
import com.personal.chatbot.service.chat.EvidenceExpander;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.chat.QuestionDecomposer;
import com.personal.chatbot.service.chat.SourceComparator;
import com.personal.chatbot.service.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The knowledge assistant (docs/system-plan.md §7, D10, D11, Phase 9c). Two ways to reach the goal,
 * chosen by the {@code agenticMode} / {@code deterministicMode} conditions:
 * <ul>
 *   <li><b>deterministic</b>: {@code retrieveEvidence} (one hybrid retrieval, INV-01) — or
 *   {@code decomposeQuestion}, a retrieval per part, when the question asks for several things
 *   (Phase 9d) — then {@code draftAnswer}, with {@code expandSearch} in between when the evidence is
 *   too weak to answer from (Phase 9a) and {@code compareSources} when the answer has to be drawn
 *   from several documents (Phase 9d);</li>
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
    static final String EVIDENCE_READY_CONDITION = "evidenceReady";
    static final String COMPOSITE_QUESTION_CONDITION = "compositeQuestion";
    static final String SINGLE_QUESTION_CONDITION = "singleQuestion";
    static final String SOURCES_COMPARED_CONDITION = "sourcesCompared";

    private final Retriever retrievalService;
    private final AnswerDrafter drafter;
    private final EvidenceExpander expander;
    private final AgenticResearcher researcher;
    private final GroundingVerifier verifier;
    private final ConversationQueryRewriter rewriter;
    private final QuestionDecomposer decomposer;
    private final SourceComparator comparator;

    public KnowledgeAssistantAgent(Retriever retrievalService, AnswerDrafter drafter, EvidenceExpander expander,
                                   AgenticResearcher researcher, GroundingVerifier verifier,
                                   ConversationQueryRewriter rewriter, QuestionDecomposer decomposer,
                                   SourceComparator comparator) {
        this.retrievalService = retrievalService;
        this.drafter = drafter;
        this.expander = expander;
        this.researcher = researcher;
        this.verifier = verifier;
        this.rewriter = rewriter;
        this.decomposer = decomposer;
        this.comparator = comparator;
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

    /**
     * Whether the retrieved evidence is worth answering from. False makes the planner insert
     * {@code expandSearch} before drafting, and that action makes it true again (Phase 9a), so the
     * question is searched twice at most and the branch cannot loop.
     */
    @Condition(name = EVIDENCE_READY_CONDITION)
    public boolean evidenceReady(Evidence evidence) {
        return !expander.shouldExpand(evidence);
    }

    /**
     * Whether the question asks for more than one thing, decided by its wording alone. It picks the
     * retrieval action for this question (Phase 9d), so the two are mutually exclusive and neither can
     * leave the planner without a way to reach evidence.
     *
     * <p>Both read the question as the user asked it, not the standalone query {@code prepareQuestion}
     * produces: a condition no action can make true is a plan the planner cannot form at all, so a
     * branch chosen by a condition has to be decided from what is on the blackboard at the start.
     */
    @Condition(name = COMPOSITE_QUESTION_CONDITION)
    public boolean compositeQuestion(UserQuestion question) {
        return decomposer.shouldDecompose(question);
    }

    @Condition(name = SINGLE_QUESTION_CONDITION)
    public boolean singleQuestion(UserQuestion question) {
        return !decomposer.shouldDecompose(question);
    }

    /**
     * Whether the sources behind the evidence still need to be related to each other. False makes the
     * planner insert {@code compareSources} before drafting, and that action makes it true again
     * (Phase 9d), so the comparison happens at most once per question.
     */
    @Condition(name = SOURCES_COMPARED_CONDITION)
    public boolean sourcesCompared(Evidence evidence) {
        return !comparator.shouldCompare(evidence);
    }

    // ---- shared preparation ----------------------------------------------------------------------

    @Action(description = "Resolve conversational references into a standalone search query", readOnly = true)
    public PreparedQuestion prepareQuestion(UserQuestion question, OperationContext context) {
        return new PreparedQuestion(rewriter.rewrite(question, context));
    }

    // ---- deterministic path ----------------------------------------------------------------------

    @Action(description = "Retrieve evidence for the question from the knowledge base", readOnly = true,
            pre = {DETERMINISTIC_CONDITION, SINGLE_QUESTION_CONDITION})
    public Evidence retrieveEvidence(PreparedQuestion prepared) {
        UserQuestion question = prepared.question();
        question.abortIfCancelled();
        question.notifyStage(AnswerStages.RETRIEVING);
        RetrievalResult result = retrievalService.search(question.retrievalQuery());
        log.debug("Retrieved {} hits for [{}] (sufficient={})", result.hits().size(), question.messageId(), result.evidenceSufficient());
        return new Evidence(question, result);
    }

    /**
     * The other way to evidence: a question that asks for several things is split into its parts and
     * each part is retrieved on its own, so no part is crowded out of the evidence by another. Costs
     * one model call and a search per part, which is why the planner sees a cost here.
     */
    @Action(description = "Retrieve evidence per part of a question that asks for several things",
            readOnly = true, cost = 0.3, pre = {DETERMINISTIC_CONDITION, COMPOSITE_QUESTION_CONDITION})
    public Evidence decomposeQuestion(PreparedQuestion prepared, OperationContext context) {
        return decomposer.decompose(prepared.question(), context);
    }

    /**
     * The condition-driven half of retrieval: reached only when the first pass found nothing that
     * clears the sufficiency floor. Costs a search and, for the model-driven strategies, one short
     * model call — which is why the plan pays for it on weak questions only, and why the action
     * carries a cost the planner can see.
     */
    @Action(description = "Search again with a widened query when the first pass found weak evidence",
            readOnly = true, cost = 0.3, pre = DETERMINISTIC_CONDITION, post = EVIDENCE_READY_CONDITION)
    public Evidence expandSearch(Evidence evidence, OperationContext context) {
        return expander.expand(evidence, context);
    }

    /**
     * Relates the sources to each other before the answer is written, for a question that asks how
     * two things compare and evidence that comes from more than one document. It needs the final
     * evidence, hence the precondition: comparing passages a widened search is about to replace
     * would pay for a model call twice.
     */
    @Action(description = "Compare what the documents behind the evidence say before the answer is written",
            readOnly = true, cost = 0.3, pre = {DETERMINISTIC_CONDITION, EVIDENCE_READY_CONDITION},
            post = SOURCES_COMPARED_CONDITION)
    public Evidence compareSources(Evidence evidence, OperationContext context) {
        return comparator.compare(evidence, context);
    }

    @Action(description = "Draft an answer that uses only the retrieved evidence",
            pre = {DETERMINISTIC_CONDITION, EVIDENCE_READY_CONDITION, SOURCES_COMPARED_CONDITION})
    public AnswerAttempt draftAnswer(Evidence evidence, OperationContext context) {
        return new AnswerAttempt(evidence, drafter.draft(evidence, context), drafter.passagesShown(evidence));
    }

    // ---- agentic path (ToolishRag) -----------------------------------------------------------------

    @Action(description = "Research the question with the knowledge-base search tools and draft an answer", pre = AGENTIC_CONDITION)
    public AnswerAttempt researchIteratively(PreparedQuestion prepared, OperationContext context) {
        return researcher.research(prepared.question(), context);
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
