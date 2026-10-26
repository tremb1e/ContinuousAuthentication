package com.continuousauth.ui

import com.continuousauth.auth.AuthHistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Calendar ranges are inclusive in the UI and half-open instants when querying records. */
data class AuthHistoryDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(start)) { "End date must not precede start date" }
    }
}

enum class AuthHistoryPeriod(val label: String) {
    TODAY("今天"), YESTERDAY("昨天"), WEEK("近 7 天"), CUSTOM("自选日期")
}

data class AuthHistoryBucket(
    val startMs: Long,
    val endExclusiveMs: Long,
    val label: String,
    val description: String,
    val totalCount: Int,
    val failureCount: Int
)

data class AuthHistoryAppSummary(
    val packageName: String,
    val displayName: String,
    val failureCount: Int,
    val latestTimestampMs: Long
)

data class AuthHistoryReport(
    val entries: List<AuthHistoryEntry>,
    val buckets: List<AuthHistoryBucket>,
    val isHourly: Boolean
) {
    val failureCount: Int get() = entries.count { !it.accepted }
}

fun authHistoryDateRange(
    period: AuthHistoryPeriod,
    today: LocalDate,
    customStart: LocalDate = today,
    customEnd: LocalDate = today
): AuthHistoryDateRange = when (period) {
    AuthHistoryPeriod.TODAY -> AuthHistoryDateRange(today, today)
    AuthHistoryPeriod.YESTERDAY -> AuthHistoryDateRange(today.minusDays(1), today.minusDays(1))
    AuthHistoryPeriod.WEEK -> AuthHistoryDateRange(today.minusDays(6), today)
    AuthHistoryPeriod.CUSTOM -> {
        // Saved ranges can outlive midnight or a timezone change. Keep them in the visible week.
        val earliest = today.minusDays(6)
        val start = customStart.coerceIn(earliest, today)
        val end = customEnd.coerceIn(start, today)
        AuthHistoryDateRange(start, end)
    }
}

/**
 * Split a single local day into real hours, including repeated/missing daylight-saving hours.
 * Multi-day ranges use local calendar days, never a fixed 24-hour duration.
 */
fun buildAuthHistoryReport(
    history: List<AuthHistoryEntry>,
    range: AuthHistoryDateRange,
    nowMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): AuthHistoryReport {
    val start = range.start.atStartOfDay(zoneId)
    val end = range.endInclusive.plusDays(1).atStartOfDay(zoneId)
    val startMs = start.toInstant().toEpochMilli()
    val endMs = end.toInstant().toEpochMilli()
    val entries = history.asSequence()
        .filter { it.timestampMs >= startMs && it.timestampMs < endMs && it.timestampMs <= nowMs }
        .distinctBy { it.id }
        .sortedByDescending { it.timestampMs }
        .toList()
    val hourly = range.start == range.endInclusive
    val hourFormat = DateTimeFormatter.ofPattern("HH:mm")
    val dateFormat = DateTimeFormatter.ofPattern("M/d")
    val fullDateFormat = DateTimeFormatter.ofPattern("M月d日")
    val buckets = mutableListOf<AuthHistoryBucket>()
    var cursor = start
    while (cursor < end) {
        val next = if (hourly) cursor.plusHours(1) else cursor.plusDays(1)
        val bucketStart = cursor.toInstant().toEpochMilli()
        val bucketEnd = minOf(next.toInstant().toEpochMilli(), endMs)
        val timeDescription = if (hourly) {
            val endLabel = if (next.toLocalDate() > cursor.toLocalDate()) "24:00" else hourFormat.format(next)
            if (cursor.offset != next.offset) {
                "${fullDateFormat.format(cursor)} ${hourFormat.format(cursor)} (UTC${cursor.offset})–$endLabel (UTC${next.offset})"
            } else {
                "${fullDateFormat.format(cursor)} ${hourFormat.format(cursor)}–$endLabel"
            }
        } else fullDateFormat.format(cursor)
        buckets += AuthHistoryBucket(
            startMs = bucketStart,
            endExclusiveMs = bucketEnd,
            label = if (hourly) hourFormat.format(cursor) else dateFormat.format(cursor),
            description = timeDescription,
            totalCount = 0,
            failureCount = 0
        )
        cursor = next
    }
    // Count in one pass instead of scanning a full week's records once for every bar.
    val starts = buckets.map { it.startMs }.toLongArray()
    val totals = IntArray(buckets.size)
    val failures = IntArray(buckets.size)
    entries.forEach { entry ->
        val match = starts.binarySearch(entry.timestampMs)
        val index = if (match >= 0) match else -match - 2
        if (index in buckets.indices) {
            totals[index]++
            if (!entry.accepted) failures[index]++
        }
    }
    return AuthHistoryReport(entries, buckets.mapIndexed { index, bucket ->
        bucket.copy(totalCount = totals[index], failureCount = failures[index])
    }, hourly)
}

fun summarizeAuthHistoryApps(history: List<AuthHistoryEntry>): List<AuthHistoryAppSummary> =
    history.filter { !it.accepted }
        .groupBy { it.appPackage.takeUnless(::isUnknownAppPackage).orEmpty() }
        .map { (packageName, entries) ->
            val latest = entries.maxBy { it.timestampMs }
            val displayName = if (packageName.isEmpty()) {
                "未识别的应用"
            } else {
                entries.sortedByDescending { it.timestampMs }.firstOrNull { it.appName.isNotBlank() }
                    ?.appName ?: packageName
            }
            AuthHistoryAppSummary(packageName, displayName, entries.size, latest.timestampMs)
        }
        .sortedWith(compareByDescending<AuthHistoryAppSummary> { it.failureCount }
            .thenByDescending { it.latestTimestampMs }.thenBy { it.packageName })

fun authHistoryAppName(entry: AuthHistoryEntry): String = when {
    isUnknownAppPackage(entry.appPackage) -> "未识别的应用"
    entry.appName.isNotBlank() -> entry.appName
    else -> entry.appPackage
}

private fun isUnknownAppPackage(packageName: String): Boolean =
    packageName.isBlank() || packageName.equals("unknown", ignoreCase = true)

fun formatAuthHistoryTime(timestampMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("M月d日 HH:mm:ss").format(Instant.ofEpochMilli(timestampMs).atZone(zoneId))
