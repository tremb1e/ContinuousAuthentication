package com.continuousauth.utils

internal enum class ForegroundEventType { RESUMED, PAUSED, CLEARED }

internal data class ForegroundAppEvent(
    val timestamp: Long,
    val type: ForegroundEventType,
    val packageName: String = "",
    val className: String = ""
)

/** Reduces overlapping UsageEvents queries to a bounded sensor-attribution timeline. */
internal class ForegroundAppTracker(private val retentionMs: Long = 120_000L) {
    data class Transition(val timestamp: Long, val packageName: String)

    private data class Activity(val packageName: String, val className: String)

    private val baselineActivities = linkedSetOf<Activity>()
    private var baselineTransition: Transition? = null
    private var discardedBeforeMs = Long.MIN_VALUE
    private val retainedEvents = mutableListOf<ForegroundAppEvent>()
    private val transitions = ArrayDeque<Transition>()

    var currentPackage: String = ""
        private set

    val latestTransition: Transition?
        get() = transitions.lastOrNull()

    fun apply(events: List<ForegroundAppEvent>, nowMs: Long): List<Transition> {
        val previousTransitions = transitions.toSet()
        val knownOccurrences = retainedEvents.groupingBy { it }.eachCount()
        val occurrences = mutableMapOf<ForegroundAppEvent, Int>()
        for (event in events) {
            if (event.timestamp < discardedBeforeMs) continue
            val occurrence = (occurrences[event] ?: 0) + 1
            occurrences[event] = occurrence
            if (occurrence > (knownOccurrences[event] ?: 0)) {
                retainedEvents += event
            }
        }
        retainedEvents.sortBy { it.timestamp }

        // Replaying permits a late-published pause to correct an earlier attribution.
        val resumedActivities = LinkedHashSet(baselineActivities)
        transitions.clear()
        baselineTransition?.let(transitions::addLast)
        currentPackage = baselineTransition?.packageName.orEmpty()
        for (event in retainedEvents) {
            val packageName = reduce(resumedActivities, event)
            if (currentPackage != packageName) {
                currentPackage = packageName
                transitions.addLast(Transition(event.timestamp, packageName))
            }
        }
        val changes = transitions.filterNot { it in previousTransitions }

        val cutoffMs = nowMs - retentionMs
        var discardCount = 0
        for (event in retainedEvents) {
            if (event.timestamp >= cutoffMs) break
            val packageName = reduce(baselineActivities, event)
            if (packageName != baselineTransition?.packageName.orEmpty()) {
                baselineTransition = Transition(event.timestamp, packageName)
            }
            discardCount++
        }
        retainedEvents.subList(0, discardCount).clear()
        discardedBeforeMs = maxOf(discardedBeforeMs, cutoffMs)
        // Preserve one preceding transition so an app active for hours does not expire.
        while (transitions.size > 1 && transitions[1].timestamp <= cutoffMs) {
            transitions.removeFirst()
        }
        return changes
    }

    private fun reduce(resumedActivities: MutableSet<Activity>, event: ForegroundAppEvent): String {
        val activity = Activity(event.packageName, event.className)
        when (event.type) {
            ForegroundEventType.RESUMED -> if (event.packageName.isNotEmpty()) {
                resumedActivities.remove(activity)
                resumedActivities.add(activity)
            }
            ForegroundEventType.PAUSED -> resumedActivities.remove(activity)
            ForegroundEventType.CLEARED -> resumedActivities.clear()
        }
        return resumedActivities.lastOrNull()?.packageName.orEmpty()
    }

    fun packageAt(timestamp: Long): String =
        transitions.lastOrNull { it.timestamp <= timestamp }?.packageName.orEmpty()

    fun clear() {
        baselineActivities.clear()
        baselineTransition = null
        discardedBeforeMs = Long.MIN_VALUE
        retainedEvents.clear()
        transitions.clear()
        currentPackage = ""
    }
}
