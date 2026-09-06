package com.femzyk.klc.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.db.KlcTestDb;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Grading-scale tests with deterministic boundary fixtures (rules 9, 10):
 * the configured WAEC A1-F9 scale must map exact thresholds, and the
 * DB-stored custom scale must override the fallback.
 */
class GradingScaleTest {

    @BeforeAll
    static void bootstrap() {
        KlcTestDb.initialize();
    }

    @AfterEach
    void clearCustomScale() throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate(
                "UPDATE school_profile SET grading_scale = NULL");
        }
    }

    @Test
    void waecFallbackScaleBoundaries() throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            // input score -> expected WAEC grade (incl. every boundary)
            double[][] cases = {
                {100, 0}, {75, 0},       // A1 range
                {74, 1},  {70, 1},       // B2
                {69, 2},  {65, 2},       // B3
                {64, 3},  {60, 3},       // C4
                {59, 4},  {55, 4},       // C5
                {54, 5},  {50, 5},       // C6
                {49, 6},  {45, 6},       // D7
                {44, 7},  {40, 7},       // E8
                {39, 8},  {0, 8}         // F9
            };
            String[] expected =
                {"A1","B2","B3","C4","C5","C6","D7","E8","F9"};
            for (double[] cs : cases) {
                String grade = GradingScaleController.gradeFor(cs[0], c);
                assertEquals(expected[(int) cs[1]], grade,
                    "score " + cs[0] + " maps to " + expected[(int) cs[1]]);
            }
        }
    }

    @Test
    void configuredCustomScaleOverridesFallback() throws Exception {
        String custom = "{\"grades\":["
            + "{\"grade\":\"AA\",\"min\":70,\"max\":100,\"remark\":\"Top\"},"
            + "{\"grade\":\"BB\",\"min\":50,\"max\":69,\"remark\":\"Mid\"},"
            + "{\"grade\":\"CC\",\"min\":0,\"max\":49,\"remark\":\"Low\"}],"
            + "\"weights\":{\"ca1\":30,\"ca2\":10,\"exam\":60}}";
        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE school_profile SET grading_scale = '"
                + custom.replace("'", "''") + "'");

            assertEquals("AA", GradingScaleController.gradeFor(70.0, c));
            assertEquals("AA", GradingScaleController.gradeFor(100.0, c));
            assertEquals("BB", GradingScaleController.gradeFor(69.0, c));
            assertEquals("BB", GradingScaleController.gradeFor(50.0, c));
            assertEquals("CC", GradingScaleController.gradeFor(49.0, c));
            assertEquals("CC", GradingScaleController.gradeFor(0.0, c));
        }
    }

    @Test
    void defaultCaWeightsAreTwentyTwentySixty() throws Exception {
        try (Connection c = DatabaseManager.getConnection()) {
            assertArrayEquals(new int[]{20, 20, 60},
                GradingScaleController.getWeights(c));
        }
    }

    @Test
    void configuredWeightsOverrideDefaults() throws Exception {
        String custom = "{\"grades\":[],"
            + "\"weights\":{\"ca1\":30,\"ca2\":20,\"exam\":50}}";
        try (Connection c = DatabaseManager.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE school_profile SET grading_scale = '"
                + custom.replace("'", "''") + "'");
            assertArrayEquals(new int[]{30, 20, 50},
                GradingScaleController.getWeights(c));
        }
    }
}
