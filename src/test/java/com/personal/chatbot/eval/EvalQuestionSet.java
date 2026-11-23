package com.personal.chatbot.eval;

import java.util.List;

public record EvalQuestionSet(String description, List<EvalQuestion> questions) {
    public EvalQuestionSet {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
