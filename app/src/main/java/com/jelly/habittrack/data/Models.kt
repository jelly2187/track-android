package com.jelly.habittrack.data

import androidx.room.*
import java.time.*

const val EVERY_DAY = 127
val PALETTE = listOf(0xFF347568, 0xFF6389B8, 0xFFBB7855, 0xFF9C78AC, 0xFFC1A044, 0xFFCD7487, 0xFF71836A, 0xFF657C92)

@Entity(tableName = "habit_groups")
data class HabitGroup(@PrimaryKey val id: String, val name: String, val color: Long)

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val id: String,
    val name: String,
    val note: String = "",
    val kind: String = "CHECK",
    val unit: String = "次",
    val step: Double = 1.0,
    val groupId: String? = null,
    val reminderDays: Int = EVERY_DAY,
    val reminderTimes: String = "09:00",
    val reminderEnabled: Boolean = false,
    val archived: Boolean = false,
    val position: Int = 0,
    val createdDate: String = LocalDate.now().toString()
)

@Entity(tableName = "plans", primaryKeys = ["habitId", "fromDate"])
data class HabitPlan(val habitId: String, val fromDate: String, val days: Int, val target: Double)

@Entity(tableName = "checkins", primaryKeys = ["habitId", "date"])
data class CheckIn(val habitId: String, val date: String, val value: Double = 0.0, val closed: Boolean = false)

@Entity(tableName = "handled_events")
data class HandledEvent(@PrimaryKey val token: String, val createdAt: Long = System.currentTimeMillis())

data class Snapshot(
    val habits: List<Habit> = emptyList(),
    val groups: List<HabitGroup> = emptyList(),
    val plans: List<HabitPlan> = emptyList(),
    val records: List<CheckIn> = emptyList()
) {
    private val recordsByKey by lazy { records.associateBy { it.habitId to it.date } }
    private val plansByHabit by lazy { plans.groupBy { it.habitId }.mapValues { (_, values) -> values.sortedByDescending { it.fromDate } } }
    private val streakCache = java.util.concurrent.ConcurrentHashMap<Pair<String, LocalDate>, Pair<Int, Int>>()
    fun plan(habit: Habit, date: LocalDate) = plansByHabit[habit.id]?.firstOrNull { it.fromDate <= date.toString() }
    fun due(habit: Habit, date: LocalDate): Boolean = date.toString() >= habit.createdDate && plan(habit, date)?.let { Rules.includes(it.days, date) } == true
    fun record(habit: Habit, date: LocalDate) = recordsByKey[habit.id to date.toString()]
    fun complete(habit: Habit, date: LocalDate) = (record(habit, date)?.value ?: 0.0) + 0.000001 >= (plan(habit, date)?.target ?: Double.MAX_VALUE)
    fun color(habit: Habit) = groups.find { it.id == habit.groupId }?.color ?: PALETTE.first()
    fun streak(habit: Habit, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        streakCache[habit.id to today]?.let { return it }
        var current = 0; var longest = 0; var run = 0
        var date = LocalDate.parse(habit.createdDate)
        while (date <= today) {
            if (due(habit, date)) {
                if (complete(habit, date)) { run++; longest = maxOf(longest, run) }
                else if (date < today || record(habit, date)?.closed == true) run = 0
            }
            date = date.plusDays(1)
        }
        current = run
        return (current to longest).also { streakCache[habit.id to today] = it }
    }
    fun rate(habit: Habit, start: LocalDate, end: LocalDate): Int {
        var count = 0; var done = 0; var date = maxOf(start, LocalDate.parse(habit.createdDate))
        while (date <= end) { if (due(habit, date)) { count++; if (complete(habit, date)) done++ }; date = date.plusDays(1) }
        return if (count == 0) 0 else (done * 100.0 / count).toInt()
    }
}

object Rules {
    fun includes(mask: Int, date: LocalDate) = mask and (1 shl (date.dayOfWeek.value - 1)) != 0
    fun quiet(time: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }
    fun times(raw: String): List<LocalTime> = raw.split(',').filter { it.isNotBlank() }.map { LocalTime.parse(it.trim()) }.distinct().sorted()
    fun number(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    fun progress(old: Double, amount: Double, mode: String, target: Double): Double = when (mode) {
        "complete" -> target
        "increment" -> kotlin.math.round((old + amount) * 100) / 100
        "set" -> amount
        "reset" -> 0.0
        else -> old
    }.also { require(it.isFinite() && it in 0.0..1_000_000.0) { "数值应在 0 到 1000000 之间" } }
}
