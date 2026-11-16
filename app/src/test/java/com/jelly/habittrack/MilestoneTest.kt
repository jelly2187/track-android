package com.jelly.habittrack

import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.reminderCopy
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MilestoneTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val habit = Habit("reading", "阅读", createdDate = today.minusDays(8).toString())
    private val plan = HabitPlan(habit.id, habit.createdDate, EVERY_DAY, 1.0)

    @Test fun firstRunAndRestartHaveDistinctReminderCopy() {
        val empty = Snapshot(plans = listOf(plan))
        assertEquals("阅读 · 从今天开始，完成第 1 次打卡", reminderCopy(empty, habit, today).summary)
        assertEquals(Milestone.FIRST, reminderCopy(empty, habit, today).milestone)

        val earlier = empty.copy(records = (2L..8L).map { CheckIn(habit.id, today.minusDays(it).toString(), 1.0) })
        val copy = reminderCopy(earlier, habit, today)
        assertEquals("阅读 · 今天可以重新开始，最长连续 7 次", copy.summary)
        assertNull(copy.milestone)
        assertTrue(earlier.milestoneProgress(habit, today).earned(Milestone.SEVEN))
        assertEquals(Milestone.FOURTEEN, earlier.milestoneProgress(habit, today).next)
    }

    @Test fun partialCountShowsCurrentProgressAndFirstReachableBadge() {
        val count = habit.copy(name = "喝水", kind = "COUNT", unit = "杯")
        val historical = (1L..6L).map { CheckIn(habit.id, today.minusDays(it).toString(), 4.0) }
        val snapshot = Snapshot(
            plans = listOf(plan.copy(target = 4.0), HabitPlan(count.id, today.toString(), EVERY_DAY, 6.0)),
            records = historical + CheckIn(count.id, today.toString(), 3.0)
        )
        val copy = reminderCopy(snapshot, count, today)
        assertEquals("喝水 · 3/6 杯 · 已连续完成 6 次", copy.summary)
        assertTrue(copy.singleExpanded.endsWith("完成今天的目标，即可解锁连续 7 次徽章"))
        assertFalse(snapshot.complete(count, today))
        assertFalse(Rules.reachesTarget(0.0, 0.0000001))
    }

    @Test fun scheduledDaysBuildMilestonesAndHistoricalCorrectionsRemoveThem() {
        val weekly = habit.copy(createdDate = today.minusDays(15).toString())
        val scheduled = Snapshot(
            plans = listOf(plan.copy(fromDate = weekly.createdDate, days = 1)),
            records = listOf(14L, 7L, 0L).map { CheckIn(weekly.id, today.minusDays(it).toString(), 1.0) }
        )
        assertTrue(scheduled.milestoneProgress(weekly, today).earned(Milestone.THREE))
        val corrected = scheduled.copy(records = scheduled.records.filterNot { it.date == today.minusDays(7).toString() })
        assertEquals(MilestoneProgress(1, 1), corrected.milestoneProgress(weekly, today))
        assertFalse(corrected.milestoneProgress(weekly, today).earned(Milestone.THREE))
        assertEquals(Milestone.THIRTY, Milestone.highestNewlyEarned(2, 30))
        assertNull(Milestone.highestNewlyEarned(30, 30))
        assertNull(MilestoneProgress(100, 100).next)
    }
}
