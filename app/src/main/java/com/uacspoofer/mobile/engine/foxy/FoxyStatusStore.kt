package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FoxyPhase { IDLE, STARTING, AUTH, EDGE, TUNNEL, CONNECTED, FAILED }

enum class FoxyStepState { RUNNING, DONE, FAILED }

data class FoxyStep(val text: String, val state: FoxyStepState)

data class FoxyUiStatus(
    val phase: FoxyPhase = FoxyPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val edgeHost: String = "",
    val countryCode: String = "",
    val exitIp: String = "",
    val steps: List<FoxyStep> = emptyList(),
    /** Wall clock when the current attempt started (0 = never connected). */
    val startedAt: Long = 0L,
    /** Wall clock when the attempt finished (0 = still running). */
    val endedAt: Long = 0L,
) {
    companion object {
        val Idle = FoxyUiStatus()
    }
}

object FoxyStatusStore {
    private val mutableStatus = MutableStateFlow(FoxyUiStatus.Idle)
    val status: StateFlow<FoxyUiStatus> = mutableStatus.asStateFlow()

    fun update(
        phase: FoxyPhase,
        progressPercent: Int = 0,
        detail: String = "",
        edgeHost: String = mutableStatus.value.edgeHost,
        countryCode: String = mutableStatus.value.countryCode,
        exitIp: String = mutableStatus.value.exitIp,
    ) {
        val current = mutableStatus.value
        val startedAt = when {
            phase == FoxyPhase.STARTING -> System.currentTimeMillis()
            current.startedAt != 0L -> current.startedAt
            else -> 0L
        }
        val endedAt = when (phase) {
            FoxyPhase.CONNECTED, FoxyPhase.FAILED -> System.currentTimeMillis()
            else -> 0L
        }
        mutableStatus.value = FoxyUiStatus(
            phase = phase,
            progressPercent = progressPercent.coerceIn(0, 100),
            detail = detail,
            edgeHost = edgeHost,
            countryCode = countryCode,
            exitIp = exitIp,
            steps = current.steps,
            startedAt = startedAt,
            endedAt = endedAt,
        )
        if (detail.isNotBlank()) note(detail)
        if (phase == FoxyPhase.CONNECTED) finish(FoxyStepState.DONE)
        if (phase == FoxyPhase.FAILED) finish(FoxyStepState.FAILED)
    }

    fun note(text: String, failed: Boolean = false) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.text == clean) return
        if (last != null && last.state == FoxyStepState.RUNNING) {
            steps[steps.lastIndex] = last.copy(state = FoxyStepState.DONE)
        }
        steps += FoxyStep(clean, if (failed) FoxyStepState.FAILED else FoxyStepState.RUNNING)
        mutableStatus.value = current.copy(steps = steps.takeLast(24))
    }

    private fun finish(state: FoxyStepState) {
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val lastIdx = steps.indexOfLast { it.state == FoxyStepState.RUNNING }
        if (lastIdx >= 0) {
            steps[lastIdx] = steps[lastIdx].copy(state = state)
            mutableStatus.value = current.copy(steps = steps)
        }
    }

    fun reset() {
        mutableStatus.value = FoxyUiStatus.Idle
    }
}
