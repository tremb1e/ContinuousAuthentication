package com.continuousauth.auth

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AuthHistoryEntry(
    val id: String,
    val timestampMs: Long,
    val accepted: Boolean,
    val appPackage: String,
    val appName: String
)

/** A separate local database keeps the seven-day history across service/activity restarts. */
@Singleton
class AuthHistoryRepository internal constructor(context: Context, databaseName: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, DATABASE_NAME)

    private val helper = HistoryDatabase(context, databaseName)
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<AuthHistoryEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private var loaded = false

    suspend fun refresh(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneAndLoad(nowMs)
        }
    }

    /** Returns false for redelivered windows and records outside the retained interval. */
    suspend fun record(
        entry: AuthHistoryEntry,
        nowMs: Long = System.currentTimeMillis(),
        isStillAllowed: () -> Boolean = { true }
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Consent can change while this coroutine is waiting for IO or the database lock.
                // This check and insertion share clear()'s mutex, so withdrawal cannot be undone
                // by a result that was already in flight when local deletion completed.
                if (!isStillAllowed()) return@withLock false
                pruneAndLoad(nowMs)
                if (entry.id.isBlank() || entry.timestampMs < nowMs - RETENTION_MS ||
                    entry.timestampMs > nowMs || entry.timestampMs <= 0L) {
                    return@withLock false
                }
                val values = ContentValues().apply {
                    put("event_id", entry.id)
                    put("timestamp_ms", entry.timestampMs)
                    put("accepted", if (entry.accepted) 1 else 0)
                    put("app_package", entry.appPackage)
                    put("app_name", entry.appName)
                }
                val inserted = helper.writableDatabase.insertWithOnConflict(
                    "auth_history", null, values, SQLiteDatabase.CONFLICT_IGNORE
                ) != -1L
                if (inserted) {
                    val previous = _entries.value
                    _entries.value = if (previous.isEmpty() || previous.last().timestampMs <= entry.timestampMs) {
                        previous + entry
                    } else {
                        (previous + entry).sortedWith(compareBy<AuthHistoryEntry> { it.timestampMs }.thenBy { it.id })
                    }
                }
                inserted
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.writableDatabase.delete("auth_history", null, null)
            _entries.value = emptyList()
            loaded = true
        }
    }

    private fun pruneAndLoad(nowMs: Long) {
        val deleted = helper.writableDatabase.delete(
            "auth_history", "timestamp_ms < ? OR timestamp_ms > ?",
            arrayOf((nowMs - RETENTION_MS).toString(), nowMs.toString())
        )
        if (!loaded) {
            _entries.value = readEntries()
            loaded = true
        } else if (deleted > 0) {
            _entries.value = _entries.value.filter { it.timestampMs in (nowMs - RETENTION_MS)..nowMs }
        }
    }

    private fun readEntries(): List<AuthHistoryEntry> = buildList {
        helper.readableDatabase.query(
            "auth_history", null, null, null, null, null, "timestamp_ms ASC, event_id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(AuthHistoryEntry(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("event_id")),
                    timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")),
                    accepted = cursor.getInt(cursor.getColumnIndexOrThrow("accepted")) != 0,
                    appPackage = cursor.getString(cursor.getColumnIndexOrThrow("app_package")),
                    appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name"))
                ))
            }
        }
    }

    internal fun close() = helper.close()

    private class HistoryDatabase(context: Context, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE auth_history (
                    event_id TEXT PRIMARY KEY NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    accepted INTEGER NOT NULL,
                    app_package TEXT NOT NULL,
                    app_name TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX auth_history_time ON auth_history(timestamp_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val DATABASE_NAME = "auth_history.db"
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
    }
}
