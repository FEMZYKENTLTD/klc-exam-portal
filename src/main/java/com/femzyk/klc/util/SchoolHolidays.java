package com.femzyk.klc.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SchoolHolidays - holiday-aware school-day calculation for the exam
 * calendar (directive F6). Statutory Nigerian public holidays are built in
 * for the requested year; the school can add its own dates (term breaks,
 * Sallah/Easter when they move, local festivals) through
 * {@code calendar.holidays} in config.properties as a comma-separated list
 * of yyyy-MM-dd dates.
 *
 * By default Sundays are not school days (both H2 offline and cloud
 * deployments are Nigerian secondary schools); this can be switched off
 * through the constructor for schools that run Sunday sessions.
 */
public final class SchoolHolidays {

    private final Set<MonthDay> fixedHolidays = new LinkedHashSet<>();
    private final Set<LocalDate> extraDates   = new LinkedHashSet<>();
    private final boolean sundaysOff;

    public SchoolHolidays(boolean sundaysOff, Iterable<String> extraIsoDates) {
        this.sundaysOff = sundaysOff;
        // Statutory Nigerian public holidays that fall on fixed dates.
        fixedHolidays.add(MonthDay.of(1, 1));   // New Year's Day
        fixedHolidays.add(MonthDay.of(5, 1));   // Workers' Day
        fixedHolidays.add(MonthDay.of(6, 12));  // Democracy Day
        fixedHolidays.add(MonthDay.of(10, 1));  // Independence Day
        fixedHolidays.add(MonthDay.of(12, 25)); // Christmas Day
        fixedHolidays.add(MonthDay.of(12, 26)); // Boxing Day
        if (extraIsoDates != null) {
            for (String d : extraIsoDates) {
                if (d == null || d.isBlank()) continue;
                try {
                    extraDates.add(LocalDate.parse(d.trim()));
                } catch (Exception ignored) {
                    // bad config entry - ignore (never fails the app)
                }
            }
        }
    }

    public boolean isHoliday(LocalDate d) {
        if (d == null) return false;
        if (extraDates.contains(d)) return true;
        if (fixedHolidays.contains(MonthDay.from(d))) return true;
        return sundaysOff && d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** True when the date is a school day. */
    public boolean isSchoolDay(LocalDate d) {
        return d != null && !isHoliday(d);
    }

    /** The next {@code count} school days at/after {@code from} (inclusive). */
    public java.util.List<LocalDate> nextSchoolDays(LocalDate from, int count) {
        java.util.List<LocalDate> out = new java.util.ArrayList<>();
        LocalDate d = from;
        while (out.size() < count) {
            if (isSchoolDay(d)) out.add(d);
            d = d.plusDays(1);
        }
        return out;
    }
}
