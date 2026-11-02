package com.jelly.habittrack

import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.platform.app.InstrumentationRegistry
import com.jelly.habittrack.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryJvmTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: Repository
    private val database = "test-${UUID.randomUUID()}.db"
    private val today = LocalDate.now()
    @Before fun setup() { repo = Repository(context, database) }
    @After fun cleanup() { repo.db.close(); context.deleteDatabase(database) }
    private suspend fun fixture(): Habit {
        val h = Habit("test", "喝水", kind = "COUNT", unit = "杯", step = 1.0, createdDate = today.minusDays(2).toString())
        repo.dao.save(h); repo.dao.save(HabitPlan(h.id, h.createdDate, 127, 8.0)); return h
    }
    @Test fun duplicateActionsAreIdempotent() = runBlocking {
        val h = fixture()
        repeat(2) { repo.act(h.id, today, "increment", 1.0, "same-token", external = true) }
        assertEquals(1.0, repo.snapshot().record(h, today)!!.value, 0.0)
        repo.act(h.id, today, "increment", 7.0)
        assertTrue(repo.snapshot().complete(h, today))
        repo.act(h.id, today, "increment", 1.0, "late-token", external = true)
        assertEquals(8.0, repo.snapshot().record(h, today)!!.value, 0.0)
    }
    @Test fun yesterdayActionsCannotWriteTodayAndFailureKeepsProgress() = runBlocking {
        val h = fixture()
        repo.act(h.id, today.minusDays(1), "increment", 1.0, external = true)
        assertNull(repo.snapshot().record(h, today.minusDays(1)))
        repo.act(h.id, today, "set", 3.0); repo.act(h.id, today, "fail")
        assertEquals(CheckIn(h.id, today.toString(), 3.0, true), repo.snapshot().record(h, today))
        repo.act(h.id, today, "reopen"); assertFalse(repo.snapshot().record(h, today)!!.closed)
    }
    @Test fun backupRoundTripAndValidationBeforeReplacement() = runBlocking {
        val h = fixture(); repo.act(h.id, today, "set", 2.0)
        val before = repo.snapshot(); val json = Backup.encode(before, ReminderSettings())
        val b = Backup.decode(json)
        repo.act(h.id, today, "complete"); Backup.restore(repo, b)
        assertEquals(before.records, repo.snapshot().records)
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")) }
        assertEquals(before.records, repo.snapshot().records)
    }
    @Test fun revisedGoalPreservesPreviousDays() = runBlocking {
        val h = fixture(); repo.act(h.id, today.minusDays(1), "complete")
        repo.saveHabit(h, 127, 12.0)
        assertEquals(8.0, repo.snapshot().plan(h, today.minusDays(1))!!.target, 0.0)
        assertTrue(repo.snapshot().complete(h, today.minusDays(1)))
        assertEquals(12.0, repo.snapshot().plan(h, today)!!.target, 0.0)
    }
}
