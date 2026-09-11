package com.personal.chatbot.service.cache;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code norm-v1} (docs/cache-plan.md §3.2): the form in which two questions count as one for the
 * exact answer cache. It removes only differences no reader would call a different question — Unicode
 * compatibility forms, case, spacing and the punctuation that closes the sentence. Anything that may
 * carry meaning stays: inner punctuation and identifiers ({@code SEV-1}, {@code GLM-5.3}), word order,
 * negations. Recognising a paraphrase is the semantic layer's job, not this one's.
 *
 * <p>The version goes into every key built from the normal form: a change to the rules below is a new
 * version, so that entries made under the old rules are never matched by the new ones.
 */
public final class QuestionNormalizer {

    public static final String VERSION = "norm-v1";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    /** Marks that close the sentence; NFKC has already turned {@code ？} into {@code ?} and {@code …} into {@code ...}. */
    private static final Pattern CLOSING_PUNCTUATION = Pattern.compile("[?!. ]+$");

    private QuestionNormalizer() {
    }

    public static String normalize(String question) {
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return CLOSING_PUNCTUATION.matcher(text).replaceFirst("");
    }
}
