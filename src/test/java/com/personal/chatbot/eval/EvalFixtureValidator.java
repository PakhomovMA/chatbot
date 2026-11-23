package com.personal.chatbot.eval;

import com.embabel.agent.rag.model.Chunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EvalFixtureValidator {
    private EvalFixtureValidator() {
    }

    public static void validateSchema(EvalQuestionSet set) {
        Set<String> ids = new HashSet<>();
        Map<String, String> idsByQuestionText = new HashMap<>();
        for (EvalQuestion question : set.questions()) {
            require(ids.add(question.id()), "Duplicate question ID " + question.id());
            require(question.question() != null && !question.question().isBlank(),
                    "Blank question text for " + question.id());
            String normalizedText = question.question().toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
            String previousId = idsByQuestionText.putIfAbsent(normalizedText, question.id());
            require(previousId == null, "Duplicate question text in " + previousId + " and " + question.id());
            // Java scores a question as negative by its empty evidence and exports that flag to the comparison
            // script; the categories must agree with it in both directions.
            boolean negativeCategory = question.categories().contains("negative")
                    || question.categories().contains("near-topic");
            require(!negativeCategory || question.relevant().isEmpty(),
                    "negative question " + question.id() + " has relevant evidence");
            require(negativeCategory || !question.relevant().isEmpty(),
                    "question " + question.id() + " has no relevant evidence but no negative category");
        }

        require(set.questions().size() >= 160, "Expected at least 160 questions, got " + set.questions().size());
        long positives = set.questions().stream().filter(q -> !q.isNegative()).count();
        long negatives = set.questions().size() - positives;
        require(positives >= 136, "Expected at least 136 positive questions, got " + positives);
        require(negatives >= 24, "Expected at least 24 negative questions, got " + negatives);
        long multiChunk = categoryCount(set, "multi-chunk");
        require(multiChunk >= 40, "Expected at least 40 multi-chunk questions");
        require(multiChunk >= set.questions().size() * 0.25,
                "Expected multi-chunk questions to be at least 25% of the set");
        require(categoryCount(set, "comparison") >= 12, "Expected at least 12 comparison questions");
        require(categoryCount(set, "ru-to-en") >= 12, "Expected at least 12 RU-to-EN questions");
        require(categoryCount(set, "en-to-ru") >= 12, "Expected at least 12 EN-to-RU questions");

    }

    public static void validateChunks(EvalQuestionSet set, List<Chunk> chunks) {
        Map<String, List<Chunk>> byDocument = new HashMap<>();
        for (Chunk chunk : chunks) {
            Object document = chunk.getMetadata().get(ProvenanceChunkTransformer.DOCUMENT_ID);
            if (document != null) {
                byDocument.computeIfAbsent(document.toString(), _ -> new java.util.ArrayList<>()).add(chunk);
            }
        }
        for (EvalQuestion question : set.questions()) {
            Map<String, Chunk> relevantChunks = new LinkedHashMap<>();
            for (EvalQuestion.RelevantPassage target : question.relevant()) {
                List<Chunk> documentChunks = byDocument.get(target.document());
                require(documentChunks != null,
                        "Unknown document " + target.document() + " in question " + question.id());
                for (String phrase : target.mustContain()) {
                    require(!phrase.isBlank(), "Blank expected phrase in question " + question.id());
                }
                List<Chunk> matches = documentChunks.stream().filter(chunk -> isRelevant(chunk, target)).toList();
                require(!matches.isEmpty(), "Expected phrase " + target.mustContain() + " from " + target.document()
                        + " not found for question " + question.id());
                matches.forEach(chunk -> relevantChunks.putIfAbsent(chunk.getId(), chunk));
            }
            if (question.isMultiChunk()) {
                // Every target needs a chunk of its own, otherwise full coverage is trivially met or unreachable.
                var matching = new EvalMetrics.FragmentMatching<Chunk>(question.relevant(),
                        EvalFixtureValidator::isRelevant);
                relevantChunks.values().forEach(matching::add);
                require(question.relevant().size() >= 2 && matching.matched() == question.relevant().size(),
                        "Multi-chunk question " + question.id() + " does not have a distinct relevant chunk for every target");
            }
        }
    }

    private static boolean isRelevant(Chunk chunk, EvalQuestion.RelevantPassage target) {
        return target.document().equals(String.valueOf(chunk.getMetadata().get(ProvenanceChunkTransformer.DOCUMENT_ID)))
                && EvalMetrics.containsAny(chunk.getUrtext(), target);
    }

    private static long categoryCount(EvalQuestionSet set, String category) {
        return set.questions().stream().filter(question -> question.categories().contains(category)).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
