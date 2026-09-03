package com.femzyk.klc.util;

import java.security.SecureRandom;

/**
 * KLC v1.0: one-time password generation.
 *
 * Used by Teacher Bulk Import and Teacher Manager "Reset password":
 * fixed weak defaults (published in the repo, see SECURITY_CREDENTIALS.md)
 * are gone - passwords are now config-driven or random and shown ONCE on
 * screen.
 */
public final class PasswordGen {
    // unambiguous alphabet - no 0/O, 1/l/I
    private static final String ALPHABET =
        "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGen() {}

    /** Strong random password of the requested length (minimum 8). */
    public static String strong(int len) {
        if (len < 8) len = 8;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Default password for teacher bulk import:
     * {@code import.default_password} from config.properties when set to a
     * real value, otherwise a fresh random password.
     */
    public static String defaultImportPassword() {
        String v = ConfigService.get("import.default_password", "");
        if (v.isEmpty() || v.startsWith("YOUR_")) return strong(12);
        return v;
    }
}
