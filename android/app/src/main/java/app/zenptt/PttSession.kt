// Pure PTT/floor state machine. Physical hold survives network transport replacement.
package app.zenptt

import java.util.UUID

sealed interface PttAction {
    data class Request(val requestId: String) : PttAction
    data class Cancel(val requestId: String) : PttAction
    data class Finish(val burstId: String, val interrupted: Boolean = false) : PttAction
    data object StartCapture : PttAction
    data object GrantCapture : PttAction
    data object StopCapture : PttAction
}

data class PttPressResult(
    val before: SessionStatus,
    val after: SessionStatus,
    val actions: List<PttAction>,
) {
    val requestCreated: Boolean
        get() = actions.any { it is PttAction.Request }
}

enum class LocalTerminalIndicator { None, ChannelFree, Suppress }

data class PttGrantResult(
    val recognized: Boolean,
    val actions: List<PttAction>,
)

data class PttTerminalResult(
    val indicator: LocalTerminalIndicator,
    val actions: List<PttAction>,
)

data class LocalAudioFailureResult(
    val wasTransmitting: Boolean,
    val actions: List<PttAction>,
)

class PttSession(private val requestIdFactory: () -> String = { UUID.randomUUID().toString() }) {
    private var state = PttState()

    val status: SessionStatus
        get() = when (state.local) {
            LocalPttPhase.Idle -> when (state.remote) {
                RemotePttPhase.Idle -> SessionStatus.Ready
                RemotePttPhase.Receiving -> SessionStatus.Receiving
                RemotePttPhase.Echo -> SessionStatus.PlayingEcho
            }
            LocalPttPhase.Deferred -> SessionStatus.Ready
            is LocalPttPhase.Blocked -> SessionStatus.Busy
            is LocalPttPhase.Requesting -> SessionStatus.Requesting
            is LocalPttPhase.Canceling -> SessionStatus.Releasing
            is LocalPttPhase.Transmitting -> SessionStatus.Transmitting
            is LocalPttPhase.Finishing -> SessionStatus.Releasing
            is LocalPttPhase.RecoveringRequest,
            is LocalPttPhase.RecoveringBurst,
            -> SessionStatus.Reconnecting
        }

    fun ready(preservePhysicalHold: Boolean = false) {
        state = PttState(
            held = state.held.takeIf { preservePhysicalHold } ?: false,
        )
    }

    fun press(): PttPressResult {
        val before = status
        val actions = if (state.held) {
            emptyList()
        } else when (status) {
            SessionStatus.Ready -> beginRequest()
            SessionStatus.Receiving -> {
                state = state.copy(held = true, local = LocalPttPhase.Blocked())
                emptyList()
            }
            SessionStatus.Connecting,
            SessionStatus.Reconnecting,
            SessionStatus.ConnectionError,
            -> {
                state = state.copy(held = true)
                emptyList()
            }
            else -> emptyList()
        }
        return PttPressResult(before, status, actions)
    }

    fun deferPress() {
        if (!state.held) state = state.copy(held = true, local = LocalPttPhase.Deferred)
    }

