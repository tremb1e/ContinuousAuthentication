package com.continuousauth.crypto

/** Persists a reservation before handing out numbers so process death cannot reuse them. */
internal class PacketSequenceAllocator(
    private val loadReservedThrough: () -> Long,
    private val persistReservedThrough: (Long) -> Boolean,
    private val reservationSize: Long = 100L
) {
    private var initialized = false
    private var current = 0L
    private var reservedThrough = 0L

    init {
        require(reservationSize > 0L)
    }

    @Synchronized
    fun initialize() {
        if (initialized) return
        val persisted = loadReservedThrough()
        require(persisted >= 0L) { "Invalid persisted packet sequence" }
        current = persisted
        reservedThrough = persisted
        initialized = true
    }

    @Synchronized
    fun next(): Long {
        initialize()
        if (current == reservedThrough) {
            val newReservation = Math.addExact(reservedThrough, reservationSize)
            check(persistReservedThrough(newReservation)) { "Unable to persist packet sequence reservation" }
            reservedThrough = newReservation
        }
        current += 1L
        return current
    }

    @Synchronized
    fun current(): Long = current

    companion object {
        fun migrateLegacyCheckpoint(checkpoint: Long): Long {
            require(checkpoint >= 0L) { "Invalid legacy packet sequence" }
            // The old implementation persisted only every 100 emitted packets.
            return Math.addExact(checkpoint, 100L)
        }
    }
}
