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

    suspend fun act(id: String, date: LocalDate, mode: String, amount: Double = 0.0, token: String = UUID.randomUUID().toString(), external: Boolean = false) {
        require(mode in listOf("complete", "increment", "set", "reset", "fail", "reopen"))
        db.withTransaction {
            if (dao.handled(token) > 0) return@withTransaction
            val habit = dao.habit(id) ?: return@withTransaction
            if (external && (date != LocalDate.now() || habit.archived)) return@withTransaction
            require(date <= LocalDate.now() && date.toString() >= habit.createdDate)
            val plan = dao.plan(id, date.toString()) ?: return@withTransaction
            if (!Rules.includes(plan.days, date)) return@withTransaction
            val old = dao.record(id, date.toString()) ?: CheckIn(id, date.toString())
            if (external && (old.closed || old.value >= plan.target)) return@withTransaction
            val value = Rules.progress(old.value, amount, mode, plan.target)
            dao.save(old.copy(value = value, closed = mode == "fail"))
            dao.save(HandledEvent(token))
        }
    }
}
