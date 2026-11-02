package com.jelly.habittrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY position, createdDate, name") fun watchHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habit_groups ORDER BY name") fun watchGroups(): Flow<List<HabitGroup>>
    @Query("SELECT * FROM plans") fun watchPlans(): Flow<List<HabitPlan>>
    @Query("SELECT * FROM checkins") fun watchRecords(): Flow<List<CheckIn>>
    @Query("SELECT * FROM habits ORDER BY position, createdDate, name") suspend fun habits(): List<Habit>
    @Query("SELECT * FROM habit_groups") suspend fun groups(): List<HabitGroup>
    @Query("SELECT * FROM plans") suspend fun plans(): List<HabitPlan>
    @Query("SELECT * FROM checkins") suspend fun records(): List<CheckIn>
    @Query("SELECT * FROM habits WHERE id=:id") suspend fun habit(id: String): Habit?
    @Query("SELECT * FROM checkins WHERE habitId=:id AND date=:date") suspend fun record(id: String, date: String): CheckIn?
    @Query("SELECT * FROM plans WHERE habitId=:id AND fromDate<=:date ORDER BY fromDate DESC LIMIT 1") suspend fun plan(id: String, date: String): HabitPlan?
    @Query("SELECT COUNT(*) FROM handled_events WHERE token=:token") suspend fun handled(token: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(habit: Habit)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(group: HabitGroup)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(plan: HabitPlan)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: CheckIn)
    @Insert suspend fun save(event: HandledEvent)
    @Query("DELETE FROM handled_events") suspend fun clearEvents()
    @Query("DELETE FROM checkins") suspend fun clearRecords()
    @Query("DELETE FROM plans") suspend fun clearPlans()
    @Query("DELETE FROM habits") suspend fun clearHabits()
    @Query("DELETE FROM habit_groups") suspend fun clearGroups()
}

@Database(entities = [Habit::class, HabitGroup::class, HabitPlan::class, CheckIn::class, HandledEvent::class], version = 1, exportSchema = true)
abstract class HabitDatabase : RoomDatabase() { abstract fun habits(): HabitDao }
