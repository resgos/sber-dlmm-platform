package com.sber.dlmm.common.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/**
 * Sprint 5 #5.10 — Russian banking calendar.
 *
 * <p>Two-layer rules:
 * <ol>
 *   <li>Weekly: Saturday + Sunday are non-working.</li>
 *   <li>Fixed federal holidays per Trudovoy Kodeks 112-ФЗ (Art. 112).</li>
 * </ol>
 *
 * <p><b>Known prototype limitations</b> (Sprint 6+ track to remediate):
 * <ul>
 *   <li>Government-issued annual "переносы" (Postanovlenie Pravitelstva
 *       transferring weekends to bridge holidays) are NOT modelled. E.g.
 *       for 2026 the government will probably move 31 Dec to a working day
 *       and absorb the bridge — our calendar will say Dec 31 is a
 *       working day regardless. For internal scheduling this is fine; for
 *       customer-facing "T+N рабочих дней" the production version needs
 *       an annual government-order parser. Tracked as Sprint 7+ debt.</li>
 *   <li>Religious holidays of non-Orthodox confessions are not modelled —
 *       these aren't federal banking holidays anyway.</li>
 *   <li>Regional holidays (e.g. Тат-Республика's Курбан-байрам) are
 *       not modelled — federal banking system doesn't observe them
 *       wholesale.</li>
 * </ul>
 *
 * <p>Service is a thin stateless bean — safe to inject anywhere, no
 * config, no DB hit. Wired via {@link DlmmCalendarAutoConfiguration}.
 */
public class BankingCalendarService {

    /**
     * Federal holidays per labor code 112-ФЗ Art. 112 (as of 2026). Stored
     * as MonthDay so the same set works year over year — government
     * transfers (see class javadoc) are NOT applied.
     */
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            // Новогодние каникулы (1-8 января) — 8 calendar days
            MonthDay.of(1, 1),
            MonthDay.of(1, 2),
            MonthDay.of(1, 3),
            MonthDay.of(1, 4),
            MonthDay.of(1, 5),
            MonthDay.of(1, 6),
            MonthDay.of(1, 7), // Рождество (Russian Orthodox Christmas)
            MonthDay.of(1, 8),
            MonthDay.of(2, 23), // День защитника Отечества
            MonthDay.of(3, 8),  // Международный женский день
            MonthDay.of(5, 1),  // Праздник весны и труда
            MonthDay.of(5, 9),  // День Победы
            MonthDay.of(6, 12), // День России
            MonthDay.of(11, 4)  // День народного единства
    );

    /**
     * Returns true if the given date is a banking working day (Mon-Fri
     * AND not a fixed federal holiday). Weekends and 14 fixed-date
     * federal holidays return false. Null-safe — null returns false.
     */
    public boolean isWorkingDay(LocalDate date) {
        if (date == null) return false;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !FIXED_HOLIDAYS.contains(MonthDay.from(date));
    }

    /** Inverse convenience — useful in scheduler guards and UI display. */
    public boolean isHoliday(LocalDate date) {
        return !isWorkingDay(date);
    }

    /**
     * Returns the next working day strictly AFTER {@code from} (so
     * {@code nextWorkingDay(friday)} returns Monday, never the same Friday).
     * Used by "T+N рабочих дней" settlement display.
     */
    public LocalDate nextWorkingDay(LocalDate from) {
        LocalDate d = from.plusDays(1);
        while (!isWorkingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * Adds {@code workingDays} working days to {@code start}. {@code start}
     * itself is NOT counted regardless of whether it's a working day —
     * matches the "T+N рабочих дней" Russian banking convention.
     * Throws on negative input.
     */
    public LocalDate addWorkingDays(LocalDate start, int workingDays) {
        if (workingDays < 0) {
            throw new IllegalArgumentException("workingDays must be non-negative, got " + workingDays);
        }
        LocalDate d = start;
        int remaining = workingDays;
        while (remaining > 0) {
            d = d.plusDays(1);
            if (isWorkingDay(d)) remaining--;
        }
        return d;
    }
}
