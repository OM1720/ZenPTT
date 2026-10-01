package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSendDiagnosticsTest {
    @Test
    fun failedSendAndIntervalsAtOrBelowThresholdDoNotCreateEvents() {
        val diagnostics = SourceSendDiagnostics()
        diagnostics.observe(true, BURST_1, 0, 1, 0, 0, 0, 0, 1, false)
        diagnostics.observe(false, BURST_1, 1, 1, 10, 10, 500, 80, 1, false)
        diagnostics.observe(true, BURST_1, 1, 1, 10, 10, 100, 0, 1, false)

        val report = diagnostics.debugReport()
        assertTrue(report.contains("max:100ms,recorded:0,retained:0,suppressed:0"))
        assertTrue(report.endsWith("source_send_events="))
    }

    @Test
    fun eventContainsExactRangeAgeQueueGenerationAndRetransmitFields() {
        val diagnostics = SourceSendDiagnostics()
        diagnostics.observe(true, BURST_1, 10, 2, 80, 100, 100, 0, 2, false)
        diagnostics.observe(true, BURST_1, 12, 3, 170, 220, 250, 126, 3, true)

        assertTrue(
            diagnostics.debugReport().contains(
                "250:source_send_gap burst=111111 first=12 frames=3 gap=150ms " +
                    "oldest_age=80ms newest_age=30ms queue=126B " +
                    "generation=3 retransmit=true",
            ),
        )
    }

    @Test
    fun eventLimitResetsForNewBurst() {
        val diagnostics = SourceSendDiagnostics(maxEventsPerBurst = 2)
        repeat(4) { index ->
            val atMs = index * 101L
            diagnostics.observe(true, BURST_1, index.toLong(), 1, atMs, atMs, atMs, 0, 1, false)
        }
        diagnostics.observe(true, BURST_2, 0, 1, 1_000, 1_000, 1_000, 0, 1, false)
        diagnostics.observe(true, BURST_2, 1, 1, 1_101, 1_101, 1_101, 0, 1, false)

        val report = diagnostics.debugReport()
        assertTrue(report.contains("recorded:3,retained:3,suppressed:1"))
        assertEquals(2, report.split("burst=111111").size - 1)
        assertEquals(1, report.split("burst=222222").size - 1)
    }

    @Test
    fun eventRingNeverExceedsThirtyEntries() {
        val diagnostics = SourceSendDiagnostics()
        repeat(31) { index ->
            val burstId = "burst-$index"
            diagnostics.observe(true, burstId, 0, 1, 0, 0, 0, 0, 1, false)
            diagnostics.observe(true, burstId, 1, 1, 101, 101, 101, 0, 1, false)
        }

        val report = diagnostics.debugReport()
        assertTrue(report.contains("recorded:31,retained:30,suppressed:0"))
        assertFalse(report.substringAfter("source_send_events=").contains("burst=urst-0"))
    }

    private companion object {
        const val BURST_1 = "11111111-1111-4111-8111-111111111111"
        const val BURST_2 = "22222222-2222-4222-8222-222222222222"
    }
}
