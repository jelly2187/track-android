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
        assertEquals(1, r.pending.size); assertEquals(LocalTime.of(9, 15), r.next.toLocalTime())
        assertEquals(0, ReminderPlanner.calculate(s.copy(records = listOf(CheckIn("h", day.toString(), 30.0))), ReminderSettings(), now).pending.size)
        assertEquals(0, ReminderPlanner.calculate(s.copy(records = listOf(CheckIn("h", day.toString(), 5.0, true))), ReminderSettings(), now).pending.size)
    }
    @Test fun firstReminderAndMidnight() {
        val s = Snapshot(listOf(habit), plans = listOf(plan))
        val morning = ReminderPlanner.calculate(s, ReminderSettings(), day.atTime(8, 0).atZone(ZoneId.of("UTC")))
        assertTrue(morning.pending.isEmpty()); assertEquals(LocalTime.of(9, 0), morning.next.toLocalTime())
        val late = ReminderPlanner.calculate(s, ReminderSettings(), day.atTime(23, 59).atZone(ZoneId.of("UTC")))
        assertEquals(day.plusDays(1).atStartOfDay(ZoneId.of("UTC")), late.next)
    }
}
