package com.sber.dlmm.common.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 5 #5.10 — locks the calendar rules. Pure stateless service,
 * no Spring needed.
 */
class BankingCalendarServiceTest {

    private BankingCalendarService cal;

    @BeforeEach
    void setUp() {
        cal = new BankingCalendarService();
    }

    @Nested
    @DisplayName("isWorkingDay")
    class WorkingDayTests {

        @Test
        @DisplayName("regular Tuesday is a working day")
        void tuesdayIsWorking() {
            // 2026-05-19 is a Tuesday, not a holiday
            assertTrue(cal.isWorkingDay(LocalDate.of(2026, 5, 19)));
        }

        @Test
        @DisplayName("Saturday is non-working")
        void saturday() {
            // 2026-05-16 is a Saturday
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 16)));
        }

        @Test
        @DisplayName("Sunday is non-working")
        void sunday() {
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 17)));
        }

        @Test
        @DisplayName("Новогодние каникулы (1-8 января) all non-working regardless of weekday")
        void newYearHolidays() {
            for (int day = 1; day <= 8; day++) {
                assertFalse(cal.isWorkingDay(LocalDate.of(2026, 1, day)),
                        "Jan " + day + " 2026 must be a holiday");
            }
        }

        @Test
        @DisplayName("9 мая (День Победы) non-working")
        void victoryDay() {
            // 9 May 2026 is a Saturday anyway, but also a federal holiday
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 9)));
            // Different year where 9 May is a weekday — 2025-05-09 was Friday
            assertFalse(cal.isWorkingDay(LocalDate.of(2025, 5, 9)));
        }

        @Test
        @DisplayName("12 июня (День России) non-working")
        void russiaDay() {
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 6, 12)));
        }

        @Test
        @DisplayName("4 ноября (День народного единства) non-working")
        void unityDay() {
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 11, 4)));
        }

        @Test
        @DisplayName("23 февраля (Защитника Отечества) non-working")
        void defenderOfFatherlandDay() {
            assertFalse(cal.isWorkingDay(LocalDate.of(2026, 2, 23)));
        }

        @Test
        @DisplayName("null returns false (defensive)")
        void nullReturnsFalse() {
            assertFalse(cal.isWorkingDay(null));
        }
    }

    @Nested
    @DisplayName("nextWorkingDay")
    class NextWorkingDayTests {

        @Test
        @DisplayName("Friday → next Monday")
        void fridayToMonday() {
            // 2026-05-15 = Friday, 2026-05-18 = Monday
            assertEquals(LocalDate.of(2026, 5, 18),
                    cal.nextWorkingDay(LocalDate.of(2026, 5, 15)));
        }

        @Test
        @DisplayName("Tuesday → Wednesday (skip nothing)")
        void tuesdayToWednesday() {
            assertEquals(LocalDate.of(2026, 5, 20),
                    cal.nextWorkingDay(LocalDate.of(2026, 5, 19)));
        }

        @Test
        @DisplayName("31 декабря → next working = после NG каникул")
        void newYearJump() {
            // 2025-12-31 was Wed → next working day jumps past Jan 1-8 (holidays).
            // Next is 9 Jan 2026 = Friday → working day.
            assertEquals(LocalDate.of(2026, 1, 9),
                    cal.nextWorkingDay(LocalDate.of(2025, 12, 31)));
        }

        @Test
        @DisplayName("returns Monday when input is Friday, even if Friday IS working day")
        void alwaysStrictlyAfter() {
            // Even if input is itself working, result is strictly AFTER.
            LocalDate friday = LocalDate.of(2026, 5, 15);
            assertTrue(cal.isWorkingDay(friday));
            LocalDate result = cal.nextWorkingDay(friday);
            assertTrue(result.isAfter(friday));
        }
    }

    @Nested
    @DisplayName("addWorkingDays")
    class AddWorkingDaysTests {

        @Test
        @DisplayName("T+0 returns start date itself")
        void zeroDays() {
            LocalDate d = LocalDate.of(2026, 5, 19);
            assertEquals(d, cal.addWorkingDays(d, 0));
        }

        @Test
        @DisplayName("T+1 from Tuesday → Wednesday")
        void onePlusFromTuesday() {
            assertEquals(LocalDate.of(2026, 5, 20),
                    cal.addWorkingDays(LocalDate.of(2026, 5, 19), 1));
        }

        @Test
        @DisplayName("T+2 from Friday skips weekend → Tuesday")
        void twoPlusFromFriday() {
            // Fri 2026-05-15 + 2 working = Mon (18), Tue (19)
            assertEquals(LocalDate.of(2026, 5, 19),
                    cal.addWorkingDays(LocalDate.of(2026, 5, 15), 2));
        }

        @Test
        @DisplayName("T+1 from 30 декабря jumps past NG каникулы to 9 января")
        void newYearJumpAddWorkingDays() {
            // 2025-12-30 = Tue → +1 working day. 31 was Wed (working).
            assertEquals(LocalDate.of(2025, 12, 31),
                    cal.addWorkingDays(LocalDate.of(2025, 12, 30), 1));
            // T+2: skip Jan 1-8 (all holidays), land on Jan 9 (Fri)
            assertEquals(LocalDate.of(2026, 1, 9),
                    cal.addWorkingDays(LocalDate.of(2025, 12, 30), 2));
        }

        @Test
        @DisplayName("negative input throws IllegalArgumentException")
        void negativeThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> cal.addWorkingDays(LocalDate.now(), -1));
        }
    }
}
