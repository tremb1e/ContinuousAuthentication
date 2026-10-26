package com.continuousauth.service

import com.continuousauth.service.CollectionPauseState.Reason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPauseStateTest {
    @Test
    fun unlockingDoesNotUndoManualPause() {
        val state = CollectionPauseState()
        state.set(Reason.MANUAL, true)
        state.set(Reason.SCREEN, true)
        state.set(Reason.SCREEN, false)
        assertTrue(state.isPaused)
        assertFalse(state.allowBufferedUpload)
        state.set(Reason.MANUAL, false)
        assertFalse(state.isPaused)
    }

    @Test
    fun repeatedScreenStateReportsOnlyActualChanges() {
        val state = CollectionPauseState()
        assertFalse(state.set(Reason.SCREEN, false))
        assertTrue(state.set(Reason.SCREEN, true))
        assertFalse(state.set(Reason.SCREEN, true))
        assertTrue(state.isPaused)
        assertTrue(state.allowBufferedUpload)
        assertTrue(state.set(Reason.SCREEN, false))
        assertFalse(state.isPaused)
    }

    @Test
    fun screenPauseSurvivesManualResumeWhileLocked() {
        val state = CollectionPauseState()
        state.set(Reason.MANUAL, true)
        state.set(Reason.SCREEN, true)
        state.set(Reason.MANUAL, false)
        assertTrue(state.isPaused)
        assertTrue(state.allowBufferedUpload)
        state.set(Reason.SCREEN, false)
        assertFalse(state.isPaused)
    }

    @Test
    fun onlyScreenPauseAllowsSmartTransmissionOfBufferedData() {
        val state = CollectionPauseState()
        state.set(Reason.SCREEN, true)
        state.set(Reason.SCREEN, true)
        assertTrue(state.allowBufferedUpload)
        state.set(Reason.MANUAL, true)
        assertFalse(state.allowBufferedUpload)
        state.set(Reason.MANUAL, false)
        assertTrue(state.allowBufferedUpload)
    }
}
