package com.jelly.habittrack

import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.ReminderPlanner
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RulesTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val habit = Habit("h", "阅读", createdDate = "2026-11-01", reminderEnabled = true, reminderTimes = "09:00,14:00,20:00")
    private val plan = HabitPlan("h", "2026-11-01", 127, 30.0)
    @Test fun weeklyMask() { assertTrue(Rules.includes(2, day)); assertFalse(Rules.includes(1, day)) }
    @Test fun overnightQuietHours() {
        assertTrue(Rules.quiet(LocalTime.of(23, 30), LocalTime.of(23, 0), LocalTime.of(7, 0)))
        assertTrue(Rules.quiet(LocalTime.of(6, 59), LocalTime.of(23, 0), LocalTime.of(7, 0)))
        assertFalse(Rules.quiet(LocalTime.of(7, 0), LocalTime.of(23, 0), LocalTime.of(7, 0)))
    }
    @Test fun countPrecisionAndValidation() {
        assertEquals(0.3, Rules.progress(0.1, 0.2, "increment", 1.0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { Rules.progress(0.0, Double.NaN, "set", 1.0) }
    }
    @Test fun pendingDoesNotBreakCurrentStreak() {
        val s = Snapshot(listOf(habit), plans = listOf(plan), records = listOf(CheckIn("h", "2026-11-01", 30.0), CheckIn("h", "2026-11-02", 30.0)))
        assertEquals(2 to 2, s.streak(habit, day))
        assertEquals(0 to 2, s.copy(records = s.records + CheckIn("h", day.toString(), closed = true)).streak(habit, day))
    }
    @Test fun restDaysDoNotBreakStreakAndRulesKeepHistory() {
        val plans = listOf(plan.copy(days = 1), HabitPlan("h", "2026-11-04", 127, 60.0))
        val s = Snapshot(listOf(habit), plans = plans, records = listOf(CheckIn("h", "2026-11-02", 30.0)))
        assertEquals(1 to 1, s.streak(habit, day))
        assertEquals(30.0, s.plan(habit, day)!!.target, 0.0)
    }
    @Test fun multipleTimesAndRepeatsDeduplicate() {
        val now = day.atTime(9, 7).atZone(ZoneId.of("Asia/Shanghai"))
        val s = Snapshot(listOf(habit), plans = listOf(plan))
        val r = ReminderPlanner.calculate(s, ReminderSettings(), now)
        assertEquals(1, r.pending.size); assertEquals(LocalTime.of(9, 15), r.next!!.toLocalTime())
        assertEquals(0, ReminderPlanner.calculate(s.copy(records = listOf(CheckIn("h", day.toString(), 30.0))), ReminderSettings(), now).pending.size)
        assertEquals(0, ReminderPlanner.calculate(s.copy(records = listOf(CheckIn("h", day.toString(), 5.0, true))), ReminderSettings(), now).pending.size)
    }
    @Test fun firstReminderAndMidnight() {
        val s = Snapshot(listOf(habit), plans = listOf(plan))
        val morning = ReminderPlanner.calculate(s, ReminderSettings(), day.atTime(8, 0).atZone(ZoneId.of("UTC")))
        assertTrue(morning.pending.isEmpty()); assertEquals(LocalTime.of(9, 0), morning.next!!.toLocalTime())
        val late = ReminderPlanner.calculate(s, ReminderSettings(), day.atTime(23, 59).atZone(ZoneId.of("UTC")))
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(ZoneId.of("UTC")), late.next)
    }

    @Test fun noReminderWorkForEmptyDisabledArchivedOrUnscheduledHabits() {
        val now = day.atTime(10, 0).atZone(ZoneId.of("UTC"))
        listOf(Snapshot(), Snapshot(listOf(habit.copy(reminderEnabled = false)), plans = listOf(plan)),
            Snapshot(listOf(habit.copy(archived = true)), plans = listOf(plan)),
            Snapshot(listOf(habit.copy(reminderDays = 1)), plans = listOf(plan.copy(days = 2)))
        ).forEach { snapshot ->
            val result = ReminderPlanner.calculate(snapshot, ReminderSettings(), now)
            assertNull(result.next); assertTrue(result.pending.isEmpty())
        }
    }

    @Test fun completedWeeklyHabitSchedulesNextWeekInsteadOfDailyWakeups() {
        val now = day.atTime(10, 0).atZone(ZoneId.of("UTC"))
        val s = Snapshot(listOf(habit.copy(reminderDays = 2)), plans = listOf(plan.copy(days = 2)),
            records = listOf(CheckIn(habit.id, day.toString(), plan.target)))
        val result = ReminderPlanner.calculate(s, ReminderSettings(), now)
        assertTrue(result.pending.isEmpty())
        assertEquals(day.plusDays(7).atTime(9, 0).atZone(now.zone), result.next)
    }

    @Test fun repeatsCanBeDisabledWhileKeepingExplicitReminderTimes() {
        val now = day.atTime(9, 7).atZone(ZoneId.of("UTC"))
        val result = ReminderPlanner.calculate(Snapshot(listOf(habit), plans = listOf(plan)), ReminderSettings(interval = 0), now)
        assertEquals(1, result.pending.size)
        assertEquals(day.atTime(14, 0).atZone(now.zone), result.next)
    }

    @Test fun quietHoursDeferRepeatsWithoutDiscardingUserScheduledTimes() {
        val now = day.atTime(12, 7).atZone(ZoneId.of("UTC"))
        val settings = ReminderSettings(quietStart = "12:00", quietEnd = "14:00")
        val morningOnly = Snapshot(listOf(habit.copy(reminderTimes = "09:00")), plans = listOf(plan))
        assertEquals(day.atTime(14, 0).atZone(now.zone), ReminderPlanner.calculate(morningOnly, settings, now).next)
        val explicitQuietTime = morningOnly.copy(habits = listOf(habit.copy(reminderTimes = "09:00,13:00")))
        assertEquals(day.atTime(13, 0).atZone(now.zone), ReminderPlanner.calculate(explicitQuietTime, settings, now).next)
        val night = day.atTime(23, 7).atZone(now.zone)
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(now.zone), ReminderPlanner.calculate(morningOnly, ReminderSettings(), night).next)
    }
}
