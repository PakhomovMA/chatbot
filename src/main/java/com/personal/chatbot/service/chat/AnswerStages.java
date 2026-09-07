package com.personal.chatbot.service.chat;

/** Stage names reported to a streaming client; the frontend keys its progress display off these. */
public final class AnswerStages {

    public static final String RETRIEVING = "retrieving";
    public static final String EXPANDING = "expanding";
    public static final String GENERATING = "generating";
    public static final String RESEARCHING = "researching";
    public static final String VERIFYING = "verifying";

    private AnswerStages() {
    }
}
