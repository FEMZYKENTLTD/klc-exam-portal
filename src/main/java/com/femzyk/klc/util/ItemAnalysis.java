package com.femzyk.klc.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ItemAnalysis — classical test-theory psychometrics per question, as pure
 * arithmetic (unit-testable, no DB/JavaFX):
 *
 * <ul>
 *   <li><b>Difficulty (p-value)</b>: fraction of candidates who answered
 *       correctly. 0.30–0.80 is the WAEC/NECO target band; &lt;0.20 too hard,
 *       &gt;0.90 too easy.</li>
 *   <li><b>Discrimination (D)</b>: correct-rate of the top 27% of candidates
 *       (by total score) minus correct-rate of the bottom 27% (Kelley 1939).
 *       ≥0.40 excellent, ≥0.30 good, ≥0.20 acceptable, &lt;0.20 poor,
 *       negative = mis-keyed/suspect question.</li>
 *   <li><b>Distractor spread</b>: share choosing each wrong option; a wrong
 *       option nobody picks (&lt;5%) is a dead distractor.</li>
 * </ul>
 */
public final class ItemAnalysis {

    private ItemAnalysis() {}

    /** One candidate's outcome on one question. */
    public record Response(double candidateTotalScore, boolean correct, String chosenOption) {}

    public record ItemStats(int attempts, int correctCount, double difficulty,
                            double discrimination, String difficultyBand,
                            String discriminationBand,
                            Map<String, Long> optionCounts,
                            List<String> deadDistractors) {}

    public static double difficulty(int correct, int attempts) {
        return attempts <= 0 ? 0.0 : (double) correct / attempts;
    }

    public static String difficultyBand(double p) {
        if (p < 0.20) return "TOO HARD";
        if (p < 0.30) return "HARD";
        if (p <= 0.80) return "OPTIMAL";
        if (p <= 0.90) return "EASY";
        return "TOO EASY";
    }

    public static String discriminationBand(double d) {
        if (d < 0) return "SUSPECT - check answer key";
        if (d < 0.20) return "POOR - review this question";
        if (d < 0.30) return "ACCEPTABLE";
        if (d < 0.40) return "GOOD";
        return "EXCELLENT";
    }

    /** Full stats for one question from its responses. */
    public static ItemStats analyse(List<Response> responses, List<String> allOptions) {
        int attempts = responses.size();
        int correct = (int) responses.stream().filter(Response::correct).count();
        double p = difficulty(correct, attempts);

        // Kelley 27% upper/lower groups by total score.
        List<Response> sorted = new ArrayList<>(responses);
        sorted.sort(Comparator.comparingDouble(Response::candidateTotalScore).reversed());
        int group = Math.max(1, (int) Math.round(attempts * 0.27));
        double topRate = rate(sorted.subList(0, Math.min(group, attempts)));
        double botRate = rate(sorted.subList(Math.max(0, attempts - group), attempts));
        double d = attempts == 0 ? 0.0 : topRate - botRate;

        Map<String, Long> counts = new java.util.TreeMap<>();
        for (Response r : responses)
            if (r.chosenOption() != null) counts.merge(r.chosenOption(), 1L, Long::sum);
        List<String> dead = new ArrayList<>();
        if (allOptions != null && attempts >= 10) {
            for (String opt : allOptions) {
                long c = counts.getOrDefault(opt, 0L);
                // correct option excluded by caller passing only distractors, or
                // a distractor chosen by <5% is dead weight.
                if (c * 100.0 / attempts < 5.0) dead.add(opt);
            }
        }
        return new ItemStats(attempts, correct, p, d,
            difficultyBand(p), discriminationBand(d), counts, List.copyOf(dead));
    }

    private static double rate(List<Response> rs) {
        if (rs.isEmpty()) return 0.0;
        return (double) rs.stream().filter(Response::correct).count() / rs.size();
    }
}
