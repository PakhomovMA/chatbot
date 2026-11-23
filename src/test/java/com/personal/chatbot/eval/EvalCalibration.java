package com.personal.chatbot.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

public final class EvalCalibration {
    private EvalCalibration() {
    }

    public static Split split(List<EvalQuestion> questions, long seed) {
        Map<String, List<EvalQuestion>> strata = new LinkedHashMap<>();
        for (EvalQuestion question : questions) {
            String key = (question.isNegative() ? "negative" : "positive") + ":" + question.categories();
            strata.computeIfAbsent(key, _ -> new ArrayList<>()).add(question);
        }
        Random random = new Random(seed);
        List<EvalQuestion> calibration = new ArrayList<>();
        List<EvalQuestion> validation = new ArrayList<>();
        for (List<EvalQuestion> stratum : strata.values()) {
            List<EvalQuestion> shuffled = new ArrayList<>(stratum);
            java.util.Collections.shuffle(shuffled, random);
            for (int i = 0; i < shuffled.size(); i++) {
                (i % 2 == 0 ? calibration : validation).add(shuffled.get(i));
            }
        }
        return new Split(List.copyOf(calibration), List.copyOf(validation));
    }

    public static ThresholdSelection selectThreshold(List<SufficiencyObservation> observations) {
        long positives = observations.stream().filter(SufficiencyObservation::positive).count();
        long negatives = observations.size() - positives;
        if (positives == 0) {
            throw new IllegalArgumentException("At least one positive observation is required");
        }
        if (negatives == 0) {
            throw new IllegalArgumentException("At least one negative observation is required");
        }
        TreeSet<Double> scores = new TreeSet<>(observations.stream().map(SufficiencyObservation::topCosine).toList());
        List<Double> candidates = new ArrayList<>();
        candidates.add(scores.first() - 0.000001);
        Double previous = null;
        for (double score : scores) {
            if (previous != null) {
                candidates.add((previous + score) / 2);
            }
            previous = score;
        }
        candidates.add(scores.last() + 0.000001);
        return candidates.stream().map(candidate -> assess(observations, candidate, scores))
                .max(Comparator.comparingDouble((ThresholdSelection selection) ->
                                selection.truePositiveRate() - selection.falsePositiveRate())
                        .thenComparingDouble(ThresholdSelection::margin))
                .orElseThrow();
    }

    private static ThresholdSelection assess(List<SufficiencyObservation> observations, double threshold,
                                             TreeSet<Double> scores) {
        long positives = observations.stream().filter(SufficiencyObservation::positive).count();
        long negatives = observations.size() - positives;
        long truePositives = observations.stream().filter(SufficiencyObservation::positive)
                .filter(observation -> observation.topCosine() >= threshold).count();
        long falsePositives = observations.stream().filter(observation -> !observation.positive())
                .filter(observation -> observation.topCosine() >= threshold).count();
        Double lower = scores.floor(threshold);
        Double higher = scores.ceiling(threshold);
        double margin = Math.min(lower == null ? Double.MAX_VALUE : Math.abs(threshold - lower),
                higher == null ? Double.MAX_VALUE : Math.abs(higher - threshold));
        return new ThresholdSelection(threshold, truePositives / (double) positives,
                falsePositives / (double) negatives, margin);
    }

    public record Split(List<EvalQuestion> calibration, List<EvalQuestion> validation) {
    }

    public record SufficiencyObservation(String questionId, boolean positive, double topCosine) {
    }

    public record ThresholdSelection(double threshold, double truePositiveRate, double falsePositiveRate,
                                     double margin) {
    }
}
