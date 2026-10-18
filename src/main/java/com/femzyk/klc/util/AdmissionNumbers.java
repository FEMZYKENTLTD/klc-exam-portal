package com.femzyk.klc.util;

/**
 * AdmissionNumbers — school-configurable admission-number format.
 *
 * <p>Config key {@code admission.format} (config.properties), default
 * {@code KLC/{CLASS}/{SEQ:3}}. Supported tokens:
 * <ul>
 *   <li>{@code {CLASS}} — class level as-is (e.g. SS1); {@code {CLASS:lower}} lower-case</li>
 *   <li>{@code {SEQ}} / {@code {SEQ:n}} — next sequence, zero-padded to n digits (default 3)</li>
 *   <li>{@code {SESSION}} — session start year (2024/2025 → 2024); {@code {YY}} → 24</li>
 *   <li>{@code {ARM}} — class arm (empty when none)</li>
 * </ul>
 * Unknown tokens are left untouched so a typo is visible, not silent.
 */
public final class AdmissionNumbers {

    public static final String DEFAULT_FORMAT = "KLC/{CLASS}/{SEQ:3}";

    private AdmissionNumbers() {}

    public static String format(String pattern, String classLevel, String arm,
                                String session, int sequence) {
        String p = (pattern == null || pattern.isBlank()) ? DEFAULT_FORMAT : pattern.trim();
        String cls = classLevel == null ? "" : classLevel.trim();
        String yr = "";
        if (session != null && session.length() >= 4) yr = session.substring(0, 4);
        String yy = yr.length() == 4 ? yr.substring(2) : "";

        return applySeq(p, cls, arm, yr, yy, sequence);
    }

    private static String applySeq(String p, String cls, String arm,
                                   String yr, String yy, int seq) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            int open = p.indexOf('{', i);
            if (open < 0) { sb.append(p, i, p.length()); break; }
            int close = p.indexOf('}', open);
            if (close < 0) { sb.append(p, i, p.length()); break; }
            sb.append(p, i, open);
            String tok = p.substring(open + 1, close);
            switch (tok) {
                case "CLASS" -> sb.append(cls);
                case "CLASS:lower" -> sb.append(cls.toLowerCase());
                case "CLASS:upper" -> sb.append(cls.toUpperCase());
                case "ARM" -> sb.append(arm == null ? "" : arm.trim());
                case "SESSION" -> sb.append(yr);
                case "YY" -> sb.append(yy);
                case "SEQ" -> sb.append(pad(seq, 3));
                default -> {
                    if (tok.startsWith("SEQ:")) {
                        try { sb.append(pad(seq, Integer.parseInt(tok.substring(4)))); }
                        catch (NumberFormatException e) { sb.append('{').append(tok).append('}'); }
                    } else sb.append('{').append(tok).append('}');
                }
            }
            i = close + 1;
        }
        // Collapse duplicate separators left by an empty {ARM}.
        return sb.toString().replaceAll("/{2,}", "/");
    }

    static String pad(int seq, int width) { return pad(seq, String.valueOf(width)); }
    static String pad(int seq, String width) {
        int w;
        try { w = Math.max(1, Math.min(10, Integer.parseInt(width))); }
        catch (NumberFormatException e) { w = 3; }
        return String.format("%0" + w + "d", seq);
    }

    /** Configured pattern from AppConfig, falling back to the default. */
    public static String configuredPattern() {
        try {
            String v = AppConfig.properties().getProperty("admission.format");
            return (v == null || v.isBlank()) ? DEFAULT_FORMAT : v.trim();
        } catch (Exception e) { return DEFAULT_FORMAT; }
    }
}
