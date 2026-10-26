package com.continuousauth.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppTrackerTest {
    private val tracker = ForegroundAppTracker()

    @Test
    fun foregroundSwitchIsAttributedAtSensorTime() {
        tracker.apply(listOf(
            resumed(100, "collector"),
            paused(200, "collector"),
            resumed(210, "third.party")
        ), 1_000)

        assertEquals("collector", tracker.packageAt(199))
        assertEquals("", tracker.packageAt(205))
        assertEquals("third.party", tracker.packageAt(210))
        assertEquals("third.party", tracker.currentPackage)
    }

    @Test
    fun activeAppDoesNotExpireAfterQueryWindow() {
        tracker.apply(listOf(resumed(100, "third.party")), 200)
        tracker.apply(emptyList(), 24 * 60 * 60 * 1_000L)

        assertEquals("third.party", tracker.currentPackage)
        assertEquals("third.party", tracker.packageAt(3_600_000))
    }

    @Test
    fun pausingCurrentActivityClearsAttribution() {
        tracker.apply(listOf(resumed(100, "third.party"), paused(200, "third.party")), 300)

        assertEquals("", tracker.currentPackage)
        assertEquals("third.party", tracker.packageAt(199))
        assertEquals("", tracker.packageAt(200))
    }

    @Test
    fun latePauseOfOldActivityDoesNotClearNewActivity() {
        tracker.apply(listOf(
            resumed(100, "third.party", "OldActivity"),
            resumed(200, "third.party", "NewActivity"),
            paused(210, "third.party", "OldActivity")
        ), 300)

        assertEquals("third.party", tracker.currentPackage)
    }

    @Test
    fun multiwindowFallsBackToStillResumedActivity() {
        tracker.apply(listOf(
            resumed(100, "first.app"), resumed(200, "second.app"), paused(300, "second.app")
        ), 400)

        assertEquals("second.app", tracker.packageAt(250))
        assertEquals("first.app", tracker.currentPackage)
    }

    @Test
    fun screenOffOrKeyguardClearsAllResumedActivities() {
        tracker.apply(listOf(
            resumed(100, "first.app"), resumed(200, "second.app"),
            ForegroundAppEvent(300, ForegroundEventType.CLEARED)
        ), 400)

        assertEquals("", tracker.currentPackage)
        tracker.apply(listOf(paused(500, "second.app")), 600)
        assertEquals("", tracker.currentPackage)
        tracker.apply(listOf(resumed(700, "first.app")), 800)
        assertEquals("first.app", tracker.currentPackage)
    }

    @Test
    fun overlappingQueriesDoNotReplayHistoricalSwitches() {
        val events = listOf(resumed(100, "first.app"), paused(200, "first.app"), resumed(210, "second.app"))
        tracker.apply(events, 300)

        assertTrue(tracker.apply(events, 400).isEmpty())
        assertEquals("second.app", tracker.currentPackage)
    }

    @Test
    fun eventsSharingTimestampKeepTheirOrderAndAreDeduplicated() {
        val events = listOf(resumed(100, "first.app"), paused(100, "first.app"), resumed(100, "first.app"))
        tracker.apply(events, 200)

        assertEquals("first.app", tracker.currentPackage)
        assertTrue(tracker.apply(events, 300).isEmpty())
        tracker.apply(events + paused(100, "first.app"), 400)
        assertEquals("", tracker.currentPackage)
    }

    @Test
    fun newlyPublishedEventAtLastTimestampIsApplied() {
        tracker.apply(listOf(resumed(100, "first.app")), 200)
        tracker.apply(listOf(resumed(100, "first.app"), paused(100, "first.app"), resumed(100, "second.app")), 300)

        assertEquals("second.app", tracker.currentPackage)
    }

    @Test
    fun unorderedQueryEventsAreAppliedChronologically() {
        tracker.apply(listOf(resumed(300, "second.app"), resumed(100, "first.app"), paused(200, "first.app")), 400)

        assertEquals("first.app", tracker.packageAt(150))
        assertEquals("second.app", tracker.currentPackage)
    }

    @Test
    fun latePublishedPauseCorrectsHistoryAndDoesNotResurrectOldApp() {
        tracker.apply(listOf(resumed(100, "first.app"), resumed(300, "second.app")), 400)
        tracker.apply(listOf(
            resumed(100, "first.app"), paused(200, "first.app"),
            resumed(300, "second.app"), paused(500, "second.app")
        ), 600)

        assertEquals("first.app", tracker.packageAt(150))
        assertEquals("", tracker.packageAt(250))
        assertEquals("second.app", tracker.packageAt(400))
        assertEquals("", tracker.currentPackage)
    }

    @Test
    fun overlappingOldEventsCannotResurrectPrunedActivities() {
        val shortTracker = ForegroundAppTracker(retentionMs = 100)
        shortTracker.apply(listOf(resumed(100, "first.app"), paused(200, "first.app")), 350)
        shortTracker.apply(listOf(resumed(100, "first.app"), paused(200, "first.app")), 450)

        assertEquals("", shortTracker.currentPackage)
    }

    @Test
    fun retentionPreservesBoundaryButDropsOlderAppHistory() {
        val shortTracker = ForegroundAppTracker(retentionMs = 100)
        shortTracker.apply(listOf(
            resumed(100, "first.app"), paused(150, "first.app"), resumed(160, "second.app"),
            paused(280, "second.app"), resumed(290, "third.app")
        ), 300)

        assertEquals("", shortTracker.packageAt(100))
        assertEquals("second.app", shortTracker.packageAt(200))
        assertEquals("third.app", shortTracker.packageAt(299))
    }

    @Test
    fun resetRemovesPermissionRevokedOrClockChangedState() {
        tracker.apply(listOf(resumed(1_000, "first.app")), 2_000)
        tracker.clear()

        assertEquals("", tracker.currentPackage)
        assertEquals("", tracker.packageAt(1_500))
        tracker.apply(listOf(resumed(100, "second.app")), 200)
        assertEquals("second.app", tracker.currentPackage)
    }

    @Test
    fun blankPackagesAndUnknownSampleTimesAreNotFabricated() {
        tracker.apply(listOf(resumed(100, "")), 200)
        assertEquals("", tracker.currentPackage)
        assertEquals("", tracker.packageAt(50))
    }

    private fun resumed(time: Long, pkg: String, activity: String = "Activity") =
        ForegroundAppEvent(time, ForegroundEventType.RESUMED, pkg, activity)

    private fun paused(time: Long, pkg: String, activity: String = "Activity") =
        ForegroundAppEvent(time, ForegroundEventType.PAUSED, pkg, activity)
}
