package com.personal.chatbot.models.agent;

/**
 * Blackboard artifact: a draft answer together with the evidence it has to be verified against
 * (docs/system-plan.md D10, INV-03).
 *
 * <p>Both branches of the plan converge on this type so that the agent has a single goal. With one
 * goal per branch the GOAP planner logged an ERROR on every replan for whichever goal the current
 * mode makes unreachable, even though the run itself was healthy.
 *
 * @param passagesShown how many evidence hits the model actually saw — the prompt budget can cut the
 *                      tail off the retrieved list, and a citation beyond that is a phantom
 */
public record AnswerAttempt(Evidence evidence, GroundedAnswerDraft draft, int passagesShown) {

    public UserQuestion question() {
        return evidence.question();
    }
}
