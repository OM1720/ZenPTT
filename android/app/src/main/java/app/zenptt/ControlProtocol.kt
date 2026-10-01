// Serializes ZenPTT v4 commands and strictly parses server control events.
package app.zenptt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val ECHO_CHANNEL = "ECHO"

fun isEchoChannel(channel: String?): Boolean = channel == ECHO_CHANNEL

data class AudioPolicy(
    val recoveryHorizonMs: Int,
) {
    init {
        require(recoveryHorizonMs in 1_000..60_000)
        require(recoveryHorizonMs % AudioConstants.FRAME_DURATION_MS == 0)
    }

    val recoveryFrames: Int get() = recoveryHorizonMs / AudioConstants.FRAME_DURATION_MS
    val serverHistoryMs: Int get() = maxOf(15_000, 3 * recoveryHorizonMs)
    val receiveFifoFrames: Int get() =
        maxOf(60_000, serverHistoryMs) / AudioConstants.FRAME_DURATION_MS
    val ackWatchdogMs: Long get() =
        (3L * recoveryHorizonMs / 5).coerceIn(500L, 3_000L)
    val fairAgeMs: Long get() = recoveryHorizonMs / 10L
    val poorAgeMs: Long get() = recoveryHorizonMs / 5L

    companion object {
        val Default = AudioPolicy(5_000)
    }
}

data class FloorSnapshot(val burstId: String, val burstIndex: Long, val owned: Boolean)

data class SessionSnapshot(
    val channel: String,
    val memberId: String,
    val resumeToken: String,
    val generation: Int,
    val channelIncarnationId: String,
    val revision: Long,
    val participantCount: Int,
    val eligibleFromIndex: Long,
    val nextBurstIndex: Long,
    val audioPolicy: AudioPolicy,
    val floor: FloorSnapshot?,
)

data class AudioSequenceRange(val firstSequence: Long, val count: Int) {
    val nextSequence: Long get() = firstSequence + count
}

sealed interface ControlEvent {
    data class Snapshot(val session: SessionSnapshot) : ControlEvent
    data class ResumeRejected(val reason: String) : ControlEvent
    data class ChannelState(
        val revision: Long,
        val participantCount: Int,
        val nextBurstIndex: Long,
        val floor: FloorSnapshot?,
    ) : ControlEvent
    data class PttGranted(
        val requestId: String,
        val burstId: String,
        val burstIndex: Long,
        val leaseRemainingMs: Int,
    ) : ControlEvent
    data class PttDenied(val requestId: String, val reason: String) : ControlEvent
    data class UplinkAck(
        val burstId: String,
        val nextSequence: Long,
    ) : ControlEvent
    data class AudioRejected(
        val burstId: String,
        val firstSequence: Long,
        val nextSequence: Long,
        val reason: String,
    ) : ControlEvent
    data class BurstStarted(val burstId: String, val burstIndex: Long) : ControlEvent
    data class BurstReleased(val burstId: String, val burstIndex: Long, val reason: String) : ControlEvent
    data class BurstGaps(
        val burstId: String,
        val burstIndex: Long,
        val ranges: List<AudioSequenceRange>,
    ) : ControlEvent
    data class BurstSealed(
        val burstId: String,
        val burstIndex: Long,
        val finalNextSequence: Long,
        val reason: String,
    ) : ControlEvent
    data class ListenReset(val burstIndex: Long, val nextSequence: Long, val reason: String) : ControlEvent
    data class PttEnded(
        val burstId: String,
        val burstIndex: Long,
        val state: String,
        val reason: String,
        val finalNextSequence: Long?,
    ) : ControlEvent
    data class Pong(val id: Int, val sentAtMs: Long) : ControlEvent
    data class Error(val code: String, val message: String) : ControlEvent
}

object ControlProtocol {
    private val json = Json { ignoreUnknownKeys = false }

    fun join(channel: String) = message("join") { put("channel", channel) }

    fun joinEcho() = message("join_echo") {}

