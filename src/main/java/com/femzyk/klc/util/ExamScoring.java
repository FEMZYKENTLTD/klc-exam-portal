package com.femzyk.klc.util;

/**
 * ExamScoring - pure, deterministic exam scoring arithmetic used by the
 * ExamController when an exam is submitted.
 *
 * <p>Extracted from ExamController.submitExam() so the critical scoring
 * business rule is unit-testable without a JavaFX toolkit:
 * <pre>
 *   raw  = max(0, correct - wrong * negativeMarking)
 *   pct  = raw * 100 / totalQuestions   (0 when totalQuestions == 0)
 * </pre>
 *
 * <p>Behaviour is identical to the original inline code; no rounding is
 * applied here (presentation layers format the values).
 */
public final class ExamScoring {

    private ExamScoring() {}

    /** Immutable result of a scored exam submission. */
    public static final class Result {
        public final int    correct;
        public final int    wrong;
        public final int    unanswered;
        public final double rawScore;
        public final double percentage;

        Result(int correct, int wrong, int unanswered,
               double rawScore, double percentage) {
            this.correct    = correct;
            this.wrong      = wrong;
            this.unanswered = unanswered;
            this.rawScore   = rawScore;
            this.percentage = percentage;
        }
    }

    /**
     * @param correct        number of questions answered correctly
     * @param wrong          number answered incorrectly
     * @param unanswered     number left unanswered
     * @param negativeMarking points deducted per wrong answer (0 = none)
     * @param totalQuestions total number of questions on the exam
     */
    public static Result compute(int correct, int wrong, int unanswered,
                                 double negativeMarking, int totalQuestions) {
        double raw = Math.max(0,
            correct - (wrong * negativeMarking));
        double pct = totalQuestions <= 0
            ? 0.0
            : raw * 100.0 / totalQuestions;
        return new Result(correct, wrong, unanswered, raw, pct);
    }
}
