package com.femzyk.klc.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranking — tie-aware competition ranking ("1224" / standard competition
 * ranking), extracted so class positions are unit-testable and identical
 * everywhere they are computed (CA aggregation, broadsheets, results).
 *
 * <p>Rules: highest score = position 1; equal scores share a position; the
 * next distinct score skips the shared places (1, 2, 2, 4). Also provides
 * English ordinals (1st, 2nd, 3rd, 4th … 11th, 12th, 13th, 21st …).
 */
public final class Ranking {

    private Ranking() {}

    /** One ranked entry: original key + its 1-based competition position. */
    public record Ranked<T>(T key, double score, int position) {}

    /**
     * Rank entries by descending score. Input order is preserved among ties
     * (stable), so output is deterministic for a deterministic input.
     */
    public static <T> List<Ranked<T>> rankDescending(List<T> keys,
                                                     java.util.function.ToDoubleFunction<T> score) {
        List<Ranked<T>> sorted = new ArrayList<>();
        for (T k : keys) sorted.add(new Ranked<>(k, score.applyAsDouble(k), 0));
        sorted.sort(Comparator.comparingDouble((Ranked<T> r) -> r.score()).reversed());
        List<Ranked<T>> out = new ArrayList<>(sorted.size());
        int pos = 0;
        double prev = Double.NaN;
        for (int i = 0; i < sorted.size(); i++) {
            Ranked<T> r = sorted.get(i);
            if (Double.compare(r.score(), prev) != 0) { pos = i + 1; prev = r.score(); }
            out.add(new Ranked<>(r.key(), r.score(), pos));
        }
        return out;
    }

    /** Positions for a plain descending-or-unsorted score list, in input order. */
    public static List<Integer> positions(List<Double> scores) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) idx.add(i);
        List<Ranked<Integer>> ranked = rankDescending(idx, i -> scores.get(i));
        Integer[] byIndex = new Integer[scores.size()];
        for (Ranked<Integer> r : ranked) byIndex[r.key()] = r.position();
        List<Integer> out = new ArrayList<>(scores.size());
        for (Integer p : byIndex) out.add(p);
        return out;
    }

    /** 1 -&gt; "1st", 2 -&gt; "2nd", 11 -&gt; "11th", 23 -&gt; "23rd". */
    public static String ordinal(int n) {
        if (n <= 0) return String.valueOf(n);
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
