package com.jelly.habittrack.data

import java.time.LocalDate

enum class Milestone(val threshold: Int, val label: String) {
    FIRST(1, "首次完成"),
    THREE(3, "连续 3 次"),
    SEVEN(7, "连续 7 次"),
    FOURTEEN(14, "连续 14 次"),
    THIRTY(30, "连续 30 次"),
    HUNDRED(100, "连续 100 次");

    companion object {
        fun nextAfter(longest: Int): Milestone? = entries.firstOrNull { it.threshold > longest }

        fun highestNewlyEarned(previousLongest: Int, longest: Int): Milestone? =
            entries.lastOrNull { it.threshold > previousLongest && it.threshold <= longest }
    }
}

data class MilestoneProgress(val current: Int, val longest: Int) {
    fun earned(milestone: Milestone) = longest >= milestone.threshold
    val next get() = Milestone.nextAfter(longest)
    val reachableToday get() = next?.takeIf { it.threshold == current + 1 }
}

fun Snapshot.milestoneProgress(habit: Habit, today: LocalDate): MilestoneProgress {
    val (current, longest) = streak(habit, today)
    return MilestoneProgress(current, longest)
}
