package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyStage
import com.uacspoofer.mobile.engine.foxy.FoxyStageState
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyStep
import com.uacspoofer.mobile.engine.foxy.FoxyStepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connect sheet/pill must advance through the engine's real ordered stages. */
class FoxyOverlayStepsTest {
    @Test
    fun enteringAStageClosesEverythingBeforeIt() {
        FoxyStatusStore.reset()
        FoxyStatusStore.enter(FoxyStage.PREPARE)
        FoxyStatusStore.enter(FoxyStage.ACCOUNT)
        FoxyStatusStore.enter(FoxyStage.SIGN_IN)
        val status = FoxyStatusStore.status.value

        assertEquals(FoxyStageState.DONE, status.stageState(FoxyStage.PREPARE))
        assertEquals(FoxyStageState.DONE, status.stageState(FoxyStage.ACCOUNT))
        assertEquals(FoxyStageState.RUNNING, status.stageState(FoxyStage.SIGN_IN))
        assertEquals(FoxyStageState.PENDING, status.stageState(FoxyStage.READY))
        assertEquals(FoxyStage.SIGN_IN, status.currentStage)
        FoxyStatusStore.reset()
    }

    @Test
    fun theSheetShowsEveryStageInOrder() {
        FoxyStatusStore.reset()
        FoxyStatusStore.enter(FoxyStage.RELAY, "Starting the secure tunnel...")

        // 13 real stages, the Persian title of the running one is the "secure tunnel" line.
        assertEquals(13, FoxyStage.entries.size)
        assertEquals("راه‌اندازی تونل امن", FoxyStageCopy.title(FoxyStage.RELAY, true))
        assertTrue(FoxyStageCopy.progress(FoxyStage.RELAY, true).contains("راه‌اندازی تونل امن"))
        FoxyStatusStore.reset()
    }

    @Test
    fun aFailureIsPinnedToTheStageThatFailed() {
        FoxyStatusStore.reset()
        FoxyStatusStore.enter(FoxyStage.SELECT)
        FoxyStatusStore.enter(FoxyStage.TLS)
        FoxyStatusStore.fail(FoxyStage.TLS, "edge certificate rejected")
        val status = FoxyStatusStore.status.value

        assertEquals(FoxyPhase.FAILED, status.phase)
        assertEquals(FoxyStageState.FAILED, status.stageState(FoxyStage.TLS))
        assertEquals(FoxyStageState.DONE, status.stageState(FoxyStage.SELECT))
        assertEquals(FoxyStage.TLS, status.currentStage)
        assertEquals("edge certificate rejected", status.detail)
        FoxyStatusStore.reset()
    }

    @Test
    fun theCompactPillWalksForwardOneGroupAtATime() {
        FoxyStatusStore.reset()
        FoxyStatusStore.enter(FoxyStage.PREPARE)
        FoxyStatusStore.enter(FoxyStage.ACCOUNT)
        FoxyStatusStore.enter(FoxyStage.SIGN_IN)

        val steps = ConnectProgressModel.foxySteps(FoxyStatusStore.status.value)

        assertEquals(5, steps.size)
        assertEquals(MiniStepState.DONE, steps[0].state)
        assertEquals(MiniStepState.RUNNING, steps[1].state)
        assertEquals(MiniStepState.PENDING, steps[2].state)
        FoxyStatusStore.reset()
    }

    @Test
    fun theStoreKeepsMessageHistoryAndClosesTheOpenStep() {
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