    fun resume(resumeToken: String, generation: Int) = message("resume") {
        put("resume_token", resumeToken)
        put("generation", generation)
    }

    fun listen(burstIndex: Long, nextSequence: Long) = message("listen") {
        put("burst_index", burstIndex)
        put("next_sequence", nextSequence)
    }

    fun pttRequest(requestId: String) = message("ptt_request") { put("request_id", requestId) }
    fun pttCancel(requestId: String) = message("ptt_cancel") { put("request_id", requestId) }

    fun burstEnd(burstId: String, finalNextSequence: Long) = message("burst_end") {
        put("burst_id", burstId)
        put("final_next_sequence", finalNextSequence)
    }

    fun ping(id: Int, sentAtMs: Long) = message("ping") {
        put("id", id)
        put("sent_at_ms", sentAtMs)
    }

    fun disconnect() = message("disconnect") {}

    fun parse(text: String): ControlEvent {
        val value = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid message")
        return when (value.string("type")) {
            "snapshot" -> {
                value.requireKeys(SNAPSHOT_KEYS)
                ControlEvent.Snapshot(value.snapshot())
            }
            "resume_rejected" -> value.withKeys("reason") { ControlEvent.ResumeRejected(string("reason")) }
            "channel_state" -> value.withKeys(
                "revision", "participant_count", "next_burst_index", "floor",
            ) {
                ControlEvent.ChannelState(
                    nonNegativeLong("revision"), positiveInt("participant_count"),
                    nonNegativeLong("next_burst_index"), floor("floor"),
                )
            }
            "ptt_granted" -> value.withKeys(
                "request_id", "burst_id", "burst_index", "lease_remaining_ms",
            ) {
                ControlEvent.PttGranted(
                    string("request_id"), string("burst_id"), nonNegativeLong("burst_index"),
                    nonNegativeInt("lease_remaining_ms"),
                )
            }
            "ptt_denied" -> value.withKeys("request_id", "reason") {
                ControlEvent.PttDenied(string("request_id"), string("reason"))
            }
            "uplink_ack" -> value.withKeys(
                "burst_id", "next_sequence",
            ) {
                ControlEvent.UplinkAck(string("burst_id"), nonNegativeLong("next_sequence"))
            }
            "audio_rejected" -> value.withKeys(
                "burst_id", "first_sequence", "next_sequence", "reason",
            ) {
                val first = nonNegativeLong("first_sequence")
                val next = nonNegativeLong("next_sequence").also { check(it >= first) }
                ControlEvent.AudioRejected(string("burst_id"), first, next, string("reason"))
            }
            "burst_started" -> value.withKeys("burst_id", "burst_index") {
                ControlEvent.BurstStarted(string("burst_id"), nonNegativeLong("burst_index"))
            }
            "burst_released" -> value.withKeys("burst_id", "burst_index", "reason") {
                ControlEvent.BurstReleased(
                    string("burst_id"), nonNegativeLong("burst_index"), string("reason"),
                )
            }
            "burst_gaps" -> value.withKeys("burst_id", "burst_index", "ranges") {
                ControlEvent.BurstGaps(
                    string("burst_id"), nonNegativeLong("burst_index"), ranges("ranges"),
                )
            }
            "burst_sealed" -> value.withKeys(
                "burst_id", "burst_index", "final_next_sequence", "reason",
            ) {
                ControlEvent.BurstSealed(
                    string("burst_id"), nonNegativeLong("burst_index"),
                    nonNegativeLong("final_next_sequence"), string("reason"),
                )
            }
            "listen_reset" -> value.withKeys("burst_index", "next_sequence", "reason") {
                ControlEvent.ListenReset(
                    nonNegativeLong("burst_index"), nonNegativeLong("next_sequence"), string("reason"),
                )
            }
            "ptt_ended" -> value.withKeys(
                "burst_id", "burst_index", "state", "reason", "final_next_sequence",
            ) {
                ControlEvent.PttEnded(
                    string("burst_id"), nonNegativeLong("burst_index"), string("state"), string("reason"),
                    nullableNonNegativeLong("final_next_sequence"),
                )
            }
            "pong" -> value.withKeys("id", "sent_at_ms") { ControlEvent.Pong(int("id"), long("sent_at_ms")) }
            "error" -> value.withKeys("code", "message") {
                ControlEvent.Error(string("code"), string("message"))
            }
            else -> error("Unknown message")
        }
    }

