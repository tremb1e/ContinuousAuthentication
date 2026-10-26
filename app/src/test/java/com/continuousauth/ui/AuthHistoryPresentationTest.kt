package com.continuousauth.ui

import com.continuousauth.auth.AuthHistoryEntry
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHistoryPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 26)
    private val now = today.atTime(15, 30).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun presetsUseLocalCalendarDatesAndSevenInclusiveDays() {
        assertEquals(AuthHistoryDateRange(today, today), authHistoryDateRange(AuthHistoryPeriod.TODAY, today))
        assertEquals(AuthHistoryDateRange(today.minusDays(1), today.minusDays(1)),
            authHistoryDateRange(AuthHistoryPeriod.YESTERDAY, today))
        assertEquals(AuthHistoryDateRange(today.minusDays(6), today), authHistoryDateRange(AuthHistoryPeriod.WEEK, today))
    }

    @Test
    fun savedCustomRangeClampsAfterMidnightOrTimeZoneChange() {
        val range = authHistoryDateRange(AuthHistoryPeriod.CUSTOM, today, today.minusDays(20), today.plusDays(3))
        assertEquals(today.minusDays(6), range.start)
        assertEquals(today, range.endInclusive)
        val reversed = authHistoryDateRange(AuthHistoryPeriod.CUSTOM, today, today, today.minusDays(1))
        assertEquals(AuthHistoryDateRange(today, today), reversed)
    }

    @Test
    fun reportHonorsMidnightBoundariesAndIgnoresFutureOrDuplicateRecords() {
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val entries = listOf(entry("before", midnight - 1), entry("start", midnight),
            entry("start", midnight), entry("pass", now, true), entry("future", now + 1),
            entry("next-day", nextMidnight))
        val report = buildAuthHistoryReport(entries, AuthHistoryDateRange(today, today), now, zone)
        assertEquals(listOf("pass", "start"), report.entries.map { it.id })
        assertEquals(1, report.failureCount)
        assertEquals(24, report.buckets.size)
        assertEquals(1, report.buckets.first().failureCount)
        assertEquals(1, report.buckets[15].totalCount)
        assertEquals(0, report.buckets[15].failureCount)
    }

    @Test
    fun zeroFailuresWithNoRecordsRemainsDistinguishableFromAllPassed() {
        val range = AuthHistoryDateRange(today, today)
        val noData = buildAuthHistoryReport(emptyList(), range, now, zone)
        val passed = buildAuthHistoryReport(listOf(entry("passed", now, true)), range, now, zone)
        assertEquals(0, noData.failureCount)
        assertTrue(noData.entries.isEmpty())
        assertTrue(noData.buckets.all { it.totalCount == 0 })
        assertEquals(0, passed.failureCount)
        assertFalse(passed.entries.isEmpty())
        assertEquals(1, passed.buckets.sumOf { it.totalCount })
    }

    @Test
    fun customSingleDayUsesHoursAndWeekUsesDailyBuckets() {
        val week = buildAuthHistoryReport(listOf(entry("today", now),
            entry("first", today.minusDays(6).atTime(11, 0).atZone(zone).toInstant().toEpochMilli())),
            authHistoryDateRange(AuthHistoryPeriod.WEEK, today), now, zone)
        assertFalse(week.isHourly)
        assertEquals(7, week.buckets.size)
        assertEquals(1, week.buckets.first().failureCount)
        assertEquals(1, week.buckets.last().failureCount)
        assertEquals(2, week.buckets.sumOf { it.failureCount })
    }

    @Test
    fun daylightSavingDaysContainAllRealHoursExactlyOnce() {
        val newYork = ZoneId.of("America/New_York")
        listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25).forEach { (date, hours) ->
            val start = date.atStartOfDay(newYork).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli()
            val entries = (0 until hours).map { index -> entry("hour-$index", start + index * 3_600_000L) }
            val report = buildAuthHistoryReport(entries, AuthHistoryDateRange(date, date), end, newYork)
            assertEquals(hours, report.buckets.size)
            assertEquals(hours, report.buckets.sumOf { it.totalCount })
            assertTrue(report.buckets.all { it.totalCount == 1 })
            assertEquals(end, report.buckets.last().endExclusiveMs)
        }
    }

    @Test
    fun applicationsAreGroupedByPackageNotPotentiallyDuplicateLabels() {
        val entries = listOf(entry("a1", now, appPackage = "app.a", appName = "同名应用"),
            entry("a2", now - 1, appPackage = "app.a", appName = "旧名称"),
            entry("b", now - 2, appPackage = "app.b", appName = "同名应用"),
            entry("pass", now, accepted = true, appPackage = "app.a", appName = "同名应用"))
        val apps = summarizeAuthHistoryApps(entries)
        assertEquals(2, apps.size)
        assertEquals("app.a", apps.first().packageName)
        assertEquals("同名应用", apps.first().displayName)
        assertEquals(2, apps.first().failureCount)
        assertEquals(1, apps.last().failureCount)
    }

    @Test
    fun unknownAppNeverInventsAnApplicationAndMissingLabelFallsBackToPackage() {
        val unknown = entry("none", now, appPackage = "", appName = "")
        val oldUnknown = entry("legacy", now - 1, appPackage = "unknown", appName = "Unknown App")
        val known = entry("known", now - 2, appPackage = "app.real", appName = "")
        val apps = summarizeAuthHistoryApps(listOf(unknown, oldUnknown, known))
        assertEquals(2, apps.size)
        assertEquals("未识别的应用", apps.first().displayName)
        assertEquals(2, apps.first().failureCount)
        assertEquals("未识别的应用", authHistoryAppName(oldUnknown))
        assertEquals("app.real", authHistoryAppName(known))
    }

    private fun entry(id: String, time: Long, accepted: Boolean = false,
                      appPackage: String = "app.example", appName: String = "示例应用") =
        AuthHistoryEntry(id, time, accepted, appPackage, appName)
}
