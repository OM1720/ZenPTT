package app.zenptt

import app.zenptt.headset.*

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class ChannelViewModelTest {
    @Test
    fun debugReportSerializesWithUplinkAcknowledgement() {
        val reading = CountDownLatch(1)
        val continueReading = CountDownLatch(1)
        val acknowledging = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val audio = RecordingCaptureAudio()
        val reports = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS), FakeConnection(), audio,
            ptt = PttSession { "request-1" }, diagnostics = Diagnostics { 0 },
            nowMs = {
                if (Thread.currentThread().name == "report-reader") {
                    reading.countDown()
                    check(continueReading.await(2, TimeUnit.SECONDS))
                }
                0L
            },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertTrue(audio.send(byteArrayOf(7)))
        val reader = thread(name = "report-reader") {
            try { reports += viewModel.debugReport("test", "test") }
            catch (failure: Throwable) { failures += failure }
        }
        var writer: Thread? = null
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            writer = thread {
                acknowledging.countDown()
                try { viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 1)) }
                catch (failure: Throwable) { failures += failure }
                finally { acknowledged.countDown() }
            }
            assertTrue(acknowledging.await(2, TimeUnit.SECONDS))
            assertFalse("ACK changed the store during a report", acknowledged.await(100, TimeUnit.MILLISECONDS))
        } finally {
            continueReading.countDown()
            reader.join(2_000)
            writer?.join(2_000)
            viewModel.close()
        }
        assertFalse(reader.isAlive)
        assertFalse(writer?.isAlive == true)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("ack_cursor:none:0"))
        assertEquals(0L, acknowledged.count)
    }

    @Test
    fun restoresAndSavesServerAddress() {
        val store = FakeStore("ws://10.0.2.2:8000")
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(store, connection)
        assertEquals("ws://10.0.2.2:8000", viewModel.state.value.serverAddress)
        assertEquals(ECHO_CHANNEL, viewModel.state.value.channelCode)

        viewModel.setChannelCode("room")
        viewModel.connect()

        assertEquals("ws://10.0.2.2:8000", store.saved)
        assertEquals("ROOM", store.savedChannel)
        assertEquals("ROOM", viewModel.state.value.currentChannel)
        assertEquals(SessionStatus.Connecting, viewModel.state.value.status)
        assertEquals("ROOM", connection.channel)
    }

    @Test
    fun restoresLastChannelAndKeepsItAfterDisconnect() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS, loadedChannel = "room")
        val viewModel = ChannelViewModel(store, FakeConnection())

        assertEquals("ROOM", viewModel.state.value.channelCode)

        viewModel.connect()
        viewModel.disconnect()

        assertEquals("ROOM", viewModel.state.value.channelCode)
    }

    @Test
    fun echoBecomesSelectedWithoutRemovingOrdinaryHistory() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS, loadedChannel = "ROOM")
        val viewModel = ChannelViewModel(store, FakeConnection())

        viewModel.connect(echo = true)

        assertEquals(ECHO_CHANNEL, store.savedChannel)
        assertEquals(ECHO_CHANNEL, viewModel.state.value.channelCode)
        assertEquals(listOf("ROOM", ECHO_CHANNEL), viewModel.state.value.frequencyChoices)
    }

    @Test
    fun ignoresInvalidStoredChannel() {
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS, loadedChannel = "invalid channel!"),
            FakeConnection(),
        )

        assertEquals(ECHO_CHANNEL, viewModel.state.value.channelCode)
    }

    @Test
    fun invalidInputDoesNotStartConnection() {
        val viewModel = ChannelViewModel(FakeStore(""), FakeConnection())
        viewModel.setServerAddress("bad")
        viewModel.setChannelCode("room")

        viewModel.connect()

        assertNull(viewModel.state.value.currentChannel)
        assertEquals("Enter a valid server address", viewModel.state.value.uiError?.message)
    }


    @Test
    fun validationCanRunBeforePermissionsWithoutStartingConnection() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("invalid channel!")

        assertFalse(viewModel.prepareConnection())

        assertNull(connection.channel)
        assertEquals(CHANNEL_CODE_ERROR, viewModel.state.value.uiError?.message)
    }
    @Test
    fun echoDoesNotRequireChannelInput() {
        val viewModel = ChannelViewModel(FakeStore(""), FakeConnection())
        viewModel.setServerAddress("ws://10.0.2.2:8000")

        viewModel.connect(echo = true)

        assertEquals("ECHO", viewModel.state.value.currentChannel)
    }

    @Test
    fun emptyStoreUsesCurrentDefaultServerAddress() {
        val viewModel = ChannelViewModel(FakeStore(""), FakeConnection())

        assertEquals(DEFAULT_SERVER_ADDRESS, viewModel.state.value.serverAddress)
    }

    @Test
    fun restoresAndSavesPowerSaveTimeout() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS, loadedTimeout = 7)
        val viewModel = ChannelViewModel(store, FakeConnection())

        assertEquals("7", viewModel.state.value.powerSaveTimeoutMinutes)
        viewModel.setPowerSaveTimeoutMinutes("15")

        assertEquals(15, store.savedTimeout)
    }

    @Test
    fun invalidPowerSaveTimeoutDoesNotStartConnection() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("room")
        viewModel.setPowerSaveTimeoutMinutes("0")

        viewModel.connect()

        assertNull(viewModel.state.value.currentChannel)
        assertNull(connection.channel)
        assertEquals(
            "Enter power save timeout from 1 to 1440 minutes",
            viewModel.state.value.uiError?.message,
        )
    }

    @Test
    fun pingReportsServerAvailabilityAndFailure() {
        val health = FakeHealth(Result.success(Unit))
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            healthClient = health,
        )

        viewModel.pingServer()
        assertEquals(DEFAULT_SERVER_ADDRESS, health.address)
        assertEquals("Server available", viewModel.state.value.serverCheckStatus)

        health.result = Result.failure(IOException("timeout"))
        viewModel.pingServer()
        assertEquals("Server unavailable: timeout", viewModel.state.value.serverCheckStatus)
    }

    @Test
    fun debugReportContainsConnectionContextWithoutClearChannelCode() {
        val viewModel = ChannelViewModel(FakeStore(DEFAULT_SERVER_ADDRESS), FakeConnection())
        viewModel.setChannelCode("SECRET42")
        viewModel.connect()
        viewModel.onConnectionDiagnostic("ConnectException: failed\ninternal detail")

        val report = viewModel.debugReport("0.1.0", "16 (SDK 36)")

        assertTrue(report.contains("server=$DEFAULT_SERVER_ADDRESS"))
        assertTrue(report.contains("channel=normal (redacted)"))
        assertTrue(report.contains("status=Connecting"))
        assertTrue(report.contains("connection=ConnectException: failed"))
        assertTrue(!report.contains("SECRET42"))
        assertTrue(!report.contains("internal detail"))
    }

    @Test
    fun reportsDiagnosticUploadProgressAndResult() {
        val viewModel = ChannelViewModel(FakeStore(DEFAULT_SERVER_ADDRESS), FakeConnection())

        val generation = viewModel.diagnosticUploadStarted()
        assertTrue(viewModel.state.value.diagnosticUploading)
        assertEquals("Sending diagnostics...", viewModel.state.value.diagnosticUploadStatus)

        viewModel.diagnosticUploadFinished(generation, Result.success("260715-K7M4"))
        assertFalse(viewModel.state.value.diagnosticUploading)
        assertEquals("Report sent", viewModel.state.value.diagnosticUploadStatus)
        assertEquals("260715-K7M4", viewModel.state.value.diagnosticReportCode)

        viewModel.diagnosticCodeCopied()
        assertEquals("Code copied", viewModel.state.value.diagnosticCopyStatus)

        val secondGeneration = viewModel.diagnosticUploadStarted()
        assertEquals(null, viewModel.state.value.diagnosticReportCode)
        assertEquals(null, viewModel.state.value.diagnosticCopyStatus)
        viewModel.diagnosticUploadFinished(generation, Result.failure(IllegalStateException("late")))
        assertTrue(viewModel.state.value.diagnosticUploading)
        assertEquals("Sending diagnostics...", viewModel.state.value.diagnosticUploadStatus)
        assertNull(viewModel.state.value.diagnosticReportCode)
        viewModel.diagnosticUploadFinished(secondGeneration, Result.success("260715-M8N5"))
        assertEquals("260715-M8N5", viewModel.state.value.diagnosticReportCode)
        viewModel.diagnosticUploadFinished(generation, Result.success("obsolete"))
        assertEquals("260715-M8N5", viewModel.state.value.diagnosticReportCode)
        viewModel.close()
    }

    @Test
    fun uplinkQualityUsesTheSameAgeThresholdsForRuntimeAndAckUpdates() {
        var now = 1_000L
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS), FakeConnection(), audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now }, nowMs = { now },
        )
        try {
            viewModel.setChannelCode("ROOM1")
            viewModel.connect()
            viewModel.onControl(testSnapshot("ROOM1", 1))
            assertTrue(viewModel.pttDown())
            viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
            assertTrue(audio.send(byteArrayOf(7)))
            for ((age, quality) in listOf(
                499L to AudioPathQuality.Good,
                500L to AudioPathQuality.Fair,
                999L to AudioPathQuality.Fair,
                1_000L to AudioPathQuality.Poor,
            )) {
                now = 1_000L + age
                viewModel.onControl(ControlEvent.ChannelState(2, 1, 1, FloorSnapshot(OLD_BURST, 0, true)))
                assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
                assertEquals(quality, viewModel.state.value.audioPathQuality)
                viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 0))
                assertEquals(quality, viewModel.state.value.audioPathQuality)
            }
            viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 1))
            assertEquals(AudioPathQuality.Good, viewModel.state.value.audioPathQuality)
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun applyingChannelReconnectsOnlyWhenFrequencyChanges() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS)
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(store, connection)
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()

        assertTrue(viewModel.applyChannelCode("room1"))
        assertEquals(1, connection.connectCount)
        assertEquals(0, connection.disconnectCount)

        assertTrue(viewModel.applyChannelCode("echo"))
        assertEquals("ECHO", store.savedChannel)
        assertEquals("ECHO", viewModel.state.value.currentChannel)
        assertEquals(2, connection.connectCount)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun bm008SettingPersistsAcrossDisconnectAndReconnect() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS)
        val viewModel = ChannelViewModel(store, FakeConnection())

        assertFalse(viewModel.state.value.headsetSettings.enabled)
        viewModel.setHeadsetSettings(HeadsetSettings(enabled = true))
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.disconnect()
        viewModel.connect()

        assertTrue(viewModel.state.value.headsetSettings.enabled)
        assertEquals(true, store.savedBm008)
    }

    @Test
    fun assignedPttSettingSurvivesChannelLifecycleAndResetCanBeUndone() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS)
        val viewModel = ChannelViewModel(store, FakeConnection())
        val saved = HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Toggle))
        viewModel.setHeadsetSettings(saved)
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.disconnect()
        assertEquals(saved, viewModel.state.value.headsetSettings)
        assertEquals(saved, store.savedPtt)
        viewModel.setHeadsetSettings(HeadsetSettings())
        assertEquals(HeadsetSettings(), store.savedPtt)
        viewModel.setHeadsetSettings(saved)
        assertEquals(saved, store.savedPtt)
        viewModel.close()
    }

    @Test
    fun failedHeadsetSaveKeepsThePreviousAcceptedSetup() {
        val store = object : ConnectionPreferences {
            override fun load() = DEFAULT_SERVER_ADDRESS
            override fun save(value: String) = Unit
            override fun saveHeadsetSettings(value: HeadsetSettings) = false
        }
        val viewModel = ChannelViewModel(store, FakeConnection())
        val previous = viewModel.state.value.headsetSettings
        assertFalse(viewModel.setHeadsetSettings(HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Toggle))))
        assertEquals(previous, viewModel.state.value.headsetSettings)
        assertEquals("Could not save the setup. Your previous setup is unchanged.", viewModel.state.value.uiError?.message)
        viewModel.close()
    }
    @Test
    fun applyingSettingsPersistsTogetherAndReconnectsOnlyForServerChange() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS)
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(store, connection)
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()

        assertTrue(viewModel.applySettings(DEFAULT_SERVER_ADDRESS, "20"))
        assertEquals(20, store.savedTimeout)
        assertEquals(1, connection.connectCount)

        assertTrue(viewModel.applySettings("wss://other.example", "20"))
        assertEquals("wss://other.example", store.saved)
        assertEquals("wss://other.example", connection.address)
        assertEquals(2, connection.connectCount)
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun invalidSettingsAreNotPersisted() {
        val store = FakeStore(DEFAULT_SERVER_ADDRESS)
        val viewModel = ChannelViewModel(store, FakeConnection())

        assertFalse(viewModel.applySettings("invalid", "10"))
        assertNull(store.saved)
        assertFalse(viewModel.applySettings(DEFAULT_SERVER_ADDRESS, "0"))
        assertNull(store.savedTimeout)
    }

    @Test
    fun pttOnCurrentReadyChannelRequestsImmediately() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        assertTrue(viewModel.pttDown())

        assertEquals(1, connection.requestCount)
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        viewModel.pttUp()
        viewModel.close()
    }

    @Test
    fun pttRequestTimeoutUsesTheAbsoluteTwoSecondDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        now = 1_999
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.releaseIds.isEmpty())

        now = 2_000
        viewModel.runSchedulerTickForTest()
        assertEquals(listOf("request-1"), connection.releaseIds)
        assertTrue(viewModel.state.value.pttError.orEmpty().contains("timed out"))
        viewModel.close()
    }

    @Test
    fun wakingPttRequestUsesTheAbsoluteFiveSecondDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(wokeOnPtt = true),
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        now = 4_999
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.releaseIds.isEmpty())

        now = 5_000
        viewModel.runSchedulerTickForTest()
        assertEquals(listOf("request-1"), connection.releaseIds)
        viewModel.close()
    }

    @Test
    fun matchingGrantAtTimeoutBoundaryCancelsTheDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        now = 2_000
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        viewModel.runSchedulerTickForTest()

        assertTrue(connection.releaseIds.isEmpty())
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun releaseAtTimeoutBoundaryCancelsTheDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        now = 2_000
        viewModel.pttUp()
        viewModel.runSchedulerTickForTest()

        assertEquals(listOf("request-1"), connection.releaseIds)
        assertNull(viewModel.state.value.pttError)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun oldRequestDeadlineCannotCancelTheNextRequest() {
        var now = 0L
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        now = 100
        viewModel.pttUp()
        viewModel.pttDown()
        now = 2_000
        viewModel.runSchedulerTickForTest()

        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        assertEquals(listOf("request-1"), connection.releaseIds)
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun oldTerminalAfterResumeDoesNotCancelFreshPttRequest() {
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        assertTrue(viewModel.pttDown())
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 1).copy(session = testSnapshot("ROOM1", 1).session.copy(generation = 2)))
        assertEquals(SessionStatus.Releasing, viewModel.state.value.status)
        assertEquals(listOf("request-1"), connection.requestIds)

        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.onControl(ControlEvent.PttGranted("request-2", NEW_BURST, 1, 2_000))
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun lostGrantReusesRequestIdWhenResumeSnapshotShowsOwnedFloor() {
        var request = 0
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        assertEquals(1, audio.startCount)
        assertEquals(0, audio.grantCount)

        viewModel.onReconnecting(1)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )

        assertEquals(listOf("request-1", "request-1"), connection.requestIds)
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        assertEquals(0, audio.grantCount)

        viewModel.onControl(ControlEvent.PttGranted("stale-request", NEW_BURST, 1, 2_000))
        assertEquals(0, audio.grantCount)
        assertEquals(listOf(NEW_BURST to 0L), connection.finishedBursts)

        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertEquals(1, audio.grantCount)
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun repeatedReconnectWhileAwaitingGrantReusesOriginalRequestId() {
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        viewModel.onReconnecting(1)
        viewModel.onReconnecting(2)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )

        assertEquals(listOf("request-1", "request-1"), connection.requestIds)
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun offlineReleaseSendsOriginalCancelAfterOwnedResumeSnapshot() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        viewModel.onReconnecting(1)
        viewModel.pttUp()
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )

        assertEquals(listOf("request-1"), connection.releaseIds)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun resumeWaitsForCaptureStopAndQueuesOldEndBeforeFreshRequest() {
        var request = 0
        val connection = FakeConnection()
        val audio = DeferredCaptureStopAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))

        assertEquals(listOf("request"), connection.pttEvents)
        assertEquals(SessionStatus.Releasing, viewModel.state.value.status)
        audio.completeStop()

        awaitCondition { connection.pttEvents == listOf("request", "finish") }
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))
        awaitCondition { connection.pttEvents == listOf("request", "finish", "request") }
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }

    @Test
    fun heldRecoveryRequestsFreshFloorAfterRemotePlaybackDrains() {
        var request = 0
        val connection = FakeConnection()
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        viewModel.onReconnecting(1)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                2,
                generation = 2,
                floor = FloorSnapshot(NEW_BURST, 1, owned = false),
            ),
        )
        awaitCondition { connection.finishedBursts.any { it.first == OLD_BURST } }
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))
        assertEquals(listOf("request-1"), connection.requestIds)

        viewModel.onControl(ControlEvent.BurstStarted(NEW_BURST, 1))
        viewModel.onAudio(downlink(NEW_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(NEW_BURST, 1, 1, "complete"))
        awaitCondition { audio.drainCallbacks.size == 1 }
        audio.completeDrain(0)

        awaitCondition { connection.requestIds == listOf("request-1", "request-2") }
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun rejectedOldEndKeepsFreshRequestBehindSchedulerRetry() {
        var request = 0
        val connection = FakeConnection().also { it.acceptFinish = false }
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))

        assertEquals(listOf("request-1"), connection.requestIds)
        assertEquals(SessionStatus.Releasing, viewModel.state.value.status)
        connection.acceptFinish = true

        awaitCondition { connection.pttEvents.count { it == "finish" } >= 2 }
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))
        awaitCondition { connection.requestIds == listOf("request-1", "request-2") }
        assertTrue(connection.pttEvents.lastIndexOf("finish") < connection.pttEvents.lastIndexOf("request"))
        viewModel.close()
    }

    @Test
    fun reconnectWhileFinishingWaitsForOldTerminalConfirmation() {
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))

        viewModel.onReconnecting(2)
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 3))

        assertEquals(listOf("request-1"), connection.requestIds)
        assertEquals(SessionStatus.Releasing, viewModel.state.value.status)
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }

    @Test
    fun manualSameTargetReconnectUsesCaptureStopBarrier() {
        var request = 0
        val connection = FakeConnection()
        val audio = DeferredCaptureStopAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()

        viewModel.onConnectionError()
        viewModel.reconnect()
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))
        assertEquals(listOf("request-1"), connection.requestIds)

        audio.completeStop()
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }

    @Test
    fun unknownGrantIsFinishedWithoutChangingReadyState() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        viewModel.onControl(ControlEvent.PttGranted("unknown", OLD_BURST, 0, 2_000))

        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        assertEquals(listOf(OLD_BURST to 0L), connection.finishedBursts)
        viewModel.close()
    }

    @Test
    fun uplinkAckWatchdogWaitsThreeSecondsAndReplaysAfterReconnect() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertTrue(audio.send(byteArrayOf(7)))
        awaitCondition { connection.audioMessages.size == 1 }

        now = 2_999
        Thread.sleep(100)
        assertEquals(1, connection.connectCount)

        now = 3_000
        awaitCondition { connection.connectCount == 2 }
        Thread.sleep(200)
        assertEquals(2, connection.connectCount)
        val report = viewModel.debugReport("test", "test")
        assertTrue(report.contains("Uplink ACK watchdog expired generation=1"))
        assertEquals(
            1,
            report.split("Uplink ACK watchdog expired generation=1").size - 1,
        )
        assertTrue(report.contains("age=3000ms timeout=3000ms"))
        assertTrue(report.contains("queue=0B rtt=none"))

        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )
        awaitCondition { connection.audioMessages.size == 2 }
        val envelopes = connection.audioMessages.map {
            AudioFrameCodec.decode(it, MediaDirection.Uplink)
        }
        assertEquals(listOf(0L, 0L), envelopes.map { it.firstSequence })
        assertEquals(listOf(byteArrayOf(7).toList(), byteArrayOf(7).toList()),
            envelopes.map { it.opusPackets.single().toList() })
        viewModel.close()
    }

    @Test
    fun freshAudioGroupsThreeFramesAndFlushesTheTailAtFortyMillisecondsOrStop() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS), connection, audio = audio,
            ptt = PttSession { "request-1" }, diagnostics = Diagnostics { now }, nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        assertTrue(audio.send(byteArrayOf(1)))
        now = 20
        assertTrue(audio.send(byteArrayOf(2)))
        assertTrue(connection.audioMessages.isEmpty())
        now = 39
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.audioMessages.isEmpty())
        now = 40
        viewModel.runSchedulerTickForTest()
        assertEquals(1, connection.audioMessages.size)
        assertEquals(2, AudioFrameCodec.decode(connection.audioMessages[0], MediaDirection.Uplink).opusPackets.size)

        now = 60
        assertTrue(audio.send(byteArrayOf(3)))
        now = 80
        assertTrue(audio.send(byteArrayOf(4)))
        now = 100
        assertTrue(audio.send(byteArrayOf(5)))
        assertEquals(2, connection.audioMessages.size)
        val group = AudioFrameCodec.decode(connection.audioMessages[1], MediaDirection.Uplink)
        assertEquals(2L, group.firstSequence)
        assertEquals(3, group.opusPackets.size)

        now = 120
        assertTrue(audio.send(byteArrayOf(6)))
        viewModel.pttUp()
        awaitCondition { connection.finishedBursts.contains(OLD_BURST to 6L) }
        assertEquals(3, connection.audioMessages.size)
        val tail = AudioFrameCodec.decode(connection.audioMessages[2], MediaDirection.Uplink)
        assertEquals(5L, tail.firstSequence)
        assertEquals(1, tail.opusPackets.size)
        viewModel.close()
    }

    @Test
    fun sealedShorterPrefixReportsInterruptedAfterTheOutgoingFramesWereCleared() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS), connection, audio = audio,
            ptt = PttSession { "request-1" }, diagnostics = Diagnostics { now }, nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        repeat(3) { assertTrue(audio.send(byteArrayOf(it.toByte()))) }
        viewModel.pttUp()
        awaitCondition { connection.finishedBursts.contains(OLD_BURST to 3L) }

        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "draining", "lease_expired", null))
        viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 2))
        now = 6_000
        viewModel.runSchedulerTickForTest()
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 2))

        assertEquals(1, audio.indicators.count { it == AudioIndicator.TransmissionInterrupted })
        viewModel.close()
    }

    @Test
    fun cumulativeAckBeforeBoundaryPreventsReconnect() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertTrue(audio.send(byteArrayOf(7)))
        assertTrue(audio.send(byteArrayOf(8)))

        now = 2_999
        viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 1))
        now = 5_998
        Thread.sleep(100)

        assertEquals(1, connection.connectCount)
        assertFalse(viewModel.debugReport("test", "test").contains("Uplink ACK watchdog expired"))
        viewModel.close()
    }

    @Test
    fun cumulativeAckRemovesOnlyAcceptedPrefixBeforeReconnectReplay() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        val packets = List(52) { sequence ->
            byteArrayOf(sequence.toByte(), (255 - sequence).toByte())
        }
        packets.forEachIndexed { sequence, packet ->
            now = sequence * 20L
            assertTrue(audio.send(packet))
        }
        assertEquals(17, connection.audioMessages.size)

        viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 50))
        viewModel.onReconnecting(1)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )
        awaitCondition { connection.audioMessages.size == 18 }

        val replay = AudioFrameCodec.decode(
            connection.audioMessages.last(),
            MediaDirection.Uplink,
        )
        assertEquals(OLD_BURST, replay.burstId)
        assertEquals(50L, replay.firstSequence)
        assertEquals(packets.drop(50).map { it.toList() }, replay.opusPackets.map { it.toList() })

        viewModel.onControl(ControlEvent.UplinkAck(OLD_BURST, 52))
        viewModel.onReconnecting(2)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 3,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )
        viewModel.pttUp()
        awaitCondition { connection.finishedBursts.contains(OLD_BURST to 52L) }
        assertEquals(18, connection.audioMessages.size)
        viewModel.close()
    }

    @Test
    fun offlineReleaseReplaysAudioBeforeBurstEnd() {
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertTrue(audio.send(byteArrayOf(7)))

        viewModel.onReconnecting(1)
        connection.acceptFinish = false
        viewModel.pttUp()
        connection.transportEvents.clear()
        connection.acceptFinish = true

        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))
        awaitCondition { connection.transportEvents.contains("finish") }

        assertEquals(listOf("audio", "finish"), connection.transportEvents.take(2))
        viewModel.close()
    }

    @Test
    fun nextBurstDuringPreviousDrainKeepsReceivingAndSuppressesOldChannelFree() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onAudio(downlink(OLD_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 1, "complete"))
        awaitCondition { audio.drainCallbacks.size == 1 }

        viewModel.onControl(ControlEvent.BurstStarted(NEW_BURST, 1))
        assertEquals(SessionStatus.Receiving, viewModel.state.value.status)
        assertEquals(2, audio.playbackStarts)
        audio.completeDrain(0)

        assertEquals(SessionStatus.Receiving, viewModel.state.value.status)
        assertFalse(audio.indicators.contains(AudioIndicator.ChannelFree))
        viewModel.close()
    }

    @Test
    fun playbackReceivesBurstIdentityForFramesAndLeadingLoss() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))

        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onAudio(downlink(OLD_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 1, "complete"))
        awaitCondition { audio.acceptedBurstIds.size == 1 }

        viewModel.onControl(ControlEvent.BurstStarted(NEW_BURST, 1))
        viewModel.onControl(
            ControlEvent.BurstGaps(NEW_BURST, 1, listOf(AudioSequenceRange(0, 1))),
        )
        viewModel.onAudio(downlink(NEW_BURST, 1, 2))
        viewModel.onControl(ControlEvent.BurstSealed(NEW_BURST, 1, 2, "complete"))
        awaitCondition { audio.acceptedBurstIds.size == 2 && audio.acceptedLosses.size == 1 }

        assertEquals(listOf(OLD_BURST, NEW_BURST), audio.acceptedBurstIds)
        assertEquals(listOf(NEW_BURST to 1), audio.acceptedLosses)
        viewModel.close()
    }

    @Test
    fun emptyBurstDoesNotCreateAPlaybackEventOrHideTheNextBurstIdentity() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))

        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 0, "complete"))
        viewModel.onControl(ControlEvent.BurstStarted(NEW_BURST, 1))
        viewModel.onAudio(downlink(NEW_BURST, 0, 9))
        viewModel.onControl(ControlEvent.BurstSealed(NEW_BURST, 1, 1, "complete"))
        awaitCondition { audio.acceptedBurstIds.size == 1 }

        assertEquals(listOf(NEW_BURST), audio.acceptedBurstIds)
        assertTrue(audio.acceptedLosses.isEmpty())
        viewModel.close()
    }

    @Test
    fun reconnectWithRetainedIncomingStateDoesNotRestartFakePlayback() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        assertEquals(1, audio.playbackStarts)

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 2, generation = 2))

        assertEquals(1, audio.playbackStarts)
        assertEquals(SessionStatus.Receiving, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun reconnectKeepsPlaybackActiveUntilAudioDrainCompletes() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onAudio(downlink(OLD_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 1, "complete"))
        awaitCondition { audio.drainCallbacks.size == 1 }

        viewModel.onReconnecting(1)

        assertEquals(SessionStatus.Reconnecting, viewModel.state.value.status)
        assertTrue(viewModel.state.value.playbackActive)
        assertEquals(HaloCenterIcon.Volume, haloVisualSpec(viewModel.state.value).centerIcon)

        audio.completeDrain(0)

        assertEquals(SessionStatus.Reconnecting, viewModel.state.value.status)
        assertFalse(viewModel.state.value.playbackActive)
        assertEquals(HaloCenterIcon.Microphone, haloVisualSpec(viewModel.state.value).centerIcon)
        viewModel.close()
    }

    @Test
    fun newIncarnationStopsOldPlaybackAndIgnoresItsDrainCallback() {
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onAudio(downlink(OLD_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 1, "complete"))
        awaitCondition { audio.drainCallbacks.size == 1 }

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 2, generation = 2, incarnation = "new-incarnation"))
        val stopsAfterReset = audio.stops
        audio.completeDrain(0)

        assertTrue(stopsAfterReset > 0)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        assertFalse(audio.indicators.contains(AudioIndicator.ChannelFree))
        viewModel.close()
    }

    @Test
    fun newIncarnationInterruptsHeldPttAndRequiresANewPhysicalPress() {
        var request = 0
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        viewModel.onReconnecting(1)
        viewModel.onControl(
            testSnapshot("ROOM1", 1, generation = 2, incarnation = "new-incarnation"),
        )

        assertEquals(1, audio.stopCaptureCount)
        assertFalse(audio.send(byteArrayOf(7)))
        assertEquals(listOf("request-1"), connection.requestIds)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        assertEquals(1, audio.indicators.count { it == AudioIndicator.TransmissionInterrupted })
        viewModel.pttUp()
        assertEquals(listOf("request-1"), connection.requestIds)
        viewModel.pttDown()
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }

    @Test
    fun resumeRejectedInterruptsHeldPttAndRequiresANewPhysicalPress() {
        var request = 0
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        viewModel.onReconnecting(1)
        viewModel.onControl(ControlEvent.ResumeRejected("expired"))
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 1))

        assertEquals(1, audio.stopCaptureCount)
        assertFalse(audio.send(byteArrayOf(7)))
        assertEquals(listOf("request-1"), connection.requestIds)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        assertEquals(1, audio.indicators.count { it == AudioIndicator.TransmissionInterrupted })
        viewModel.pttUp()
        assertEquals(listOf("request-1"), connection.requestIds)
        viewModel.pttDown()
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }

    @Test
    fun rejectedPlaybackEnqueueRetriesTheSameFrameUntilAccepted() {
        val audio = ControlledPlaybackAudio().apply { acceptPlayback = false }
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        repeat(5) { sequence -> viewModel.onAudio(downlink(OLD_BURST, sequence.toLong(), 7)) }
        awaitCondition { audio.playAttempts.size >= 2 }
        val attempts = synchronized(audio.playAttempts) { audio.playAttempts.toList() }
        assertTrue(attempts.all { it.contentEquals(byteArrayOf(7)) })

        audio.acceptPlayback = true
        awaitCondition { audio.acceptedPackets.size == 1 }

        assertArrayEquals(byteArrayOf(7), audio.acceptedPackets.single())
        viewModel.close()
    }

    @Test
    fun bufferedPlaybackIsNotPacedAtTheNetworkFrameRate() {
        val audio = CapacityPlaybackAudio(reportQueuedFrames = false)
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))

        viewModel.onAudio(downlinkPackets(OLD_BURST, 0, 20))
        awaitCondition { audio.acceptedPackets.size == 20 }

        val acceptedAt = synchronized(audio.acceptedAtNanos) { audio.acceptedAtNanos.toList() }
        assertTrue(
            "playback was paced at the 20 ms network frame interval",
            acceptedAt.last() - acceptedAt.first() < TimeUnit.MILLISECONDS.toNanos(250),
        )
        assertEquals((1..20).map { it.toByte() }, audio.acceptedPackets.map { it.single() })
        viewModel.close()
    }

    @Test
    fun bufferedPlaybackFillsAheadAndWaitsForCapacity() {
        val audio = CapacityPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))

        viewModel.onAudio(downlinkPackets(OLD_BURST, 0, 6))
        awaitCondition { audio.acceptedPackets.size == 5 }

        Thread.sleep(30)
        assertEquals(5, audio.acceptedPackets.size)
        assertEquals(5, audio.maxQueuedFrames.get())

        audio.consumeOne()
        awaitCondition { audio.acceptedPackets.size == 6 }
        assertEquals((1..6).map { it.toByte() }, audio.acceptedPackets.map { it.single() })
        viewModel.close()
    }

    @Test
    fun repeatedReceiveOverflowAtSameCursorStopsReconnectLoop() {
        val connection = FakeConnection()
        val audio = ControlledPlaybackAudio().apply { acceptPlayback = false }
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        repeat(60) { batch ->
            viewModel.onAudio(downlinkFrames(OLD_BURST, batch * 50L, 50, 7))
        }

        val overflow = downlinkFrames(OLD_BURST, 3_000, 1, 8)
        viewModel.onAudio(overflow)
        assertEquals(2, connection.connectCount)

        viewModel.onAudio(overflow)

        assertEquals(2, connection.connectCount)
        assertEquals(1, connection.disconnectCount)
        assertEquals(SessionStatus.ConnectionError, viewModel.state.value.status)
        assertTrue(viewModel.state.value.connectionDetail.orEmpty().contains("repeated"))
        viewModel.close()
    }

    @Test
    fun newIncarnationClearsReceiveOverflowStrike() {
        val connection = FakeConnection()
        val audio = ControlledPlaybackAudio().apply { acceptPlayback = false }
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 2))
        fillReceiveFifo(viewModel)
        viewModel.onAudio(downlinkFrames(OLD_BURST, 3_000, 1, 8))
        assertEquals(2, connection.connectCount)

        viewModel.onControl(
            testSnapshot("ROOM1", 2, generation = 2, incarnation = "new-incarnation"),
        )
        fillReceiveFifo(viewModel)
        viewModel.onAudio(downlinkFrames(OLD_BURST, 3_000, 1, 8))

        assertEquals(3, connection.connectCount)
        assertEquals(0, connection.disconnectCount)
        assertEquals(SessionStatus.Connecting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun payloadMismatchStopsAutomaticReplayUntilManualReconnect() {
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertTrue(audio.send(byteArrayOf(7)))
        assertTrue(audio.send(byteArrayOf(8)))
        assertTrue(audio.send(byteArrayOf(9)))

        viewModel.onControl(ControlEvent.AudioRejected(OLD_BURST, 0, 3, "payload_mismatch"))
        Thread.sleep(100)

        assertEquals(1, connection.connectCount)
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.audioMessages.size)
        assertEquals(SessionStatus.ConnectionError, viewModel.state.value.status)
        assertTrue(viewModel.state.value.uiError?.message.orEmpty().contains("protocol conflict"))

        viewModel.pttUp()
        assertEquals(SessionStatus.ConnectionError, viewModel.state.value.status)

        viewModel.reconnect()
        assertEquals(2, connection.connectCount)
        viewModel.close()
    }

    @Test
    fun serverConnectionChurnHasNoServiceIndicators() {
        var now = 0L
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        now = 100
        viewModel.onReconnecting(1)
        now = 2_100
        viewModel.runSchedulerTickForTest()
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))

        assertTrue(audio.indicators.isEmpty())
        viewModel.close()
    }

    @Test
    fun busyAndEchoPlaybackDoNotStartBackgroundServerSounds() {
        var now = 0L
        val busyAudio = RecordingCaptureAudio()
        val busy = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = busyAudio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        busy.setChannelCode("ROOM1")
        busy.connect()
        busy.onControl(testSnapshot("ROOM1", 1))
        busy.pttDown()
        busy.onControl(ControlEvent.PttDenied("request-1", "channel_busy"))
        now = 100
        busy.onReconnecting(1)
        busy.pttUp()
        busy.onReconnecting(2)
        now = 2_100
        busy.runSchedulerTickForTest()
        assertFalse(busyAudio.indicators.contains(AudioIndicator.TransmissionInterrupted))
        busy.close()

        now = 0
        val echoAudio = ControlledPlaybackAudio()
        val echo = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = echoAudio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        echo.connect(echo = true)
        echo.onControl(testSnapshot(ECHO_CHANNEL, 2))
        echo.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        echo.onAudio(downlink(OLD_BURST, 0, 1))
        assertEquals(SessionStatus.PlayingEcho, echo.state.value.status)
        now = 100
        echo.onReconnecting(1)
        echo.onReconnecting(2)
        now = 2_100
        echo.runSchedulerTickForTest()
        assertFalse(echoAudio.indicators.contains(AudioIndicator.TransmissionInterrupted))
        echo.close()
    }

    @Test
    fun repeatedReconnectAttemptsDoNotExtendOfflineRecoveryDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        now = 100
        viewModel.onReconnecting(1)
        now = 2_100
        viewModel.onReconnecting(2)
        now = 5_099
        viewModel.runSchedulerTickForTest()
        assertFalse(connection.finishedBursts.any { it.first == OLD_BURST })

        now = 5_100
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.finishedBursts.any { it.first == OLD_BURST })

        assertTrue(audio.indicators.contains(AudioIndicator.TransmissionInterrupted))
        assertEquals(SessionStatus.Reconnecting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun connectionErrorStartsOfflineRecoveryDeadlineBeforeReconnectCallback() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        now = 100
        viewModel.onConnectionError()
        now = 2_100
        viewModel.onReconnecting(1)
        now = 5_099
        viewModel.runSchedulerTickForTest()
        assertFalse(connection.finishedBursts.any { it.first == OLD_BURST })

        now = 5_100
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.finishedBursts.any { it.first == OLD_BURST })
        viewModel.close()
    }

    @Test
    fun manualSameTargetReconnectStartsOfflineRecoveryDeadline() {
        var now = 0L
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = RecordingCaptureAudio(),
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        now = 100
        viewModel.reconnect()
        now = 5_099
        viewModel.runSchedulerTickForTest()
        assertFalse(connection.finishedBursts.any { it.first == OLD_BURST })

        now = 5_100
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.finishedBursts.any { it.first == OLD_BURST })
        viewModel.close()
    }

    @Test
    fun stableCaptureStopsAtAbsoluteSixtySecondBurstLimit() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        now = 59_999
        viewModel.runSchedulerTickForTest()
        assertFalse(connection.finishedBursts.any { it.first == OLD_BURST })

        now = 60_000
        viewModel.runSchedulerTickForTest()
        assertTrue(connection.finishedBursts.any { it.first == OLD_BURST })

        assertTrue(audio.indicators.contains(AudioIndicator.TransmissionInterrupted))
        assertEquals(SessionStatus.Releasing, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun oldBurstDeadlineCannotStopTheNextBurst() {
        var now = 0L
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        viewModel.pttUp()
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))

        now = 100
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-2", NEW_BURST, 1, 2_000))
        now = 60_000
        viewModel.runSchedulerTickForTest()

        assertFalse(connection.finishedBursts.any { it.first == NEW_BURST })
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun clearedOfflineDeadlineCannotStopTheNextBurst() {
        var now = 0L
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))

        now = 100
        viewModel.onReconnecting(1)
        viewModel.onControl(
            testSnapshot(
                "ROOM1",
                1,
                generation = 2,
                floor = FloorSnapshot(OLD_BURST, 0, owned = true),
            ),
        )
        viewModel.pttUp()
        viewModel.onControl(ControlEvent.PttEnded(OLD_BURST, 0, "sealed", "complete", 0))

        now = 200
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttGranted("request-2", NEW_BURST, 1, 2_000))
        now = 5_100
        viewModel.runSchedulerTickForTest()

        assertFalse(connection.finishedBursts.any { it.first == NEW_BURST })
        assertEquals(SessionStatus.Transmitting, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun deferredPttExpiresExactlyAtFiveSeconds() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")

        assertTrue(viewModel.pttDown())
        assertTrue(viewModel.state.value.pendingPtt)
        assertEquals(PttNoticeKind.Queued, viewModel.state.value.pttNotice?.kind)
        assertEquals(1, audio.queuedCueStarts)

        now = 4_999
        viewModel.runSchedulerTickForTest()
        assertTrue(viewModel.state.value.pendingPtt)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))

        now = 5_000
        viewModel.runSchedulerTickForTest()
        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(PttNoticeKind.Rejected, viewModel.state.value.pttNotice?.kind)
        assertEquals(1, audio.indicators.count { it == AudioIndicator.PttRejected })
        assertTrue(audio.queuedCueStops >= 1)

        viewModel.onControl(testSnapshot("ROOM1", 1))
        assertEquals(0, connection.requestCount)
        viewModel.close()
    }

    @Test
    fun deferredPttGrantPathCancelsQueueDeadlineWithoutRejection() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        now = 800
        viewModel.onControl(testSnapshot("ROOM1", 1))
        assertEquals(1, connection.requestCount)
        assertNull(viewModel.state.value.pttNotice)

        viewModel.onControl(ControlEvent.PttGranted("request-1", OLD_BURST, 0, 2_000))
        assertNull(viewModel.state.value.pttNotice)
        now = 5_000
        viewModel.runSchedulerTickForTest()
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        viewModel.pttUp()
        viewModel.close()
    }

    @Test
    fun releasingDeferredPttCancelsQueueWithoutRejection() {
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        viewModel.pttUp()

        assertFalse(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttNotice)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun disconnectCancelsDeferredPttWithoutLateRequestOrRejection() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        viewModel.disconnect()
        now = 6_000
        viewModel.runSchedulerTickForTest()

        assertFalse(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttNotice)
        assertEquals(0, connection.requestCount)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun channelChangeCancelsDeferredPttWithoutLateRequestOrRejection() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        assertTrue(viewModel.applyChannelCode("ROOM2"))
        now = 6_000
        viewModel.runSchedulerTickForTest()
        viewModel.onControl(testSnapshot("ROOM2", 1, incarnation = "room2-incarnation"))

        assertFalse(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttNotice)
        assertEquals(0, connection.requestCount)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun serverChangeCancelsDeferredPttWithoutLateRequestOrRejection() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        assertTrue(viewModel.applySettings("wss://other.example", "10"))
        now = 6_000
        viewModel.runSchedulerTickForTest()
        viewModel.onControl(testSnapshot("ROOM1", 1, incarnation = "new-server"))

        assertFalse(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttNotice)
        assertEquals(0, connection.requestCount)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun newServerIncarnationCancelsDeferredPttWithoutLateRequestOrRejection() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1, incarnation = "old-server"))
        viewModel.onReconnecting(1)
        viewModel.pttDown()

        viewModel.onControl(
            testSnapshot("ROOM1", 1, generation = 2, incarnation = "new-server"),
        )
        now = 6_000
        viewModel.runSchedulerTickForTest()

        assertFalse(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttNotice)
        assertEquals(0, connection.requestCount)
        assertFalse(audio.indicators.contains(AudioIndicator.PttRejected))
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun finalConnectionErrorRejectsDeferredPttExactlyOnce() {
        var now = 0L
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { now },
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.pttDown()

        viewModel.onConnectionError()
        now = 6_000
        viewModel.runSchedulerTickForTest()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(PttNoticeKind.Rejected, viewModel.state.value.pttNotice?.kind)
        assertTrue(viewModel.state.value.pttNotice?.message.orEmpty().contains("press PTT again"))
        assertEquals(1, audio.indicators.count { it == AudioIndicator.PttRejected })
        assertEquals(0, connection.requestCount)
        assertTrue(audio.queuedCueStops >= 1)
        viewModel.close()
    }

    @Test
    fun stalePttNoticeConsumptionCannotClearNewerNotice() {
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            diagnostics = Diagnostics { 0 },
        )
        viewModel.playPttRejected("First rejection")
        val staleId = requireNotNull(viewModel.state.value.pttNotice).id
        viewModel.playPttRejected("Second rejection")

        viewModel.consumePttNotice(staleId)

        assertEquals("Second rejection", viewModel.state.value.pttNotice?.message)
        viewModel.close()
    }

    @Test
    fun pttInterruptsOrdinaryDrainingTailAndSuppressesOldDrainCallback() {
        val connection = FakeConnection()
        val audio = ControlledPlaybackAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        viewModel.onAudio(downlink(OLD_BURST, 0, 1))
        viewModel.onControl(ControlEvent.BurstSealed(OLD_BURST, 0, 1, "complete"))
        awaitCondition { audio.drainCallbacks.size == 1 }
        assertTrue(viewModel.state.value.playbackInterruptible)

        assertTrue(viewModel.pttDown())

        assertEquals(1, audio.interrupts)
        assertEquals(1, connection.requestCount)
        assertEquals(SessionStatus.Requesting, viewModel.state.value.status)
        audio.completeDrain(0)
        assertFalse(audio.indicators.contains(AudioIndicator.ChannelFree))
        viewModel.pttUp()
        viewModel.close()
    }

    @Test
    fun pttInterruptsEchoDrainingTailButRejectsActiveEchoBurst() {
        val activeConnection = FakeConnection()
        val activeAudio = ControlledPlaybackAudio()
        val active = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            activeConnection,
            audio = activeAudio,
            diagnostics = Diagnostics { 0 },
        )
        active.connect(echo = true)
        active.onControl(testSnapshot(ECHO_CHANNEL, 2))
        active.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
        active.onAudio(downlink(OLD_BURST, 0, 1))

        assertFalse(active.pttDown())
        assertEquals(0, activeAudio.interrupts)
        assertEquals(0, activeConnection.requestCount)
        assertEquals(PttNoticeKind.Rejected, active.state.value.pttNotice?.kind)
        active.pttUp()
        active.close()

        val drainingConnection = FakeConnection()
        val drainingAudio = ControlledPlaybackAudio()
        val draining = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            drainingConnection,
            audio = drainingAudio,
            ptt = PttSession { "request-1" },
            diagnostics = Diagnostics { 0 },
        )
        draining.connect(echo = true)
        draining.onControl(testSnapshot(ECHO_CHANNEL, 2))
        draining.onControl(ControlEvent.BurstStarted(NEW_BURST, 0))
        draining.onAudio(downlink(NEW_BURST, 0, 2))
        draining.onControl(ControlEvent.BurstSealed(NEW_BURST, 0, 1, "complete"))
        awaitCondition { drainingAudio.drainCallbacks.size == 1 }

        assertTrue(draining.pttDown())
        assertEquals(1, drainingAudio.interrupts)
        assertEquals(1, drainingConnection.requestCount)
        drainingAudio.completeDrain(0)
        assertFalse(drainingAudio.indicators.contains(AudioIndicator.ChannelFree))
        draining.pttUp()
        draining.close()
    }

    @Test
    fun pttSwitchesChannelAndWaitsForJoin() {
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.setChannelCode("room2")

        assertTrue(viewModel.pttDown())

        assertEquals("ROOM2", viewModel.state.value.channelCode)
        assertEquals("ROOM2", viewModel.state.value.currentChannel)
        assertTrue(viewModel.state.value.pendingPtt)
        assertEquals(0, connection.requestCount)
        assertEquals(2, connection.connectCount)
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, audio.indicators.count { it == AudioIndicator.PttQueued })

        viewModel.onControl(testSnapshot("ROOM2", 1, incarnation = "room2-incarnation"))
        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(1, connection.requestCount)
        viewModel.pttUp()
        viewModel.close()
    }

    @Test
    fun releaseBeforeJoinCancelsPendingPtt() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")

        assertTrue(viewModel.pttDown())
        assertTrue(viewModel.state.value.pendingPtt)
        viewModel.pttUp()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(0, connection.requestCount)
        viewModel.close()
    }

    @Test
    fun joinedBeforeReleaseRequestsThenImmediatelyReleasesPtt() {
        val requestEntered = CountDownLatch(1)
        val allowRequest = CountDownLatch(1)
        val connection = FakeConnection().apply {
            this.requestEntered = requestEntered
            this.allowRequest = allowRequest
        }
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        assertTrue(viewModel.pttDown())

        val joined = thread { viewModel.onControl(testSnapshot("ROOM1", 1)) }
        assertTrue(requestEntered.await(2, TimeUnit.SECONDS))
        val releaseStarted = CountDownLatch(1)
        val released = thread {
            releaseStarted.countDown()
            viewModel.pttUp()
        }
        assertTrue(releaseStarted.await(2, TimeUnit.SECONDS))
        assertEquals(0, connection.releaseCount)

        allowRequest.countDown()
        joined.join(2_000)
        released.join(2_000)

        assertFalse(joined.isAlive)
        assertFalse(released.isAlive)
        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(listOf("request", "release"), connection.pttEvents)
        assertFalse(viewModel.state.value.status == SessionStatus.Transmitting)
        viewModel.close()
    }

    @Test
    fun releaseBeforeJoinedFinishesCancelsPendingRequest() {
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val audio = BlockingReleaseAudio(releaseEntered, allowRelease)
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        assertTrue(viewModel.pttDown())

        val released = thread { viewModel.pttUp() }
        assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
        val joinedStarted = CountDownLatch(1)
        val joined = thread {
            joinedStarted.countDown()
            viewModel.onControl(testSnapshot("ROOM1", 1))
        }
        assertTrue(joinedStarted.await(2, TimeUnit.SECONDS))
        assertEquals(0, connection.requestCount)

        allowRelease.countDown()
        released.join(2_000)
        joined.join(2_000)

        assertFalse(released.isAlive)
        assertFalse(joined.isAlive)
        assertFalse(viewModel.state.value.pendingPtt)
        assertEquals(0, connection.requestCount)
        viewModel.close()
    }
    @Test
    fun invalidFrequencyIsRejectedAndReconnectPreservesPttHold() {
        val connection = FakeConnection()
        val audio = RecordingCaptureAudio()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            audio = audio,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("")

        assertFalse(viewModel.pttDown())
        assertEquals(UiErrorTarget.Frequency, viewModel.state.value.uiError?.target)
        assertEquals(listOf(AudioIndicator.PttRejected), audio.indicators)

        viewModel.setChannelCode("ROOM1")
        assertTrue(viewModel.pttDown())
        viewModel.onReconnecting(1)

        assertTrue(viewModel.state.value.pendingPtt)
        assertNull(viewModel.state.value.pttError)
        assertEquals(0, connection.requestCount)
        viewModel.close()
    }
    @Test
    fun deniedPttPublishesPersistentPttNotice() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        assertTrue(viewModel.pttDown())
        val requestId = connection.requestIds.single()
        viewModel.onControl(ControlEvent.PttDenied(requestId, "channel_busy"))

        assertEquals(SessionStatus.Busy, viewModel.state.value.status)
        assertEquals("Channel busy", viewModel.state.value.pttError)
        assertNull(viewModel.state.value.uiError)
        assertEquals(PttNoticeKind.Rejected, viewModel.state.value.pttNotice?.kind)
        assertTrue(viewModel.state.value.pttNotice?.message.orEmpty().contains("press PTT again"))
        assertEquals(HaloCenterIcon.MicrophoneOff, haloVisualSpec(viewModel.state.value).centerIcon)
        viewModel.pttUp()
        assertNull(viewModel.state.value.pttError)
        assertEquals(HaloCenterIcon.Microphone, haloVisualSpec(viewModel.state.value).centerIcon)
        viewModel.close()
    }

    @Test
    fun busyHoldDoesNotRequestFloorAfterReconnect() {
        var request = 0
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            connection,
            ptt = PttSession { "request-${++request}" },
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.pttDown()
        viewModel.onControl(ControlEvent.PttDenied("request-1", "channel_busy"))

        viewModel.onReconnecting(1)
        viewModel.onControl(testSnapshot("ROOM1", 1, generation = 2))

        assertEquals(listOf("request-1"), connection.requestIds)
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        viewModel.pttUp()
        viewModel.pttDown()
        assertEquals(listOf("request-1", "request-2"), connection.requestIds)
        viewModel.close()
    }
    @Test
    fun participantCountUpdatesAndResetsAcrossConnectionChanges() {
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS),
            FakeConnection(),
            diagnostics = Diagnostics { 0 },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        viewModel.onControl(testSnapshot("ROOM1", 1))

        assertEquals(1, viewModel.state.value.participantCount)
        viewModel.onControl(ControlEvent.ChannelState(1, 3, 0, null))
        assertEquals(3, viewModel.state.value.participantCount)

        viewModel.onConnectionDisconnected("closed")
        assertNull(viewModel.state.value.participantCount)
        viewModel.onControl(ControlEvent.ChannelState(2, 2, 0, null))
        viewModel.onReconnecting(1)
        assertNull(viewModel.state.value.participantCount)

        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.onControl(ControlEvent.ChannelState(1, 2, 0, null))
        assertTrue(viewModel.applySettings("wss://other.example", "10"))
        assertNull(viewModel.state.value.participantCount)

        viewModel.onControl(testSnapshot("ROOM1", 1))
        viewModel.onControl(ControlEvent.ChannelState(1, 2, 0, null))
        assertTrue(viewModel.applyChannelCode(ECHO_CHANNEL))
        assertNull(viewModel.state.value.participantCount)
        viewModel.onControl(testSnapshot(ECHO_CHANNEL, 1))
        assertEquals(1, viewModel.state.value.participantCount)
        viewModel.close()
    }

    @Test
    fun participantCountTextOnlyDescribesAppliedConnectedFrequency() {
        val connected = ChannelUiState(
            channelCode = "ROOM1",
            currentChannel = "ROOM1",
            participantCount = 4,
            status = SessionStatus.Ready,
        )

        assertEquals("4", participantCountText(connected, "ROOM1"))
        assertEquals("—", participantCountText(connected, "ROOM2"))
        assertEquals(
            "—",
            participantCountText(connected.copy(status = SessionStatus.Reconnecting), "ROOM1"),
        )
        assertEquals("—", participantCountText(connected.copy(currentChannel = null), "ROOM1"))
        assertEquals(
            "4",
            participantCountText(
                connected.copy(channelCode = ECHO_CHANNEL, currentChannel = ECHO_CHANNEL),
                ECHO_CHANNEL,
            ),
        )
    }

    @Test
    fun echoBlocksPttUntilBotIsPresent() {
        val connection = FakeConnection()
        val viewModel = ChannelViewModel(
            FakeStore(DEFAULT_SERVER_ADDRESS, loadedChannel = ECHO_CHANNEL),
            connection,
            diagnostics = Diagnostics { 0 },
        )
        viewModel.connect()
        viewModel.onControl(testSnapshot(ECHO_CHANNEL, 2))

        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        viewModel.onControl(ControlEvent.ChannelState(1, 1, 0, null))

        assertEquals(SessionStatus.Connecting, viewModel.state.value.status)
        assertFalse(viewModel.pttDown())
        assertEquals(0, connection.requestCount)

        viewModel.onControl(ControlEvent.ChannelState(2, 2, 0, null))
        assertEquals(SessionStatus.Ready, viewModel.state.value.status)
        viewModel.close()
    }

    @Test
    fun buildsSeparateUserIndicators() {
        assertEquals(
            "Connected",
            networkIndicator(SessionStatus.Ready),
        )
        assertEquals(
            "Offline",
            networkIndicator(SessionStatus.ConnectionError),
        )
        assertEquals(
            "Connected",
            headsetIndicator("BM008: connected"),
        )
        assertEquals(
            "Unavailable",
            AudioRouteStatus.Unavailable.label,
        )
    }
    private class FakeStore(
        private val loaded: String,
        private val loadedTimeout: Int = DEFAULT_POWER_SAVE_TIMEOUT_MINUTES,
        private val loadedChannel: String? = null,
    ) : ConnectionPreferences {
        var saved: String? = null
        var savedChannel: String? = null
        var savedTimeout: Int? = null
        var savedBm008: Boolean? = null
        var savedPtt: HeadsetSettings? = null
        override fun load() = loaded
        override fun save(value: String) { saved = value }
        override fun loadLastChannel() = loadedChannel
        override fun saveLastChannel(value: String) { savedChannel = value }
        override fun loadPowerSaveTimeoutMinutes() = loadedTimeout
        override fun savePowerSaveTimeoutMinutes(value: Int) { savedTimeout = value }
        override fun saveHeadsetSettings(value: HeadsetSettings): Boolean {
            savedPtt = value
            savedBm008 = value.enabled && value.setup == HeadsetSetup.factory()
            return true
        }
    }

    private class FakeHealth(var result: Result<Unit>) : ServerHealthClient {
        var address: String? = null
        override fun check(address: String, callback: (Result<Unit>) -> Unit) {
            this.address = address
            callback(result)
        }
    }

    private class BlockingReleaseAudio(
        private val releaseEntered: CountDownLatch,
        private val allowRelease: CountDownLatch,
    ) : AudioGate {
        override fun start(sender: (ByteArray) -> Boolean) = Unit
        override fun stop(onStopped: () -> Unit) = onStopped()
        override fun play(burstId: String, message: ByteArray) = true
        override fun onPttReleased() {
            releaseEntered.countDown()
            assertTrue(allowRelease.await(2, TimeUnit.SECONDS))
        }
    }

    private class DeferredCaptureStopAudio : AudioGate {
        private var stopCallback: (() -> Unit)? = null
        override fun start(sender: (ByteArray) -> Boolean) = Unit
        override fun stopCapture(onStopped: () -> Unit) {
            stopCallback = onStopped
        }
        override fun stop(onStopped: () -> Unit) = onStopped()
        override fun play(burstId: String, message: ByteArray) = true
        fun completeStop() = requireNotNull(stopCallback).invoke()
    }

    private class RecordingCaptureAudio(
        private val wokeOnPtt: Boolean = false,
    ) : AudioGate {
        var startCount = 0
        var grantCount = 0
        var stopCaptureCount = 0
        var queuedCueStarts = 0
        var queuedCueStops = 0
        val indicators = Collections.synchronizedList(mutableListOf<AudioIndicator>())
        private var sender: ((ByteArray) -> Boolean)? = null
        override fun start(sender: (ByteArray) -> Boolean) {
            startCount += 1
            this.sender = sender
        }
        override fun grantCapture() {
            grantCount += 1
        }
        override fun stopCapture(onStopped: () -> Unit) {
            stopCaptureCount += 1
            onStopped()
        }
        override fun stop(onStopped: () -> Unit) = onStopped()
        override fun play(burstId: String, message: ByteArray) = true
        override fun onPttActivity(): Boolean = wokeOnPtt
        override fun startQueuedCue() {
            queuedCueStarts += 1
            playIndicator(AudioIndicator.PttQueued)
        }
        override fun stopQueuedCue() {
            queuedCueStops += 1
        }
        override fun playIndicator(indicator: AudioIndicator, onComplete: () -> Unit) {
            indicators += indicator
            onComplete()
        }
        fun send(packet: ByteArray): Boolean = requireNotNull(sender).invoke(packet)
    }

    private class ControlledPlaybackAudio : AudioGate {
        @Volatile var acceptPlayback = true
        var playbackStarts = 0
        var stops = 0
        var interrupts = 0
        val playAttempts = Collections.synchronizedList(mutableListOf<ByteArray>())
        val acceptedPackets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val acceptedBurstIds = Collections.synchronizedList(mutableListOf<String>())
        val acceptedLosses = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val drainCallbacks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val indicators = Collections.synchronizedList(mutableListOf<AudioIndicator>())

        override fun start(sender: (ByteArray) -> Boolean) = Unit
        override fun stop(onStopped: () -> Unit) {
            stops += 1
            onStopped()
        }
        override fun play(burstId: String, message: ByteArray): Boolean {
            playAttempts += message.copyOf()
            if (acceptPlayback) {
                acceptedBurstIds += burstId
                acceptedPackets += message.copyOf()
            }
            return acceptPlayback
        }
        override fun playLoss(burstId: String, frameCount: Int): Boolean {
            if (acceptPlayback) acceptedLosses += burstId to frameCount
            return acceptPlayback
        }
        override fun incomingTransmissionStarted() {
            playbackStarts += 1
        }
        override fun incomingTransmissionEnded(onPlaybackDrained: () -> Unit) {
            drainCallbacks += onPlaybackDrained
        }
        override fun interruptIncomingPlayback() {
            interrupts += 1
        }
        override fun playIndicator(indicator: AudioIndicator, onComplete: () -> Unit) {
            indicators += indicator
            onComplete()
        }

        fun completeDrain(index: Int) = drainCallbacks[index].invoke()
    }

    private class CapacityPlaybackAudio(
        private val reportQueuedFrames: Boolean = true,
    ) : AudioGate {
        val acceptedPackets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val acceptedAtNanos = Collections.synchronizedList(mutableListOf<Long>())
        val maxQueuedFrames = AtomicInteger()
        private val queuedFrames = AtomicInteger()

        override fun start(sender: (ByteArray) -> Boolean) = Unit
        override fun stop(onStopped: () -> Unit) = onStopped()
        override fun play(burstId: String, message: ByteArray): Boolean {
            val queued = queuedFrames.incrementAndGet()
            maxQueuedFrames.accumulateAndGet(queued) { current, candidate ->
                maxOf(current, candidate)
            }
            acceptedPackets += message.copyOf()
            acceptedAtNanos += System.nanoTime()
            return true
        }
        override fun playbackQueuedFrames(): Int = if (reportQueuedFrames) queuedFrames.get() else 0
        override fun playbackBacklogEmpty(): Boolean = queuedFrames.get() == 0

        fun consumeOne() {
            check(queuedFrames.decrementAndGet() >= 0)
        }
    }

    private class FakeConnection : ConnectionClient {
        var address: String? = null
        var channel: String? = null
        var connectCount = 0
        var disconnectCount = 0
        var requestCount = 0
        var releaseCount = 0
        @Volatile var acceptFinish = true
        var requestEntered: CountDownLatch? = null
        var allowRequest: CountDownLatch? = null
        val pttEvents = Collections.synchronizedList(mutableListOf<String>())
        val requestIds = mutableListOf<String>()
        val releaseIds = mutableListOf<String>()
        val finishedBursts = mutableListOf<Pair<String, Long>>()
        val audioMessages = Collections.synchronizedList(mutableListOf<ByteArray>())
        val transportEvents = Collections.synchronizedList(mutableListOf<String>())
        override fun connect(address: String, channel: String, listener: ConnectionListener) {
            this.address = address
            this.channel = channel
            connectCount++
        }
        override fun requestPtt(requestId: String): Boolean {
            requestCount++
            requestIds += requestId
            pttEvents += "request"
            requestEntered?.countDown()
            allowRequest?.let { assertTrue(it.await(2, TimeUnit.SECONDS)) }
            return true
        }
        override fun releasePtt(requestId: String): Boolean {
            releaseCount++
            releaseIds += requestId
            pttEvents += "release"
            return true
        }
        override fun finishBurst(burstId: String, finalNextSequence: Long): Boolean {
            finishedBursts += burstId to finalNextSequence
            pttEvents += "finish"
            transportEvents += "finish"
            return acceptFinish
        }
        override fun sendAudio(message: ByteArray): Boolean {
            audioMessages += message.copyOf()
            transportEvents += "audio"
            return true
        }
        override fun disconnect() { disconnectCount++ }
    }
}

