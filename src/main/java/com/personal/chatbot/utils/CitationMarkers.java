package com.personal.chatbot.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code [n]} citation markers that tie an answer to numbered evidence passages. Shared by the
 * streamed-draft parser, the agentic draft mapper and the grounding verifier so all three agree on
 * what counts as a marker (docs/system-plan.md D10, INV-03).
 *
 * <p>A marker may carry several passage numbers at once ({@code [1, 2]}): local models group their
 * citations that way as readily as they write them one by one, and a grouped marker read as prose
 * costs the answer every citation in it — and with them, its GROUNDED standing.
 */
public final class CitationMarkers {

    /** Group 1 is the leading whitespace, group 2 the comma-separated passage numbers. */
    private static final Pattern MARKER = Pattern.compile("(\\s*)\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]");

    private CitationMarkers() {
    }

    /** Every marker in the text, ascending and de-duplicated. */
    public static SortedSet<Integer> collect(String text) {
        SortedSet<Integer> cited = new TreeSet<>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) {
            cited.addAll(numbers(matcher.group(2)));
        }
        return cited;
    }

    /**
     * Removes the given markers (with any whitespace in front of them) from the text. A grouped
     * marker keeps the numbers that were not given and is rewritten without the rest; it is left
     * exactly as the model wrote it when none of them were.
     */
    public static String remove(String text, Collection<Integer> markers) {
        if (markers.isEmpty()) {
            return text;
        }
        Set<Integer> dropped = Set.copyOf(markers);
        Matcher matcher = MARKER.matcher(text);
        StringBuilder cleaned = new StringBuilder();
        while (matcher.find()) {
            List<Integer> cited = numbers(matcher.group(2));
            List<Integer> kept = cited.stream().filter(n -> !dropped.contains(n)).toList();
            String replacement = kept.size() == cited.size()
                    ? matcher.group()
                    : render(matcher.group(1), kept);
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(cleaned);
        return cleaned.toString().strip();
    }

    /** The passage numbers of one marker, in the order the model wrote them. */
    private static List<Integer> numbers(String group) {
        List<Integer> cited = new ArrayList<>();
        for (String number : group.split(",")) {
            cited.add(Integer.parseInt(number.strip()));
        }
        return cited;
    }

    /** A marker holding the surviving numbers, or nothing at all when none survived. */
    private static String render(String leadingWhitespace, List<Integer> kept) {
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder marker = new StringBuilder(leadingWhitespace).append('[');
        for (int i = 0; i < kept.size(); i++) {
            marker.append(i == 0 ? "" : ", ").append(kept.get(i));
        }
        return marker.append(']').toString();
    }
}
