package com.personal.chatbot.utils;

import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code [n]} citation markers that tie an answer to numbered evidence passages. Shared by the
 * streamed-draft parser, the agentic draft mapper and the grounding verifier so all three agree on
 * what counts as a marker (docs/system-plan.md D10, INV-03).
 */
public final class CitationMarkers {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

    private CitationMarkers() {
    }

    /** Every marker in the text, ascending and de-duplicated. */
    public static SortedSet<Integer> collect(String text) {
        SortedSet<Integer> cited = new TreeSet<>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) {
            cited.add(Integer.parseInt(matcher.group(1)));
        }
        return cited;
    }

    /** Removes the given markers (with any whitespace in front of them) from the text. */
    public static String remove(String text, Collection<Integer> markers) {
        if (markers.isEmpty()) {
            return text;
        }
        String cleaned = text;
        for (int n : markers) {
            cleaned = cleaned.replaceAll("\\s*\\[" + n + "]", "");
        }
        return cleaned.strip();
    }
}
