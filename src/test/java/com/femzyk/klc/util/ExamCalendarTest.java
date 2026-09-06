package com.femzyk.klc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exam-calendar + holiday tests (directive F6). Uses fixed statutory
 * Nigerian holidays so assertions are deterministic.
 */
class ExamCalendarTest {

    private final SchoolHolidays holidays =
        new SchoolHolidays(true, List.of("2026-03-02", "2026-03-03"));

    @Test
    void statutoryHolidaysAreDetected() {
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 1, 1)), "New Year");
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 5, 1)), "Workers Day");
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 6, 12)),
            "Democracy Day");
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 10, 1)),
            "Independence");
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 12, 25)), "Xmas");
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 12, 26)),
            "Boxing Day");
    }

    @Test
    void configuredExtraDatesCountAsHolidays() {
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 3, 2)));
        assertTrue(holidays.isHoliday(LocalDate.of(2026, 3, 3)));
        assertFalse(holidays.isHoliday(LocalDate.of(2026, 3, 4)));
    }

    @Test
    void sundayIsNotASchoolDayUnlessConfigured() {
        LocalDate sunday = LocalDate.of(2026, 9, 6); // a Sunday
        assertFalse(holidays.isSchoolDay(sunday));
        SchoolHolidays noSundayOff = new SchoolHolidays(false, List.of());
        assertTrue(noSundayOff.isSchoolDay(sunday));
    }

    @Test
    void nextSchoolDaysSkipsWeekendsAndHolidays() {
        // Friday 2026-09-04 -> school days: Fri 4, Mon 7, Tue 8 (skips
        // weekend); add extra holiday Mon 2026-09-07? not configured here.
        List<LocalDate> days = holidays.nextSchoolDays(
            LocalDate.of(2026, 9, 4), 3);
        assertEquals(LocalDate.of(2026, 9, 4), days.get(0));
        assertEquals(LocalDate.of(2026, 9, 7), days.get(1));
        assertEquals(LocalDate.of(2026, 9, 8), days.get(2));
    }

    @Test
    void windowCountsExamsPerDayAndFlagsOverload() {
        List<ExamCalendar.ExamOn> exams = new ArrayList<>();
        exams.add(new ExamCalendar.ExamOn("e1", "Mathematics", "SS1",
            LocalDate.of(2026, 9, 8)));
        exams.add(new ExamCalendar.ExamOn("e2", "English", "SS1",
            LocalDate.of(2026, 9, 8)));
        exams.add(new ExamCalendar.ExamOn("e3", "Physics", "SS2",
            LocalDate.of(2026, 9, 8)));
        exams.add(new ExamCalendar.ExamOn("e4", "Chemistry", "SS2",
            LocalDate.of(2026, 9, 9)));

        List<ExamCalendar.DaySlot> slots = ExamCalendar.buildWindow(
            LocalDate.of(2026, 9, 7), 5, holidays, exams, 2);
        // Mon 7 (0), Tue 8 (3 - overloaded), Wed 9 (1), Thu 10 (0),
        // Fri 11 (0)  -- Sunday 13 is outside the window
        assertEquals(5, slots.size());
        assertEquals(3, slots.get(1).examCount);
        assertTrue(slots.get(1).overloaded);
        assertFalse(slots.get(2).overloaded);
        assertEquals(1, slots.get(2).examCount);
        assertTrue(slots.get(0).schoolDay);
    }

    @Test
    void sortedExamsOrderByDateThenTitle() {
        List<ExamCalendar.ExamOn> exams = new ArrayList<>();
        exams.add(new ExamCalendar.ExamOn("b", "Zoo", "JSS1",
            LocalDate.of(2026, 9, 10)));
        exams.add(new ExamCalendar.ExamOn("a", "Alpha", "JSS1",
            LocalDate.of(2026, 9, 9)));
        exams.add(new ExamCalendar.ExamOn("c", "Beta", "JSS1",
            LocalDate.of(2026, 9, 9)));
        List<ExamCalendar.ExamOn> out = ExamCalendar.sorted(exams);
        assertEquals("Alpha", out.get(0).title);
        assertEquals("Beta", out.get(1).title);
        assertEquals("Zoo", out.get(2).title);
    }
}
