package com.continuousauth.auth

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuthHistoryRepositoryInstrumentedTest {
    private lateinit var context: Context
    private val repositories = mutableListOf<AuthHistoryRepository>()
    private val now = 1_800_000_000_000L
    private lateinit var databaseName: String

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "auth-history-test-${UUID.randomUUID()}.db"
    }

    @After fun tearDown() {
        repositories.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test fun resultsSurviveRecreationAndRedeliveredWindowsDoNotChangeCountsOrApps() = runBlocking {
        val first = repository()
        val original = entry("session:1", now - 1_000)
        assertTrue(first.record(original, now))
        assertFalse(first.record(original.copy(accepted = true, appName = "重新打开的应用"), now))
        assertTrue(first.record(entry("other-session:1", now, accepted = true), now))

        val recreated = repository()
        recreated.refresh(now)
        assertEquals(2, recreated.entries.value.size)
        assertEquals(original, recreated.entries.value.first())
        assertEquals(1, recreated.entries.value.count { !it.accepted })
    }

    @Test fun historyRetainsThirtyDaysAndRejectsExpiredOrFutureEvents() = runBlocking {
        val history = repository()
        val thirtyDays = 30 * 24 * 60 * 60 * 1_000L
        assertFalse(history.record(entry("expired", now - thirtyDays - 1), now))
        assertFalse(history.record(entry("future", now + 1), now))
        assertFalse(history.record(entry("", now), now))
        assertTrue(history.record(entry("boundary", now - thirtyDays), now))
        assertTrue(history.record(entry("older-than-a-week", now - 8 * 24 * 60 * 60 * 1_000L), now))
        assertTrue(history.record(entry("recent", now), now))
        history.refresh(now + 1)
        assertEquals(listOf("older-than-a-week", "recent"), history.entries.value.map { it.id })
        val recreated = repository()
        recreated.refresh(now + 1)
        assertEquals(history.entries.value, recreated.entries.value)
    }

    @Test fun deletionClearsBothPublishedHistoryAndPersistentStorage() = runBlocking {
        val history = repository()
        history.record(entry("record", now), now)
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        val recreated = repository()
        recreated.refresh(now)
        assertTrue(recreated.entries.value.isEmpty())
    }

    @Test fun withdrawnConsentPreventsAnAlreadyReceivedResultFromReappearingAfterDeletion() = runBlocking {
        val history = repository()
        history.record(entry("existing", now), now)
        history.clear()
        assertFalse(history.record(entry("in-flight", now), now) { false })
        history.refresh(now)
        assertTrue(history.entries.value.isEmpty())
        val recreated = repository()
        recreated.refresh(now)
        assertTrue(recreated.entries.value.isEmpty())
    }

    private fun repository() = AuthHistoryRepository(context, databaseName).also { repositories += it }
    private fun entry(id: String, timestamp: Long, accepted: Boolean = false) =
        AuthHistoryEntry(id, timestamp, accepted, "com.example.reader", "阅读")
}
