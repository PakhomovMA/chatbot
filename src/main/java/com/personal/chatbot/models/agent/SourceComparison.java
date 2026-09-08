package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.personal.chatbot.utils.Texts;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Model output of {@code compareSources} (docs/system-plan.md Phase 9d): what the evidence passages
 * of several documents say about each aspect of the question, and where they disagree.
 *
 * <p>It is an analysis of passages the model has already been given, never a source of facts of its
 * own: it goes back into the answer prompt as structure, and the answer is still verified against the
 * evidence (INV-03). Everything here is untrusted model text, so {@link #limitedTo} is applied before
 * it is used anywhere.
 */
@JsonClassDescription("How the numbered evidence passages of different documents relate on the aspects the question asks about")
public record SourceComparison(
        @JsonPropertyDescription("One entry per aspect the documents both speak about; an empty list when they do not overlap")
        @Nullable List<Aspect> aspects
) {

    /** Longest aspect and finding text kept; a comparison is a summary, not a second answer. */
    private static final int ASPECT_CHARS = 80;
    private static final int FINDING_CHARS = 300;

    @JsonClassDescription("One point of comparison between the sources")
    public record Aspect(
            @JsonPropertyDescription("What is being compared, a few words (for example \"how long a rollback takes\")")
            String aspect,
            @JsonPropertyDescription("What the passages say about it, one or two sentences naming which document says what")
            String finding,
            @JsonPropertyDescription("Numbers of the evidence passages this entry is based on, at least one")
            @Nullable List<Integer> passages,
            @JsonPropertyDescription("true only when the passages actually disagree about this aspect")
            boolean conflicting
    ) {

        public List<Integer> passagesOrEmpty() {
            return passages != null ? passages : List.of();
        }
    }

    public static SourceComparison none() {
        return new SourceComparison(List.of());
    }

    public List<Aspect> aspectsOrEmpty() {
        return aspects != null ? aspects : List.of();
    }

    public boolean isEmpty() {
        return aspectsOrEmpty().isEmpty();
    }

    public boolean hasConflict() {
        return aspectsOrEmpty().stream().anyMatch(Aspect::conflicting);
    }

    /**
     * The comparison as it may be used: aspects that point at passages the model was actually shown,
     * each referring to those passages only, capped in number and in length.
     *
     * <p>A reference outside the numbered evidence is the same phantom citation the verifier strips
     * from an answer (INV-03); here it is dropped before it can reach the next prompt and invite one.
     */
    public SourceComparison limitedTo(int passagesShown, int maxAspects) {
        List<Aspect> kept = new ArrayList<>();
        for (Aspect aspect : aspectsOrEmpty()) {
            if (kept.size() == maxAspects) {
                break;
            }
            if (aspect == null || aspect.aspect() == null || aspect.aspect().isBlank()
                    || aspect.finding() == null || aspect.finding().isBlank()) {
                continue;
            }
            // Nulls are dropped before sorting: a list from the model may hold anything.
            List<Integer> refs = aspect.passagesOrEmpty().stream()
                    .filter(n -> n != null && n >= 1 && n <= passagesShown)
                    .distinct().sorted().toList();
            if (refs.isEmpty()) {
                continue;
            }
            kept.add(new Aspect(Texts.singleLine(aspect.aspect(), ASPECT_CHARS),
                    Texts.singleLine(aspect.finding(), FINDING_CHARS), refs, aspect.conflicting()));
        }
        return new SourceComparison(List.copyOf(kept));
    }
}