    fun release(): List<PttAction> {
        if (!state.held) return emptyList()
        state = state.copy(held = false)
        return when (val local = state.local) {
            is LocalPttPhase.RecoveringRequest -> {
                state = state.copy(local = local.copy(cancelOnResume = true))
                emptyList()
            }
            is LocalPttPhase.Requesting -> {
                state = state.copy(
                    local = LocalPttPhase.Canceling(local.requestId),
                    lateGrantRequestId = local.requestId,
                )
                listOf(PttAction.StopCapture, PttAction.Cancel(local.requestId))
            }
            is LocalPttPhase.Transmitting -> finish(local.burstId, local.indicator)
            is LocalPttPhase.RecoveringBurst -> finish(local.burstId, local.indicator)
            LocalPttPhase.Deferred -> {
                state = state.copy(local = LocalPttPhase.Idle)
                emptyList()
            }
            is LocalPttPhase.Blocked -> {
                state = state.copy(local = LocalPttPhase.Idle)
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun controlSent() {
        if (state.local is LocalPttPhase.Canceling || state.local is LocalPttPhase.Finishing) {
            state = state.copy(local = LocalPttPhase.Idle)
        }
    }

    fun granted(
        id: String,
        grantedBurstId: String = "00000000-0000-4000-8000-000000000001",
    ): PttGrantResult {
        if (id == state.lateGrantRequestId) {
            state = state.copy(lateGrantRequestId = null)
            return PttGrantResult(true, listOf(PttAction.Finish(grantedBurstId)))
        }
        val requesting = state.local as? LocalPttPhase.Requesting
            ?: return PttGrantResult(false, emptyList())
        if (id != requesting.requestId) return PttGrantResult(false, emptyList())
        val actions = if (state.held) {
            state = state.copy(
                local = LocalPttPhase.Transmitting(
                    grantedBurstId,
                    LocalTerminalIndicator.ChannelFree,
                ),
            )
            listOf(PttAction.GrantCapture)
        } else {
            state = state.copy(local = LocalPttPhase.Idle)
            listOf(PttAction.Finish(grantedBurstId))
        }
        return PttGrantResult(true, actions)
    }

    fun requestFailed(id: String): List<PttAction> {
        val requesting = state.local as? LocalPttPhase.Requesting ?: return emptyList()
        if (id != requesting.requestId) return emptyList()
        state = state.copy(
            held = false,
            local = LocalPttPhase.Idle,
            lateGrantRequestId = id,
        )
        return listOf(PttAction.StopCapture, PttAction.Cancel(id))
    }

    fun denied(id: String, reason: String): List<PttAction> {
        val requesting = state.local as? LocalPttPhase.Requesting ?: return emptyList()
        if (id != requesting.requestId) return emptyList()
        val channelBusy = reason == "channel_busy"
        state = state.copy(
            held = state.held && channelBusy,
            local = if (channelBusy && state.held) LocalPttPhase.Blocked() else LocalPttPhase.Idle,
            lateGrantRequestId = null,
        )
        return listOf(PttAction.StopCapture)
    }

    fun resumed(floor: FloorSnapshot? = null): List<PttAction> {
        val recovering = state.local
        if (recovering is LocalPttPhase.Finishing) {
            state = state.copy(
                local = recovering,
                remote = if (floor != null && !floor.owned) {
                    RemotePttPhase.Receiving
                } else RemotePttPhase.Idle,
                lateGrantRequestId = null,
            )
            return emptyList()
        }
        state = state.copy(
            local = LocalPttPhase.Idle,
            remote = RemotePttPhase.Idle,
            lateGrantRequestId = null,
        )
        if (recovering is LocalPttPhase.RecoveringBurst) {
            if (floor?.owned == true && floor.burstId == recovering.burstId) {
                state = state.copy(
                    local = LocalPttPhase.Transmitting(recovering.burstId, recovering.indicator),
                )
                return emptyList()
            }
            state = state.copy(
                local = LocalPttPhase.Finishing(
                    recovering.burstId,
                    recovering.indicator,
                    restartWhenRemoteIdle = state.held,
                ),
                remote = if (floor != null && !floor.owned) {
                    RemotePttPhase.Receiving
                } else RemotePttPhase.Idle,
            )
            return listOf(PttAction.StopCapture, PttAction.Finish(recovering.burstId))
        }
        if (floor != null && !floor.owned) {
            state = state.copy(
                local = if (state.held) LocalPttPhase.Blocked() else LocalPttPhase.Idle,
                remote = RemotePttPhase.Receiving,
            )
            return emptyList()
        }
        if (recovering is LocalPttPhase.RecoveringRequest && floor?.owned == true) {
            return if (recovering.cancelOnResume || !state.held) {
                state = state.copy(
                    local = LocalPttPhase.Canceling(recovering.requestId),
                    lateGrantRequestId = recovering.requestId,
                )
                listOf(PttAction.Cancel(recovering.requestId))
            } else {
                state = state.copy(local = LocalPttPhase.Requesting(recovering.requestId))
                listOf(PttAction.StartCapture, PttAction.Request(recovering.requestId))
            }
        }
        return when {
            recovering == LocalPttPhase.Deferred && state.held -> beginRequest()
            recovering is LocalPttPhase.RecoveringRequest &&
                !recovering.cancelOnResume &&
                state.held -> beginRequest()
            else -> emptyList()
        }
    }

    fun remoteStarted() {
        state = state.copy(remote = RemotePttPhase.Receiving)
    }

    fun remoteEnded(): List<PttAction> {
        val blocked = state.local as? LocalPttPhase.Blocked
        state = state.copy(
            remote = RemotePttPhase.Idle,
            local = if (blocked != null) LocalPttPhase.Idle else state.local,
        )
        return if (blocked?.restartWhenRemoteIdle == true && state.held) {
            state = state.copy(held = false)
            beginRequest()
        } else emptyList()
    }

    fun localTransmissionActive(): Boolean =
        state.local is LocalPttPhase.Transmitting || state.local is LocalPttPhase.RecoveringBurst

    fun localRequestActive(): Boolean =
        state.local is LocalPttPhase.Requesting || state.local is LocalPttPhase.RecoveringRequest

    fun activeBurstId(): String? = when (val local = state.local) {
        is LocalPttPhase.Transmitting -> local.burstId
        is LocalPttPhase.RecoveringBurst -> local.burstId
        else -> null
    }

    fun localInterruptionNeeded(): Boolean =
        terminalIndicator() == LocalTerminalIndicator.ChannelFree

    fun markLocalInterruptionSignaled() {
        state = state.copy(local = when (val local = state.local) {
            is LocalPttPhase.Transmitting -> local.copy(indicator = LocalTerminalIndicator.Suppress)
            is LocalPttPhase.Finishing -> local.copy(indicator = LocalTerminalIndicator.Suppress)
            is LocalPttPhase.RecoveringBurst -> local.copy(indicator = LocalTerminalIndicator.Suppress)
            else -> local
        })
    }

    fun resetAfterServerEpochLoss(): List<PttAction> {
        val interrupted = localTransmissionActive() || state.local is LocalPttPhase.Requesting
        val stopCapture = interrupted || state.local is LocalPttPhase.Canceling
        state = PttState()
        return listOfNotNull(PttAction.StopCapture.takeIf { stopCapture })
    }

    fun pendingPhysicalHold(): Boolean =
        state.held && state.local == LocalPttPhase.Deferred

    fun localAudioFailed(): LocalAudioFailureResult {
        val local = state.local
        val pending = (local as? LocalPttPhase.Requesting)?.requestId
        val burst = when (local) {
            is LocalPttPhase.Transmitting -> local.burstId
            is LocalPttPhase.RecoveringBurst -> local.burstId
            else -> null
        }
        if (pending == null && burst == null) {
            return LocalAudioFailureResult(false, emptyList())
        }
        val reconnecting = local is LocalPttPhase.RecoveringRequest ||
            local is LocalPttPhase.RecoveringBurst
        val wasTransmitting = burst != null
        state = state.copy(
            held = state.held.takeIf { reconnecting } ?: false,
            local = burst?.let {
                LocalPttPhase.Finishing(it, LocalTerminalIndicator.Suppress)
            } ?: LocalPttPhase.Canceling(requireNotNull(pending)),
        )
        return LocalAudioFailureResult(
            wasTransmitting,
            listOfNotNull(
                PttAction.StopCapture,
                burst?.let { PttAction.Finish(it, interrupted = true) },
                pending?.let(PttAction::Cancel),
            ),
        )
    }

    fun echoPlaybackStarted(): List<PttAction> {
        val actions = if (status in CAPTURING_STATUSES) listOf(PttAction.StopCapture) else emptyList()
        state = state.copy(
            local = LocalPttPhase.Idle,
            remote = RemotePttPhase.Echo,
            lateGrantRequestId = null,
        )
        return actions
    }

    fun ended(endedBurstId: String): PttTerminalResult? {
        val local = state.local
        val activeEnded = when (local) {
            is LocalPttPhase.Transmitting -> local.burstId == endedBurstId
            is LocalPttPhase.RecoveringBurst -> local.burstId == endedBurstId
            else -> false
        }
        val releaseEnded = local is LocalPttPhase.Finishing && local.burstId == endedBurstId
        if (!activeEnded && !releaseEnded) return null
        val actions = if (status in CAPTURING_STATUSES) listOf(PttAction.StopCapture) else emptyList()
        val indicator = terminalIndicator()
        val restartWhenRemoteIdle = (local as? LocalPttPhase.Finishing)?.restartWhenRemoteIdle == true
        state = state.copy(
            local = if (state.held) {
                LocalPttPhase.Blocked(restartWhenRemoteIdle)
            } else LocalPttPhase.Idle,
            lateGrantRequestId = null,
        )
        val restartActions = if (
            restartWhenRemoteIdle &&
            state.held &&
            state.remote == RemotePttPhase.Idle
        ) {
            state = state.copy(held = false, local = LocalPttPhase.Idle)
            beginRequest()
        } else emptyList()
        return PttTerminalResult(indicator, actions + restartActions)
    }

    fun connectionLost(): List<PttAction> {
        val local = state.local
        val (nextLocal, actions) = when (local) {
            LocalPttPhase.Idle,
            LocalPttPhase.Deferred,
            is LocalPttPhase.Blocked,
            is LocalPttPhase.Finishing,
            is LocalPttPhase.RecoveringRequest,
            is LocalPttPhase.RecoveringBurst,
            -> local to emptyList()
            is LocalPttPhase.Requesting -> LocalPttPhase.RecoveringRequest(
                local.requestId,
                cancelOnResume = !state.held,
            ) to listOf(PttAction.StopCapture)
            is LocalPttPhase.Canceling -> LocalPttPhase.RecoveringRequest(
                local.requestId,
                cancelOnResume = true,
            ) to emptyList()
            is LocalPttPhase.Transmitting -> if (state.held) {
                LocalPttPhase.RecoveringBurst(local.burstId, local.indicator) to emptyList()
            } else {
                LocalPttPhase.Finishing(
                    local.burstId,
                    LocalTerminalIndicator.Suppress,
                ) to listOf(
                    PttAction.StopCapture,
                    PttAction.Finish(local.burstId, interrupted = true),
                )
            }
        }
        state = state.copy(
            local = nextLocal,
            remote = RemotePttPhase.Idle,
            lateGrantRequestId = null,
        )
        return actions
    }

    fun recoveryExpired(): LocalAudioFailureResult {
        state = state.copy(held = false)
        return localAudioFailed()
    }

    fun durationExpired(): LocalAudioFailureResult {
        state = state.copy(held = false)
        return localAudioFailed()
    }

    fun physicalHeld(): Boolean = state.held

    private fun beginRequest(): List<PttAction> {
        val id = requestIdFactory()
        state = state.copy(held = true, local = LocalPttPhase.Requesting(id))
        return listOf(PttAction.StartCapture, PttAction.Request(id))
    }

    private fun finish(
        burstId: String,
        indicator: LocalTerminalIndicator,
    ): List<PttAction> {
        state = state.copy(local = LocalPttPhase.Finishing(burstId, indicator))
        return listOf(PttAction.StopCapture, PttAction.Finish(burstId))
    }

    private fun terminalIndicator(): LocalTerminalIndicator = when (val local = state.local) {
        is LocalPttPhase.Transmitting -> local.indicator
        is LocalPttPhase.Finishing -> local.indicator
        is LocalPttPhase.RecoveringBurst -> local.indicator
        else -> LocalTerminalIndicator.None
    }

    private companion object {
        val CAPTURING_STATUSES = setOf(SessionStatus.Requesting, SessionStatus.Transmitting)
    }
}

private data class PttState(
    val held: Boolean = false,
    val local: LocalPttPhase = LocalPttPhase.Idle,
    val remote: RemotePttPhase = RemotePttPhase.Idle,
    val lateGrantRequestId: String? = null,
)

private enum class RemotePttPhase { Idle, Receiving, Echo }

private sealed interface LocalPttPhase {
    data object Idle : LocalPttPhase
    data object Deferred : LocalPttPhase
    data class Blocked(val restartWhenRemoteIdle: Boolean = false) : LocalPttPhase
    data class Requesting(val requestId: String) : LocalPttPhase
    data class Canceling(val requestId: String) : LocalPttPhase
    data class Transmitting(
        val burstId: String,
        val indicator: LocalTerminalIndicator,
    ) : LocalPttPhase
    data class Finishing(
        val burstId: String,
        val indicator: LocalTerminalIndicator,
        val restartWhenRemoteIdle: Boolean = false,
    ) : LocalPttPhase
    data class RecoveringRequest(
        val requestId: String,
        val cancelOnResume: Boolean,
    ) : LocalPttPhase
    data class RecoveringBurst(
        val burstId: String,
        val indicator: LocalTerminalIndicator,
    ) : LocalPttPhase
}
