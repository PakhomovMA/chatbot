package com.personal.chatbot.eval;

import java.util.List;

public record EvalQuestion(String id, String question, List<String> categories, List<RelevantPassage> relevant) {
    public EvalQuestion {
        categories = categories == null ? List.of() : List.copyOf(categories);
        relevant = relevant == null ? List.of() : List.copyOf(relevant);
    }

    public boolean isNegative() {
        return relevant.isEmpty();
    }

    public boolean isMultiChunk() {
        return categories.contains("multi-chunk");
    }

    public record RelevantPassage(String document, List<String> mustContain) {
        public RelevantPassage {
            mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        }
    }
}
