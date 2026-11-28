package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FoxyPhase { IDLE, STARTING, AUTH, EDGE, TUNNEL, CONNECTED, FAILED }

enum class FoxyStepState { RUNNING, DONE, FAILED }

/** Stage-level state; unlike [FoxyStepState] a stage can still be waiting its turn. */
enum class FoxyStageState { PENDING, RUNNING, DONE, FAILED }

data class FoxyStep(val text: String, val state: FoxyStepState)

/**
 * The real connect stages, in the order the engine walks through them. The UI shows them one by
 * one (PoW/Tor style) instead of a coarse four-line script: every stage below is entered by an
 * actual step in [FoxyConnectionCoordinator], never by a timer.
 */
enum class FoxyStage {
    PREPARE,
    ACCOUNT,
    SIGN_IN,
    PASS,
    SERVERS,
    SELECT,
    TLS,
    H2,
    TUNNEL,
    TUN,
    RELAY,
    VERIFY,
    READY,
    ;

    /** Coarse phase the overlay pill still colours itself from. */
    val phase: FoxyPhase
        get() = when (this) {
            PREPARE, ACCOUNT -> FoxyPhase.STARTING
            SIGN_IN, PASS -> FoxyPhase.AUTH
            SERVERS, SELECT, TLS, H2 -> FoxyPhase.EDGE
            TUNNEL, TUN, RELAY, VERIFY -> FoxyPhase.TUNNEL
            READY -> FoxyPhase.CONNECTED
        }
}

data class FoxyStageStatus(val state: FoxyStageState, val detail: String = "")

data class FoxyUiStatus(
    val phase: FoxyPhase = FoxyPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val edgeHost: String = "",
    val countryCode: String = "",
    val exitIp: String = "",
    val steps: List<FoxyStep> = emptyList(),
    /** State of every connect stage; empty before the first attempt. */
    val stages: Map<FoxyStage, FoxyStageStatus> = emptyMap(),
    /** Wall clock when the current attempt started (0 = never connected). */
    val startedAt: Long = 0L,
    /** Wall clock when the attempt finished (0 = still running). */
    val endedAt: Long = 0L,
) {
    fun stageStatus(stage: FoxyStage): FoxyStageStatus? = stages[stage]

    fun stageState(stage: FoxyStage): FoxyStageState? = stages[stage]?.state

    /** Most recent stage that is running or failed — the one the UI should focus on. */
    val currentStage: FoxyStage?
        get() = FoxyStage.entries.firstOrNull { stageState(it) == FoxyStageState.FAILED }
            ?: FoxyStage.entries.firstOrNull { stageState(it) == FoxyStageState.RUNNING }
            ?: FoxyStage.entries.lastOrNull { stageState(it) == FoxyStageState.DONE }

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
            stages = current.stages,
            startedAt = startedAt,
            endedAt = endedAt,
        )
        if (detail.isNotBlank()) note(detail)
        if (phase == FoxyPhase.CONNECTED) finish(FoxyStepState.DONE)
        if (phase == FoxyPhase.FAILED) finish(FoxyStepState.FAILED)
    }

    /**
     * Marks [stage] as the one in progress. Everything before it becomes done, everything after
     * stays pending. This is what advances the sheet one row at a time.
     */
    fun enter(stage: FoxyStage, detail: String = "") {
        val current = mutableStatus.value
        val stages = LinkedHashMap<FoxyStage, FoxyStageStatus>()
        for (candidate in FoxyStage.entries) {
            stages[candidate] = when {
                candidate == stage -> FoxyStageStatus(FoxyStageState.RUNNING, detail)
                candidate.ordinal < stage.ordinal -> {
                    val previous = current.stages[candidate]
                    if (previous?.state == FoxyStageState.FAILED) previous
                    else FoxyStageStatus(FoxyStageState.DONE, previous?.detail.orEmpty())
                }
                else -> FoxyStageStatus(FoxyStageState.PENDING)
            }
        }
        mutableStatus.value = current.copy(
            phase = stage.phase,
            stages = stages,
            detail = detail.ifBlank { current.detail },
            startedAt = if (current.startedAt == 0L || stage == FoxyStage.PREPARE) {
                System.currentTimeMillis()
            } else {
                current.startedAt
            },
            endedAt = if (stage == FoxyStage.READY) System.currentTimeMillis() else 0L,
        )
        if (detail.isNotBlank()) note(detail)
        if (stage == FoxyStage.READY) {
            mutableStatus.value = mutableStatus.value.copy(phase = FoxyPhase.CONNECTED)
        }
    }

    /** Closes [stage] without moving to the next one (e.g. the relay is up, health is pending). */
    fun done(stage: FoxyStage) {
        val current = mutableStatus.value
        val entry = current.stages[stage] ?: return
        if (entry.state == FoxyStageState.DONE) return
        mutableStatus.value = current.copy(
            stages = current.stages + (stage to entry.copy(state = FoxyStageState.DONE)),
        )
    }

    /** Pins the failure to the stage that actually failed; the ones before it stay done. */
    fun fail(stage: FoxyStage? = null, detail: String = "") {
        val current = mutableStatus.value
        val target = stage ?: current.currentStage ?: FoxyStage.PREPARE
        val stages = LinkedHashMap<FoxyStage, FoxyStageStatus>()
        for (candidate in FoxyStage.entries) {
            val previous = current.stages[candidate]
            stages[candidate] = when {
                candidate == target -> FoxyStageStatus(FoxyStageState.FAILED, detail.ifBlank { previous?.detail.orEmpty() })
                previous != null -> previous
                candidate.ordinal < target.ordinal -> FoxyStageStatus(FoxyStageState.DONE)
                else -> FoxyStageStatus(FoxyStageState.PENDING)
            }
        }
        mutableStatus.value = current.copy(
            phase = FoxyPhase.FAILED,
            stages = stages,
            detail = detail.ifBlank { current.detail },
            endedAt = System.currentTimeMillis(),
        )
        finish(FoxyStepState.FAILED)
        if (detail.isNotBlank()) note(detail, failed = true)
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

    fun setExitIp(ip: String) {
        mutableStatus.value = mutableStatus.value.copy(exitIp = ip)
    }

    fun setEdge(host: String, countryCode: String) {
        mutableStatus.value = mutableStatus.value.copy(edgeHost = host, countryCode = countryCode)
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
