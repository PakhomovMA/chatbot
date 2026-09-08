package com.personal.chatbot.utils;

/** Text shaping shared by prompts, diagnostics and citation quotes. */
public final class Texts {

    /** Appended where text was cut off. */
    public static final String ELLIPSIS = "…";

    private Texts() {
    }

    /** Collapses all whitespace into single spaces and truncates to {@code max} characters. */
    public static String singleLine(String text, int max) {
        return truncate(text.replaceAll("\\s+", " ").strip(), max);
    }

    /** Truncates to {@code max} characters, keeping the original line structure. */
    public static String truncate(String text, int max) {
        String stripped = text.strip();
        return stripped.length() > max ? stripped.substring(0, max).stripTrailing() + ELLIPSIS : stripped;
    }
}