private const val OLD_BURST = "11111111-1111-4111-8111-111111111111"
private const val NEW_BURST = "22222222-2222-4222-8222-222222222222"

private fun testSnapshot(
    channel: String,
    participants: Int,
    generation: Int = 1,
    incarnation: String = "incarnation",
    floor: FloorSnapshot? = null,
) = ControlEvent.Snapshot(
    SessionSnapshot(
        channel, "member", "token", generation, incarnation, 0, participants,
        0, 0, AudioPolicy(5_000), floor,
    ),
)

private fun downlink(burstId: String, sequence: Long, value: Byte): ByteArray = AudioFrameCodec.encode(
    NetworkAudioEnvelope(MediaDirection.Downlink, burstId, sequence, listOf(byteArrayOf(value))),
)

private fun downlinkFrames(
    burstId: String,
    sequence: Long,
    count: Int,
    value: Byte,
): ByteArray = AudioFrameCodec.encode(
    NetworkAudioEnvelope(
        MediaDirection.Downlink,
        burstId,
        sequence,
        List(count) { byteArrayOf(value) },
    ),
)

private fun downlinkPackets(burstId: String, sequence: Long, count: Int): ByteArray =
    AudioFrameCodec.encode(
        NetworkAudioEnvelope(
            MediaDirection.Downlink,
            burstId,
            sequence,
            List(count) { byteArrayOf((it + 1).toByte()) },
        ),
    )

private fun fillReceiveFifo(viewModel: ChannelViewModel) {
    viewModel.onControl(ControlEvent.BurstStarted(OLD_BURST, 0))
    repeat(60) { batch ->
        viewModel.onAudio(downlinkFrames(OLD_BURST, batch * 50L, 50, 7))
    }
}

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    assertTrue(condition())
}
