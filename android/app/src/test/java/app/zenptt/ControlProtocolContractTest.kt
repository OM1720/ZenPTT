package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlProtocolContractTest {
    @Test
    fun recoveryPolicyMatchesServerContractVectors() {
        val expected = listOf(
            listOf(1_000, 50, 15_000, 600, 3_000),
            listOf(5_000, 250, 15_000, 3_000, 3_000),
            listOf(60_000, 3_000, 180_000, 3_000, 9_000),
        )

        expected.forEach { (horizon, frames, history, watchdog, fifo) ->
            val policy = AudioPolicy(horizon)
            assertEquals(frames, policy.recoveryFrames)
            assertEquals(history, policy.serverHistoryMs)
            assertEquals(watchdog.toLong(), policy.ackWatchdogMs)
            assertEquals(fifo, policy.receiveFifoFrames)
        }
    }

    @Test
    fun clientMessagesUseRollingV4Contract() {
        assertEquals("{\"type\":\"ptt_request\",\"request_id\":\"r1\"}", ControlProtocol.pttRequest("r1"))
        assertEquals("{\"type\":\"listen\",\"burst_index\":2,\"next_sequence\":7}", ControlProtocol.listen(2, 7))
        assertEquals(
            "{\"type\":\"burst_end\",\"burst_id\":\"b1\",\"final_next_sequence\":9}",
            ControlProtocol.burstEnd("b1", 9),
        )
    }

    @Test
    fun parsesSnapshotAudioPolicyAndNullableFloor() {
        val event = ControlProtocol.parse(
            """{"type":"snapshot","channel":"A","member_id":"m","resume_token":"t","generation":1,"channel_incarnation_id":"i","revision":0,"participant_count":1,"eligible_from_index":0,"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":5000},"floor":null}""",
        ) as ControlEvent.Snapshot
        assertEquals(5_000, event.session.audioPolicy.recoveryHorizonMs)
        assertEquals(15_000, event.session.audioPolicy.serverHistoryMs)
        assertEquals(3_000, event.session.audioPolicy.receiveFifoFrames)
        assertNull(event.session.floor)
    }

    @Test
    fun parsesCumulativeAckAndAuthoritativeGaps() {
        assertEquals(
            ControlEvent.UplinkAck("b", 7),
            ControlProtocol.parse(
                """{"type":"uplink_ack","burst_id":"b","next_sequence":7}""",
            ),
        )
        assertEquals(
            listOf(AudioSequenceRange(2, 3)),
            (ControlProtocol.parse(
                """{"type":"burst_gaps","burst_id":"b","burst_index":1,"ranges":[{"first_sequence":2,"count":3}]}""",
            ) as ControlEvent.BurstGaps).ranges,
        )
    }

    @Test
    fun rejectsUnknownFields() {
        assertThrows(IllegalStateException::class.java) {
            ControlProtocol.parse("""{"type":"burst_started","burst_id":"b","burst_index":0,"legacy":true}""")
        }
    }
}
