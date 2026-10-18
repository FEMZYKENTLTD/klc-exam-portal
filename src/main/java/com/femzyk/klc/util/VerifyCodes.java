package com.femzyk.klc.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * VerifyCodes — tamper-evident result verification codes printed on result
 * slips / stored in {@code results.qr_verify_code}. Anyone with the code can
 * be checked against a recomputation: the code is a truncated SHA-256 over
 * the result facts + a school secret ({@code results.verify_secret} in
 * config.properties), so editing a score/PDF invalidates the code without a
 * database lookup, and the full lookup (parent checker) confirms it exists.
 */
public final class VerifyCodes {

    private VerifyCodes() {}

    /** 12-char grouped code, e.g. {@code KLC-3F9A-22BC-90DE}. */
    public static String forResult(String studentId, String attemptId,
                                   double percentage, String secret) {
        String payload = nullSafe(studentId) + "|" + nullSafe(attemptId) + "|"
            + String.format(java.util.Locale.ROOT, "%.2f", percentage) + "|"
            + nullSafe(secret);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++)
                hex.append(String.format("%02X", hash[i]));
            return "KLC-" + hex.substring(0, 4) + "-" + hex.substring(4, 8)
                 + "-" + hex.substring(8, 12);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean matches(String code, String studentId, String attemptId,
                                  double percentage, String secret) {
        return code != null && code.trim().equalsIgnoreCase(
            forResult(studentId, attemptId, percentage, secret));
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }
}
