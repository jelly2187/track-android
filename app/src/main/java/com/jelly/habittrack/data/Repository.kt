package com.jelly.habittrack.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.util.UUID

class Repository(context: Context, databaseName: String = "habit-track.db") {
    val db = Room.databaseBuilder(context.applicationContext, HabitDatabase::class.java, databaseName).build()
    val dao = db.habits()
    val changes = combine(dao.watchHabits(), dao.watchGroups(), dao.watchPlans(), dao.watchRecords()) { h, g, p, r -> Snapshot(h, g, p, r) }
    suspend fun snapshot() = db.withTransaction { Snapshot(dao.habits(), dao.groups(), dao.plans(), dao.records()) }

    suspend fun saveHabit(habit: Habit, days: Int, target: Double) {
        require(habit.name.isNotBlank() && habit.name.length <= 60)
        require(habit.kind in listOf("CHECK", "COUNT") && habit.step.isFinite() && habit.step > 0 && habit.step <= 1_000_000)
        require(target.isFinite() && target > 0 && target <= 1_000_000 && days in 1..127)
        require(habit.reminderDays in 0..127)
        val times = Rules.times(habit.reminderTimes)
        require(!habit.reminderEnabled || (times.isNotEmpty() && habit.reminderDays and days != 0)) { "请至少选择一个有效提醒日和时间" }
        db.withTransaction {
            val today = LocalDate.now().toString()
            val oldPlan = dao.plan(habit.id, today)
            dao.save(habit.copy(name = habit.name.trim(), reminderTimes = times.joinToString(",")))
            if (oldPlan == null || oldPlan.days != days || oldPlan.target != target) dao.save(HabitPlan(habit.id, today, days, target))
        }
    }

    suspend fun act(id: String, date: LocalDate, mode: String, amount: Double = 0.0, token: String = UUID.randomUUID().toString(), external: Boolean = false): Milestone? {
        require(mode in listOf("complete", "increment", "set", "reset", "fail", "reopen"))
        return db.withTransaction {
            if (dao.handled(token) > 0) return@withTransaction null
            val habit = dao.habit(id) ?: return@withTransaction null
            val today = LocalDate.now()
            if (external && (date != today || habit.archived)) return@withTransaction null
            val recordDate = date.toString()
            require(date <= today && recordDate >= habit.createdDate)
            val plan = dao.plan(id, recordDate) ?: return@withTransaction null
            if (!Rules.includes(plan.days, date)) return@withTransaction null
            val old = dao.record(id, recordDate) ?: CheckIn(id, recordDate)
            val wasComplete = Rules.reachesTarget(old.value, plan.target)
            if (external && (old.closed || wasComplete)) return@withTransaction null
            val value = Rules.progress(old.value, amount, mode, plan.target)
            val updated = old.copy(value = value, closed = mode == "fail")
            // Comparing inside the write transaction prevents two taps from celebrating one unlock.
            val milestone = if (!external && !wasComplete && Rules.reachesTarget(value, plan.target)) {
                val history = Snapshot(plans = dao.plansForHabit(id), records = dao.recordsForHabit(id))
                val previousLongest = history.streak(habit, today).second
                if (Milestone.nextAfter(previousLongest) == null) null
                else {
                    val revised = history.copy(records = history.records.filterNot { it.date == recordDate } + updated)
                    Milestone.highestNewlyEarned(previousLongest, revised.streak(habit, today).second)
                }
            } else null
            dao.save(updated)
            dao.save(HandledEvent(token))
            milestone
        }
    }
}
