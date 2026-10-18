package com.femzyk.klc.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RankingItemAdmissionTest {

    @Test void tiesSharePositionAndSkip() {
        assertEquals(List.of(1, 2, 2, 4, 5),
            Ranking.positions(List.of(90.0, 80.0, 80.0, 70.0, 60.0)));
    }

    @Test void positionsFollowInputOrderNotSortedOrder() {
        assertEquals(List.of(3, 1, 2), Ranking.positions(List.of(50.0, 90.0, 70.0)));
    }

    @Test void emptyAndSingle() {
        assertEquals(List.of(), Ranking.positions(List.of()));
        assertEquals(List.of(1), Ranking.positions(List.of(42.0)));
    }

    @Test void ordinals() {
        assertEquals("1st", Ranking.ordinal(1));
        assertEquals("2nd", Ranking.ordinal(2));
        assertEquals("3rd", Ranking.ordinal(3));
        assertEquals("4th", Ranking.ordinal(4));
        assertEquals("11th", Ranking.ordinal(11));
        assertEquals("12th", Ranking.ordinal(12));
        assertEquals("13th", Ranking.ordinal(13));
        assertEquals("21st", Ranking.ordinal(21));
        assertEquals("23rd", Ranking.ordinal(23));
        assertEquals("111th", Ranking.ordinal(111));
    }

    @Test void difficultyBands() {
        assertEquals(0.5, ItemAnalysis.difficulty(5, 10));
        assertEquals(0.0, ItemAnalysis.difficulty(0, 0));
        assertEquals("OPTIMAL", ItemAnalysis.difficultyBand(0.5));
        assertEquals("TOO HARD", ItemAnalysis.difficultyBand(0.1));
        assertEquals("TOO EASY", ItemAnalysis.difficultyBand(0.95));
    }

    @Test void discriminationTopVsBottom() {
        // 10 candidates: top scorers all correct, bottom all wrong.
        List<ItemAnalysis.Response> rs = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++)
            rs.add(new ItemAnalysis.Response(100 - i * 10, i < 3, i < 3 ? "A" : "B"));
        ItemAnalysis.ItemStats s = ItemAnalysis.analyse(rs, List.of("B", "C", "D"));
        assertEquals(10, s.attempts());
        assertTrue(s.discrimination() > 0.9, "D should be ~1.0, was " + s.discrimination());
        assertEquals("EXCELLENT", s.discriminationBand());
        assertTrue(s.deadDistractors().contains("C"));
    }

    @Test void negativeDiscriminationFlagsSuspectKey() {
        List<ItemAnalysis.Response> rs = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++)
            rs.add(new ItemAnalysis.Response(100 - i * 10, i >= 7, i >= 7 ? "A" : "B"));
        ItemAnalysis.ItemStats s = ItemAnalysis.analyse(rs, List.of("B"));
        assertTrue(s.discrimination() < 0);
        assertTrue(s.discriminationBand().startsWith("SUSPECT"));
    }

    @Test void admissionDefaultFormat() {
        assertEquals("KLC/SS1/007",
            AdmissionNumbers.format(null, "SS1", "A", "2024/2025", 7));
        assertEquals("KLC/JSS3/123",
            AdmissionNumbers.format("", "JSS3", null, "2024/2025", 123));
    }

    @Test void admissionCustomFormat() {
        assertEquals("KLC-2024-ss1-42",
            AdmissionNumbers.format("KLC-{SESSION}-{CLASS:lower}-{SEQ:2}", "SS1", "A", "2024/2025", 42));
        assertEquals("24/A/0009",
            AdmissionNumbers.format("{YY}/{ARM}/{SEQ:4}", "SS1", "A", "2024/2025", 9));
        // empty arm collapses the duplicate separator
        assertEquals("KLC/SS1/001",
            AdmissionNumbers.format("KLC/{ARM}/{CLASS}/{SEQ:3}", "SS1", "", "2024/2025", 1));
        // unknown token stays visible
        assertTrue(AdmissionNumbers.format("{BOGUS}", "SS1", "", "", 1).contains("{BOGUS}"));
    }
}
