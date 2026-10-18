package com.femzyk.klc.util;

import java.time.Year;

/**
 * RetentionPolicy — NDPR-aligned data-retention rules (pure, unit-tested),
 * used by backup/health tooling to flag what may be archived or anonymised:
 *
 * <ul>
 *   <li>Academic results are kept <b>10 years</b> after the session ends,
 *       then become archive-eligible (read-only archive, never hard-deleted
 *       silently).</li>
 *   <li>Withdrawn/graduated student personal data becomes
 *       anonymisation-eligible <b>5 years</b> after leaving (names/contacts
 *       scrubbed; results remain, linked to the admission number).</li>
 *   <li>Audit logs are kept <b>7 years</b> (WORM on the cloud).</li>
 * </ul>
 */
public final class RetentionPolicy {

    public static final int RESULTS_ARCHIVE_YEARS = 10;
    public static final int PERSONAL_ANONYMISE_YEARS = 5;
    public static final int AUDIT_KEEP_YEARS = 7;

    private RetentionPolicy() {}

    /** Session start year from "2024/2025" (or bare "2024"); -1 if unknown. */
    public static int sessionStartYear(String session) {
        if (session == null) return -1;
        String s = session.trim();
        if (s.length() < 4) return -1;
        try { return Integer.parseInt(s.substring(0, 4)); }
        catch (NumberFormatException e) { return -1; }
    }

    public static boolean resultsArchiveDue(String session, int currentYear) {
        int y = sessionStartYear(session);
        return y > 0 && currentYear - (y + 1) >= RESULTS_ARCHIVE_YEARS;
    }

    public static boolean resultsArchiveDue(String session) {
        return resultsArchiveDue(session, Year.now().getValue());
    }

    /** Years since leaving school (graduation/withdrawal year). */
    public static boolean personalAnonymiseDue(int leavingYear, int currentYear) {
        return leavingYear > 0 && currentYear - leavingYear >= PERSONAL_ANONYMISE_YEARS;
    }

    public static boolean auditPurgeDue(int logYear, int currentYear) {
        return logYear > 0 && currentYear - logYear >= AUDIT_KEEP_YEARS;
    }
}
