package com.continuousauth.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionGuidanceCoordinatorTest {
    @Test
    fun grantingNotificationsContinuesThroughUsageStatsAndBattery() {
        val coordinator = PermissionGuidanceCoordinator()
        assertEquals(PermissionGuidanceStep.NOTIFICATIONS, coordinator.nextStep(true, true, true))
        coordinator.complete(PermissionGuidanceStep.NOTIFICATIONS)
        assertEquals(PermissionGuidanceStep.USAGE_STATS, coordinator.nextStep(false, true, true))
        coordinator.complete(PermissionGuidanceStep.USAGE_STATS)
        assertEquals(PermissionGuidanceStep.BATTERY_OPTIMIZATION, coordinator.nextStep(false, false, true))
        coordinator.complete(PermissionGuidanceStep.BATTERY_OPTIMIZATION)
        assertNull(coordinator.nextStep(false, false, false))
    }

    @Test
    fun alreadyGrantedNotificationsDoNotBlockUsageStatsGuidance() {
        val coordinator = PermissionGuidanceCoordinator()
        assertEquals(PermissionGuidanceStep.USAGE_STATS, coordinator.nextStep(false, true, true))
    }

    @Test
    fun skippedOrDeniedPermissionAdvancesWithoutPromptLoop() {
        val coordinator = PermissionGuidanceCoordinator()
        coordinator.nextStep(true, true, true)
        coordinator.complete(PermissionGuidanceStep.NOTIFICATIONS)
        assertEquals(PermissionGuidanceStep.USAGE_STATS, coordinator.nextStep(true, true, true))
        coordinator.complete(PermissionGuidanceStep.USAGE_STATS)
        assertEquals(PermissionGuidanceStep.BATTERY_OPTIMIZATION, coordinator.nextStep(true, true, true))
        coordinator.complete(PermissionGuidanceStep.BATTERY_OPTIMIZATION)
        assertNull(coordinator.nextStep(true, true, true))
    }

    @Test
    fun recompositionOrResumeCannotOpenDuplicateDialogs() {
        val coordinator = PermissionGuidanceCoordinator()
        coordinator.nextStep(true, true, true)
        repeat(10) { assertNull(coordinator.nextStep(true, true, true)) }
        coordinator.complete(PermissionGuidanceStep.USAGE_STATS)
        assertNull(coordinator.nextStep(true, true, true))
    }

    @Test
    fun recreationKeepsActiveSettingsRoundTripAndContinuesOnItsResult() {
        val original = PermissionGuidanceCoordinator()
        original.nextStep(false, true, true)
        val restored = PermissionGuidanceCoordinator()
        restored.restore(original.offeredSteps, original.activeStep)

        assertNull(restored.nextStep(false, false, true))
        restored.complete(PermissionGuidanceStep.USAGE_STATS)
        assertEquals(PermissionGuidanceStep.BATTERY_OPTIMIZATION, restored.nextStep(false, false, true))
    }

    @Test
    fun satisfiedPermissionsNeedNoGuidance() {
        assertNull(PermissionGuidanceCoordinator().nextStep(false, false, false))
    }
}
