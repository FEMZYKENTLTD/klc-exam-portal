package com.femzyk.klc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exam scoring arithmetic tests (rules 9, 10): deterministic fixtures
 * for grading - raw score floor at zero, negative marking, percentages
 * and the empty-exam edge case.
 */
class ExamScoringTest {

    @Test
    void allCorrectScoresFullMarks() {
        ExamScoring.Result r = ExamScoring.compute(20, 0, 0, 0.0, 20);
        assertEquals(20.0, r.rawScore, 0.0001);
        assertEquals(100.0, r.percentage, 0.0001);
        assertEquals(0, r.unanswered);
    }

    @Test
    void halfCorrectIsHalfMarks() {
        ExamScoring.Result r = ExamScoring.compute(5, 5, 0, 0.0, 10);
        assertEquals(5.0, r.rawScore, 0.0001);
        assertEquals(50.0, r.percentage, 0.0001);
        assertEquals(5, r.correct);
        assertEquals(5, r.wrong);
    }

    @Test
    void unansweredQuestionsDilutePercentage() {
        // 4 correct, 1 wrong, 5 unanswered out of 10
        ExamScoring.Result r = ExamScoring.compute(4, 1, 5, 0.0, 10);
        assertEquals(4.0, r.rawScore, 0.0001);
        assertEquals(40.0, r.percentage, 0.0001);
        assertEquals(5, r.unanswered);
    }

    @Test
    void negativeMarkingReducesRawScore() {
        // 2 correct, 4 wrong, 4 total, 0.5 deducted per wrong answer
        ExamScoring.Result r = ExamScoring.compute(2, 4, 0, 0.5, 6);
        assertEquals(0.0, r.rawScore, 0.0001); // 2 - (4 * 0.5) = 0
        assertEquals(0.0, r.percentage, 0.0001);
    }

    @Test
    void rawScoreNeverGoesBelowZero() {
        ExamScoring.Result r = ExamScoring.compute(0, 10, 0, 1.0, 10);
        assertEquals(0.0, r.rawScore, 0.0001);
        assertEquals(0.0, r.percentage, 0.0001);
    }

    @Test
    void partialNegativeMarkingAllowed() {
        // 8 correct, 2 wrong at 0.25 => 8 - 0.5 = 7.5 of 10 = 75%
        ExamScoring.Result r = ExamScoring.compute(8, 2, 0, 0.25, 10);
        assertEquals(7.5, r.rawScore, 0.0001);
        assertEquals(75.0, r.percentage, 0.0001);
    }

    @Test
    void emptyExamIsZeroPercentNotDivisionByZero() {
        ExamScoring.Result r = ExamScoring.compute(0, 0, 0, 0.0, 0);
        assertEquals(0.0, r.percentage, 0.0001);
        assertEquals(0.0, r.rawScore, 0.0001);
    }
}
