package com.continuousauth.ui.compose

internal enum class PermissionGuidanceStep { NOTIFICATIONS, USAGE_STATS, BATTERY_OPTIMIZATION }

/** Keeps a settings round trip or dialog active until its result is delivered. */
internal class PermissionGuidanceCoordinator {
    private val offered = mutableSetOf<PermissionGuidanceStep>()
    val offeredSteps: Set<PermissionGuidanceStep>
        get() = offered.toSet()
    var activeStep: PermissionGuidanceStep? = null
        private set

    fun nextStep(
        needsNotifications: Boolean,
        needsUsageStats: Boolean,
        needsBatteryOptimization: Boolean
    ): PermissionGuidanceStep? {
        if (activeStep != null) return null
        val next = listOf(
            PermissionGuidanceStep.NOTIFICATIONS to needsNotifications,
            PermissionGuidanceStep.USAGE_STATS to needsUsageStats,
            PermissionGuidanceStep.BATTERY_OPTIMIZATION to needsBatteryOptimization
        ).firstOrNull { (step, needed) -> needed && step !in offered }?.first ?: return null
        offered += next
        activeStep = next
        return next
    }

    fun complete(step: PermissionGuidanceStep) {
        if (activeStep == step) activeStep = null
    }

    fun restore(offeredSteps: Set<PermissionGuidanceStep>, activeStep: PermissionGuidanceStep?) {
        offered.clear()
        offered += offeredSteps
        activeStep?.let { offered += it }
        this.activeStep = activeStep
    }
}
