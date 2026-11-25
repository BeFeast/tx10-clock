package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/** Deterministic calendar-matrix and adjacent-month styling contract. */
public class ClockRendererCalendarTest {

    @Test
    public void everyCalendarIsFortyTwoConsecutiveSundayFirstDates() {
        YearMonth[] fixtures = {
                YearMonth.of(2022, 5),  // begins Sunday
                YearMonth.of(2026, 8),  // needs a sixth natural row
                YearMonth.of(2026, 1),  // previous-year spillover
                YearMonth.of(2026, 12), // next-year spillover
                YearMonth.of(2024, 2),  // leap February
                YearMonth.of(2023, 2)   // non-leap February
        };

        for (YearMonth month : fixtures) {
            LocalDate[] dates = ClockRenderer.calendarDates(month);
            assertEquals(month + " has exactly 42 cells", 42, dates.length);
            assertEquals(month + " starts on Sunday", DayOfWeek.SUNDAY,
                    dates[0].getDayOfWeek());
            for (int cell = 1; cell < dates.length; cell++) {
                assertEquals(month + " cell " + cell + " is consecutive",
                        dates[cell - 1].plusDays(1), dates[cell]);
            }
            assertTrue(month + " includes displayed month",
                    !dates[0].isAfter(month.atDay(1))
                            && !dates[41].isBefore(month.atEndOfMonth()));
        }
    }

    @Test
    public void sundayStartHasNoLeadingSpillover() {
        YearMonth month = YearMonth.of(2022, 5);
        LocalDate[] dates = ClockRenderer.calendarDates(month);
        assertEquals(month.atDay(1), dates[0]);
        assertEquals(YearMonth.of(2022, 6), YearMonth.from(dates[41]));
    }

    @Test
    public void sixRowMonthKeepsItsLastDayInTheSixthRow() {
        YearMonth month = YearMonth.of(2026, 8);
        LocalDate[] dates = ClockRenderer.calendarDates(month);
        int lastDayCell = -1;
        for (int cell = 0; cell < dates.length; cell++) {
            if (dates[cell].equals(month.atEndOfMonth())) {
                lastDayCell = cell;
            }
        }
        assertTrue("August 2026 ends in row six", lastDayCell >= 35);
    }

    @Test
    public void yearBoundariesAndFebruaryLengthsAreCorrect() {
        assertEquals(LocalDate.of(2025, 12, 28),
                ClockRenderer.calendarDates(YearMonth.of(2026, 1))[0]);
        assertEquals(29, YearMonth.of(2024, 2).lengthOfMonth());
        assertEquals(28, YearMonth.of(2023, 2).lengthOfMonth());
        assertEquals(YearMonth.of(2027, 1), YearMonth.from(
                ClockRenderer.calendarDates(YearMonth.of(2026, 12))[41]));
    }

    @Test
    public void adjacentMonthDateCannotReceiveTodayAccent() {
        YearMonth displayed = YearMonth.of(2026, 7);
        LocalDate adjacent = LocalDate.of(2026, 6, 30);
        assertEquals(ClockRenderer.CALENDAR_ROLE_ADJACENT,
                ClockRenderer.calendarCellRole(adjacent, displayed, adjacent));
        assertEquals(ClockRenderer.CALENDAR_ROLE_TODAY,
                ClockRenderer.calendarCellRole(LocalDate.of(2026, 7, 12), displayed,
                        LocalDate.of(2026, 7, 12)));
        assertEquals(ClockRenderer.CALENDAR_ROLE_CURRENT_MONTH,
                ClockRenderer.calendarCellRole(LocalDate.of(2026, 7, 13), displayed,
                        LocalDate.of(2026, 7, 12)));
    }
}
