package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fallback attribution for agentic answers (Phase 9c): small local models often answer
 * correctly from the tool results but forget to reference chunk ids. When the draft carries no
 * references, a chunk is credited if the answer reuses enough of its distinctive tokens (identifiers,
 * commands, numbers, long words). This never invents evidence: only chunks the model actually saw are
 * candidates, and the answer must literally contain their content (INV-03).
 */
public final class EvidenceAttributor {

    /** Tokens that must overlap for a chunk to be credited. */
    static final int MIN_SHARED_TOKENS = 3;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_./:-]{4,}");
    private static final Set<String> STOPWORDS = Set.of("about", "after", "before", "should", "their", "there", "these",
            "those", "which", "while", "would", "could", "every", "other", "within", "using", "under", "where", "because");

    private EvidenceAttributor() {
    }

    /** 1-based positions (in {@code seen}) of chunks the answer demonstrably draws on, best first. */
    public static List<Integer> attribute(String answer, List<RetrievedChunk> seen) {
        Set<String> answerTokens = tokens(answer);
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < seen.size(); i++) {
            Set<String> shared = new HashSet<>(tokens(seen.get(i).text()));
            shared.retainAll(answerTokens);
            if (shared.size() >= MIN_SHARED_TOKENS) {
                scored.add(new int[]{i + 1, shared.size()});
            }
        }
        scored.sort((a, b) -> Integer.compare(b[1], a[1]));
        return scored.stream().map(s -> s[0]).toList();
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().replaceAll("[.:,;]+$", "");
            if (!STOPWORDS.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }
}
