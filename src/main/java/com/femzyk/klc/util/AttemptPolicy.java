package com.femzyk.klc.util;

/**
 * AttemptPolicy — exam attempt-limit rule (pure, unit-tested).
 *
 * <p>{@code exams.attempt_limit} (default 1) caps how many times a student may
 * SUBMIT an official exam. Practice exams are unlimited; mock exams follow
 * the same limit as official ones (they simulate the real sitting). A limit
 * of 0 or negative means "no limit configured" and is treated as unlimited
 * for backward compatibility with pre-limit exam rows.
 */
public final class AttemptPolicy {

    private AttemptPolicy() {}

    public static boolean canStart(int attemptLimit, long submittedAttempts,
                                   boolean isPractice) {
        if (isPractice) return true;
        if (attemptLimit <= 0) return true;
        return submittedAttempts < attemptLimit;
    }

    public static String denialMessage(int attemptLimit, long submittedAttempts) {
        return "Attempt limit reached: this exam allows " + attemptLimit
            + " attempt(s) and you have already submitted " + submittedAttempts
            + ". Contact your teacher or the Exam Officer if you need a retake.";
    }
}
