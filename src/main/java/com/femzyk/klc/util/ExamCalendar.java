package com.femzyk.klc.util;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ExamCalendar - builds a holiday-aware exam timetable window for the
 * "Exam Calendar" view (directive F6). Pure and unit tested: given the
 * school's holiday rules and the scheduled exams, it returns one
 * {@link DaySlot} per day in the window so the UI can render a calendar
 * strip (school days vs holidays, per-day exam load, overload warnings)
 * and an ordered exam schedule.
 */
public final class ExamCalendar {

    /** One exam occurrence on a day. */
    public static final class ExamOn {
        public final String examId;
        public final String title;
        public final String subjectClass;
        public final LocalDate date;
        public ExamOn(String examId, String title, String subjectClass,
                      LocalDate date) {
            this.examId = examId;
            this.title = title;
            this.subjectClass = subjectClass;
            this.date = date;
        }
    }

    /** One calendar day slot inside the window. */
    public static final class DaySlot {
        public final LocalDate date;
        public final boolean schoolDay;
        public final boolean holiday;
        public final int examCount;
        public final boolean overloaded; // more than maxPerDay exams
        public DaySlot(LocalDate date, boolean schoolDay, boolean holiday,
                       int examCount, boolean overloaded) {
            this.date = date;
            this.schoolDay = schoolDay;
            this.holiday = holiday;
            this.examCount = examCount;
            this.overloaded = overloaded;
        }
    }

    private ExamCalendar() {}

    /**
     * @param from      first day of the window
     * @param days      window length in days (>= 1)
     * @param holidays  school-day rules
     * @param exams     scheduled exams (only those inside the window count)
     * @param maxPerDay load above this is flagged overloaded
     * @return one DaySlot per day, in date order
     */
    public static List<DaySlot> buildWindow(LocalDate from, int days,
            SchoolHolidays holidays, List<ExamOn> exams, int maxPerDay) {
        List<DaySlot> slots = new ArrayList<>();
        LocalDate end = from.plusDays(Math.max(1, days) - 1L);
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            int count = 0;
            for (ExamOn e : exams) if (d.equals(e.date)) count++;
            boolean school = holidays.isSchoolDay(d);
            slots.add(new DaySlot(d, school, !school, count,
                school && count > maxPerDay));
        }
        return slots;
    }

    /** Exams sorted by date (stable: title order within a day). */
    public static List<ExamOn> sorted(List<ExamOn> exams) {
        List<ExamOn> out = new ArrayList<>(exams);
        out.sort((a, b) -> {
            int c = a.date.compareTo(b.date);
            return c != 0 ? c : a.title.compareTo(b.title);
        });
        return out;
    }
}
