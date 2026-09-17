package com.personal.chatbot.service.cache;

import com.personal.chatbot.models.agent.UserQuestion;

import java.util.List;
import java.util.Optional;

/** Cache boundary for the four KB-independent question transformations. */
public interface DerivationCache {
    DerivationCache NONE = (step, prompt, instructions, temperature, question) -> new Attempt() {
        public Optional<List<String>> value() { return Optional.empty(); }
        public void usable(List<String> value) { }
    };

    Attempt lookup(Derivation step, String prompt, String instructions, double temperature, UserQuestion question);

    interface Attempt {
        Optional<List<String>> value();
        /** Stages a validated result for storage only when this request successfully finishes. */
        void usable(List<String> value);
    }
}
