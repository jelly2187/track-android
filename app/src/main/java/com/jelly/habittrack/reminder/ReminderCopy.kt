package com.jelly.habittrack.reminder

import com.jelly.habittrack.data.Habit
import com.jelly.habittrack.data.Milestone
import com.jelly.habittrack.data.Rules
import com.jelly.habittrack.data.Snapshot
import com.jelly.habittrack.data.milestoneProgress
import java.time.LocalDate

internal data class ReminderCopy(
    val summary: String,
    val details: String,
    val milestone: Milestone?
) {
    val singleExpanded: String
        get() = milestone?.let { "$details\n完成今天的目标，即可解锁${it.label}徽章" } ?: details

    val inboxLine: String
        get() = milestone?.let { "$summary · 达标解锁${it.label}徽章" } ?: summary
}

internal fun reminderCopy(snapshot: Snapshot, habit: Habit, today: LocalDate): ReminderCopy {
    val progress = snapshot.milestoneProgress(habit, today)
    val status = when {
        progress.current > 0 -> "已连续完成 ${progress.current} 次"
        progress.longest == 0 -> "从今天开始，完成第 1 次打卡"
        else -> "今天可以重新开始，最长连续 ${progress.longest} 次"
    }
    val name = habit.name.oneLine()
    val quantity = if (habit.kind == "COUNT") {
        val value = snapshot.record(habit, today)?.value ?: 0.0
        val target = checkNotNull(snapshot.plan(habit, today)).target
        "${Rules.number(value)}/${Rules.number(target)} ${habit.unit.oneLine()}"
    } else null
    return ReminderCopy(
        summary = listOfNotNull(name.shortened(12), quantity?.shortened(22), status).joinToString(" · "),
        details = listOfNotNull(name, quantity, status).joinToString(" · "),
        milestone = progress.reachableToday
    )
}

private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

private fun String.shortened(maxCharacters: Int): String {
    if (codePointCount(0, length) <= maxCharacters) return this
    return substring(0, offsetByCodePoints(0, maxCharacters - 1)) + "…"
}
