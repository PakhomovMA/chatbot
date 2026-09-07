package com.personal.chatbot.models.chat;

/** How well an answer is backed by retrieved evidence (docs/system-plan.md D10, INV-03). */
public enum Grounding {
    /** At least one verified citation and the model reported the evidence as sufficient. */
    GROUNDED,
    /** The model answered, but without verifiable citations or below the retrieval sufficiency floor. */
    PARTIAL,
    /** Nothing usable was retrieved, or the model reported the evidence as insufficient. */
    INSUFFICIENT_EVIDENCE
}
