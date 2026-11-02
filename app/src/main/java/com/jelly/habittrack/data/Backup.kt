package com.jelly.habittrack.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.time.LocalDate
import java.time.LocalTime

data class BackupBundle(val format: String = "HabitTrack", val schemaVersion: Int = 1, val habits: List<Habit>, val groups: List<HabitGroup>, val plans: List<HabitPlan>, val records: List<CheckIn>, val settings: ReminderSettings)

object Backup {
    fun encode(s: Snapshot, settings: ReminderSettings): String = GsonBuilder().setPrettyPrinting().create().toJson(BackupBundle(habits = s.habits, groups = s.groups, plans = s.plans, records = s.records, settings = settings))
    fun decode(raw: String): BackupBundle {
        require(raw.length <= 20_000_000) { "备份超过 20 MB" }
        val b = Gson().fromJson(raw, BackupBundle::class.java)
        require(b.format == "HabitTrack" && b.schemaVersion == 1) { "备份格式或版本不支持" }
        require(b.habits.size <= 10000 && b.groups.size <= 1000 && b.records.size <= 500000)
        require(b.habits.map { it.id }.distinct().size == b.habits.size)
        require(b.groups.map { it.id }.distinct().size == b.groups.size)
        val ids = b.habits.map { it.id }.toSet(); val groupIds = b.groups.map { it.id }.toSet()
        b.groups.forEach { require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 40 && it.color in 0..0xFFFFFFFF) }
        b.habits.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 60)
            require(it.kind in listOf("CHECK", "COUNT") && it.step.isFinite() && it.step > 0 && it.step <= 1_000_000)
            require(it.groupId == null || it.groupId in groupIds)
            require(it.reminderDays in 0..127)
            require(!it.reminderEnabled || (Rules.times(it.reminderTimes).isNotEmpty() && it.reminderDays > 0))
            Rules.times(it.reminderTimes); LocalDate.parse(it.createdDate)
            require(it.createdDate <= LocalDate.now().toString())
        }
        require(b.plans.map { it.habitId to it.fromDate }.distinct().size == b.plans.size)
        require(b.records.map { it.habitId to it.date }.distinct().size == b.records.size)
        b.plans.forEach { require(it.habitId in ids && it.days in 1..127 && it.target.isFinite() && it.target > 0 && it.target <= 1_000_000); LocalDate.parse(it.fromDate) }
        b.habits.forEach { h -> require(b.plans.any { it.habitId == h.id && it.fromDate <= h.createdDate }) }
        b.records.forEach {
            val h = b.habits.first { h -> h.id == it.habitId }
            require(it.value.isFinite() && it.value in 0.0..1_000_000.0)
            require(it.date >= h.createdDate && LocalDate.parse(it.date) <= LocalDate.now())
            val p = b.plans.filter { p -> p.habitId == it.habitId && p.fromDate <= it.date }.maxByOrNull { p -> p.fromDate }
            require(p != null && Rules.includes(p.days, LocalDate.parse(it.date)))
        }
        require(b.settings.interval in listOf(15, 30, 60) && b.settings.theme in listOf("system", "light", "dark"))
        LocalTime.parse(b.settings.quietStart); LocalTime.parse(b.settings.quietEnd)
        return b
    }
    suspend fun restore(repository: Repository, bundle: BackupBundle) = repository.db.withTransaction {
        val dao = repository.dao
        dao.clearEvents(); dao.clearRecords(); dao.clearPlans(); dao.clearHabits(); dao.clearGroups()
        bundle.groups.forEach { dao.save(it) }; bundle.habits.forEach { dao.save(it) }
        bundle.plans.forEach { dao.save(it) }; bundle.records.forEach { dao.save(it) }
    }
}