    private fun JsonObject.snapshot() = SessionSnapshot(
        string("channel"), string("member_id"), stringAllowEmpty("resume_token"), positiveInt("generation"),
        stringAllowEmpty("channel_incarnation_id"), nonNegativeLong("revision"), positiveInt("participant_count"),
        nonNegativeLong("eligible_from_index"), nonNegativeLong("next_burst_index"), audioPolicy("audio_policy"),
        floor("floor"),
    )

    private fun JsonObject.audioPolicy(key: String): AudioPolicy {
        val value = getValue(key).jsonObject
        value.requireKeys(AUDIO_POLICY_KEYS)
        return AudioPolicy(
            value.positiveInt("recovery_horizon_ms"),
        )
    }

    private fun JsonObject.floor(key: String): FloorSnapshot? {
        val element = getValue(key)
        if (element is JsonNull) return null
        val value = element.jsonObject
        value.requireKeys(setOf("burst_id", "burst_index", "owned"))
        return FloorSnapshot(value.string("burst_id"), value.nonNegativeLong("burst_index"), value.boolean("owned"))
    }

    private fun JsonObject.ranges(key: String): List<AudioSequenceRange> {
        val array = getValue(key) as? JsonArray ?: error("Invalid ranges")
        return array.map { element ->
            val value = element.jsonObject
            value.requireKeys(setOf("first_sequence", "count"))
            AudioSequenceRange(value.nonNegativeLong("first_sequence"), value.positiveInt("count"))
        }.also { ranges ->
            check(ranges.zipWithNext().all { (first, second) -> first.nextSequence <= second.firstSequence })
        }
    }

    private fun message(type: String, content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        kotlinx.serialization.json.buildJsonObject {
            put("type", type)
            content()
        }.toString()

    private fun JsonObject.string(key: String): String = stringAllowEmpty(key).also { check(it.isNotEmpty()) }

    private fun JsonObject.stringAllowEmpty(key: String): String {
        val primitive = getValue(key).jsonPrimitive
        check(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.int(key: String): Int = number(key).int
    private fun JsonObject.long(key: String): Long = number(key).long
    private fun JsonObject.positiveInt(key: String): Int = int(key).also { check(it > 0) }
    private fun JsonObject.nonNegativeInt(key: String): Int = int(key).also { check(it >= 0) }
    private fun JsonObject.nonNegativeLong(key: String): Long = long(key).also { check(it >= 0) }
    private fun JsonObject.nullableNonNegativeLong(key: String): Long? = nullableNumber(key)?.long?.also { check(it >= 0) }

    private fun JsonObject.number(key: String): JsonPrimitive {
        val primitive = getValue(key).jsonPrimitive
        check(!primitive.isString)
        return primitive
    }

    private fun JsonObject.nullableNumber(key: String): JsonPrimitive? {
        val element = getValue(key)
        if (element is JsonNull) return null
        return element.jsonPrimitive.also { check(!it.isString) }
    }

    private fun JsonObject.boolean(key: String): Boolean = number(key).boolean

    private fun JsonObject.requireKeys(required: Set<String>) {
        check(keys == required)
    }

    private inline fun <T> JsonObject.withKeys(vararg required: String, block: JsonObject.() -> T): T {
        requireKeys(setOf("type", *required))
        return block()
    }

    private val SNAPSHOT_KEYS = setOf(
        "type", "channel", "member_id", "resume_token", "generation", "channel_incarnation_id",
        "revision", "participant_count", "eligible_from_index", "next_burst_index", "audio_policy", "floor",
    )
    private val AUDIO_POLICY_KEYS = setOf("recovery_horizon_ms")
}
