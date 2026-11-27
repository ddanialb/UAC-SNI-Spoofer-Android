package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyStep
import com.uacspoofer.mobile.engine.foxy.FoxyStepState
import com.uacspoofer.mobile.engine.foxy.FoxyUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connect overlay must show what the engine really did, not a fixed four-row script. */
class FoxyOverlayStepsTest {
    @Test
    fun realEngineStepsReplaceThePlaceholderHierarchy() {
        val status = FoxyUiStatus(
            phase = FoxyPhase.EDGE,
            steps = listOf(
                FoxyStep("guardian token ok", FoxyStepState.DONE),
                FoxyStep("edge lime121.mozillavpn.net:2499", FoxyStepState.DONE),
                FoxyStep("h2 connect", FoxyStepState.RUNNING),
            ),
        )

        val steps = ConnectProgressModel.foxySteps(status)

        assertEquals(3, steps.size)
        assertEquals("h2 connect", steps.last().english)
        assertEquals(MiniStepState.RUNNING, steps.last().state)
        assertEquals(MiniStepState.DONE, steps.first().state)
        // the coarse placeholder labels must not leak in once the engine talks
        assertFalse(steps.any { it.english == "Firefox edge" })
    }

    @Test
    fun aFailureMarksTheRunningStepFailed() {
        val status = FoxyUiStatus(
            phase = FoxyPhase.FAILED,
            steps = listOf(FoxyStep("h2 handshake refused", FoxyStepState.FAILED)),
        )

        assertEquals(MiniStepState.FAILED, ConnectProgressModel.foxySteps(status).single().state)
    }

    @Test
    fun thePlaceholderIsStillShownBeforeTheFirstMessage() {
        val steps = ConnectProgressModel.foxySteps(FoxyUiStatus(phase = FoxyPhase.AUTH))

        assertTrue(steps.isNotEmpty())
        assertEquals(MiniStepState.RUNNING, steps[ConnectProgressModel.focusIndex(steps)].state)
    }

    @Test
    fun theStoreKeepsHistoryAndClosesTheOpenStep() {
        FoxyStatusStore.reset()
        FoxyStatusStore.note("resolve guard")
        FoxyStatusStore.note("open tunnel")
        val steps = FoxyStatusStore.status.value.steps

        assertEquals(listOf("resolve guard", "open tunnel"), steps.map { it.text })
        assertEquals(FoxyStepState.DONE, steps.first().state)
        assertEquals(FoxyStepState.RUNNING, steps.last().state)
        FoxyStatusStore.reset()
    }
}
