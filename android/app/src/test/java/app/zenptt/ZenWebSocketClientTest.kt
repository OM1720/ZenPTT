package app.zenptt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ZenWebSocketClientTest {
    @Test
    fun controlSendReportsMissingSocket() {
        val client = ZenWebSocketClient()

        assertFalse(client.requestPtt("one"))
        assertFalse(client.releasePtt("one"))
        client.disconnect()
    }

    @Test
    fun staleSocketCannotSendJoinOrDeliverEvents() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)

        client.connect("ws://first.example:8000", "ONE", listener)
        val first = factory.opens.single()
        client.connect("ws://second.example:8000", "TWO", listener)
        val second = factory.opens.last()

        first.listener.onOpen(first.socket, response(first.request))
        first.listener.onMessage(first.socket, "{\"type\":\"transmission_started\"}")
        first.listener.onMessage(first.socket, "{\"type\":\"participant_count\",\"count\":9}")
        first.listener.onFailure(first.socket, IllegalStateException("late"), null)
        second.listener.onOpen(second.socket, response(second.request))

        assertTrue(first.socket.sentText.isEmpty())
        assertTrue(listener.controls.isEmpty())
        assertEquals(listOf(ControlProtocol.join("TWO")), second.socket.sentText)
        assertEquals(null, second.request.url.query)
        assertEquals(2, factory.opens.size)
        client.disconnect()
    }

    @Test
    fun synchronousFactoryFailureCannotLeaveDeadSocketCurrent() {
        var failFirst = true
        val factory = FakeWebSocketFactory { opened ->
            if (failFirst) {
                failFirst = false
                opened.listener.onFailure(
                    opened.socket,
                    IllegalStateException("offline"),
                    null,
                )
            }
        }
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            wait = {},
            reconnectJitterMs = { 0 },
            webSocketFactory = factory::open,
        )

        client.connect("ws://server.example:8000", "ROOM", listener)

        awaitCondition { factory.opens.size == 2 }
        val failed = factory.opens.first()
        assertEquals(1, failed.socket.cancelCount)
        assertEquals(listOf("IllegalStateException: offline"), listener.disconnections)
        assertEquals(listOf(1), listener.reconnecting)

        failed.listener.onClosed(failed.socket, 1006, "duplicate")
        failed.listener.onFailure(failed.socket, IllegalStateException("duplicate"), null)
        assertEquals(2, factory.opens.size)
        assertEquals(listOf(1), listener.reconnecting)
        client.disconnect()
    }

    @Test
    fun synchronousFactoryOpenActivatesSocketBeforeFactoryReturns() {
        val factory = FakeWebSocketFactory { opened ->
            opened.listener.onOpen(opened.socket, response(opened.request))
        }
        val client = ZenWebSocketClient(
            wait = { if (it == ZenWebSocketClient.READINESS_TIMEOUT_MS) CompletableDeferred<Unit>().await() },
            webSocketFactory = factory::open,
        )

        client.connect("ws://server.example:8000", "ROOM", RecordingListener())

        val opened = factory.opens.single()
        assertEquals(listOf(ControlProtocol.join("ROOM")), opened.socket.sentText)
        assertEquals(0, opened.socket.cancelCount)
        client.disconnect()
    }

    @Test
    fun echoUsesDedicatedJoinCommand() {
        val factory = FakeWebSocketFactory { opened ->
            opened.listener.onOpen(opened.socket, response(opened.request))
        }
        val client = ZenWebSocketClient(
            wait = { if (it == ZenWebSocketClient.READINESS_TIMEOUT_MS) CompletableDeferred<Unit>().await() },
            webSocketFactory = factory::open,
        )

        client.connect("ws://server.example:8000", ECHO_CHANNEL, RecordingListener())

        assertEquals(listOf(ControlProtocol.joinEcho()), factory.opens.single().socket.sentText)
        client.disconnect()
    }

    @Test
    fun pingStartsOnlyAfterJoinedAndExplicitDisconnectStopsLifecycle() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val waits = ControlledWaits()
        val client = ZenWebSocketClient(
            wait = waits::suspendFor,
            elapsedRealtimeMs = { 100L },
            webSocketFactory = factory::open,
        )

        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        assertEquals(ZenWebSocketClient.READINESS_TIMEOUT_MS, waits.next().delayMs)
        assertEquals(null, waits.poll())

        opened.listener.onMessage(
            opened.socket,
            joinedMessage(),
        )
        val firstPing = waits.next()
        assertEquals(ZenWebSocketClient.PING_INTERVAL_MS, firstPing.delayMs)
        firstPing.resumeAndAwait()
        awaitCondition { opened.socket.sentText.count { it.contains("\"type\":\"ping\"") } == 1 }
        val secondPing = waits.next()

        client.disconnect()
        assertEquals(listOf(1000), opened.socket.closeCodes)
        val gracefulClose = waits.next()
        assertEquals(ZenWebSocketClient.GRACEFUL_DISCONNECT_TIMEOUT_MS, gracefulClose.delayMs)
        secondPing.awaitCancelled()

        assertEquals(
            1,
            opened.socket.sentText.count { it.contains("\"type\":\"ping\"") },
        )
        gracefulClose.resumeAndAwait()
        awaitCondition { opened.socket.cancelCount == 1 }
        assertEquals(1, factory.opens.size)
    }

    @Test
    fun pongBeforeWatchdogThresholdResetsPendingPings() {
        val factory = FakeWebSocketFactory()
        val waits = ControlledWaits()
        val client = ZenWebSocketClient(
            wait = waits::suspendFor,
            reconnectJitterMs = { 0 },
            elapsedRealtimeMs = { 100L },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        val readiness = waits.next()
        opened.listener.onMessage(opened.socket, joinedMessage())
        readiness.awaitCancelled()

        var nextPing = waits.next()
        repeat(ZenWebSocketClient.MAX_MISSED_PONGS - 1) { index ->
            assertEquals(ZenWebSocketClient.PING_INTERVAL_MS, nextPing.delayMs)
            nextPing.resumeAndAwait()
            awaitCondition {
                opened.socket.sentText.count { it.contains("\"type\":\"ping\"") } == index + 1
            }
            assertEquals(index + 1, client.pendingPingCount())
            assertEquals(0, opened.socket.cancelCount)
            nextPing = waits.next()
        }

        opened.listener.onMessage(
            opened.socket,
            """{"type":"pong","id":${ZenWebSocketClient.MAX_MISSED_PONGS - 1},"sent_at_ms":100}""",
        )
        assertEquals(0, client.pendingPingCount())
        nextPing.resumeAndAwait()
        awaitCondition {
            opened.socket.sentText.count { it.contains("\"type\":\"ping\"") } ==
                ZenWebSocketClient.MAX_MISSED_PONGS && client.pendingPingCount() == 1
        }
        val pendingPing = waits.next()
        assertEquals(0, opened.socket.cancelCount)

        client.disconnect()
        pendingPing.awaitCancelled()
        waits.next().resumeAndAwait()
    }

    @Test
    fun validInboundTrafficResetsPendingPingsButStaleTrafficDoesNot() {
        val factory = FakeWebSocketFactory()
        val client = ZenWebSocketClient(
            wait = { CompletableDeferred<Unit>().await() },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val first = factory.opens.single()
        first.listener.onOpen(first.socket, response(first.request))
        first.listener.onMessage(first.socket, joinedMessage())

        assertTrue(client.sendPing(1, 100))
        assertEquals(1, client.pendingPingCount())
        first.listener.onMessage(first.socket, ByteString.of(1))
        assertEquals(0, client.pendingPingCount())

        assertTrue(client.sendPing(2, 200))
        first.listener.onMessage(
            first.socket,
            """{"type":"channel_state","revision":1,"participant_count":1,"next_burst_index":0,"floor":null}""",
        )
        assertEquals(0, client.pendingPingCount())

        client.connect("ws://second.example:8000", "ROOM", RecordingListener())
        val second = factory.opens.last()
        second.listener.onOpen(second.socket, response(second.request))
        second.listener.onMessage(second.socket, joinedMessage())
        assertTrue(client.sendPing(3, 300))

        first.listener.onMessage(first.socket, ByteString.of(2))
        first.listener.onMessage(
            first.socket,
            """{"type":"channel_state","revision":2,"participant_count":1,"next_burst_index":0,"floor":null}""",
        )
        assertEquals(1, client.pendingPingCount())
        second.listener.onMessage(second.socket, ByteString.of(3))
        assertEquals(0, client.pendingPingCount())
        client.disconnect()
    }

    @Test
    fun watchdogExpiresAfterTenUnansweredPingsAndRecordsDisconnectReason() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val waits = ControlledWaits()
        val client = ZenWebSocketClient(
            wait = waits::suspendFor,
            reconnectJitterMs = { 0 },
            elapsedRealtimeMs = { 100L },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        val readiness = waits.next()
        opened.listener.onMessage(opened.socket, joinedMessage())
        readiness.awaitCancelled()

        var nextPing = waits.next()
        repeat(ZenWebSocketClient.MAX_MISSED_PONGS) { index ->
            assertEquals(ZenWebSocketClient.PING_INTERVAL_MS, nextPing.delayMs)
            nextPing.resumeAndAwait()
            awaitCondition {
                opened.socket.sentText.count { it.contains("\"type\":\"ping\"") } == index + 1
            }
            assertEquals(index + 1, client.pendingPingCount())
            assertEquals(0, opened.socket.cancelCount)
            nextPing = waits.next()
        }

        nextPing.resumeAndAwait()
        awaitCondition { opened.socket.cancelCount == 1 }
        assertEquals(
            listOf("Application ping watchdog expired"),
            listener.diagnostics,
        )
        assertEquals(
            listOf("Application ping watchdog expired"),
            listener.disconnections,
        )
        awaitCondition { listener.reconnecting == listOf(1) }
        val reconnectWait = waits.next()

        opened.listener.onFailure(opened.socket, IllegalStateException("duplicate"), null)
        assertEquals(listOf(1), listener.reconnecting)
        client.disconnect()
        reconnectWait.awaitCancelled()
    }

    @Test
    fun cleanCloseCompletesGracefulDisconnectWithoutFallbackCancel() {
        val factory = FakeWebSocketFactory()
        val waits = ControlledWaits()
        val client = ZenWebSocketClient(wait = waits::suspendFor, webSocketFactory = factory::open)
        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        waits.next()

        client.disconnect()
        val gracefulClose = waits.next()
        assertEquals(ZenWebSocketClient.GRACEFUL_DISCONNECT_TIMEOUT_MS, gracefulClose.delayMs)
        opened.listener.onClosed(opened.socket, 1000, "client_disconnect")
        gracefulClose.awaitCancelled()

        assertEquals(0, opened.socket.cancelCount)
        assertEquals(0, client.pendingGracefulCloseCount())
    }

    @Test
    fun synchronousCloseCallbackCannotMissGracefulRegistration() {
        val factory = FakeWebSocketFactory()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)
        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val opened = factory.opens.single()
        opened.socket.onClose = {
            opened.listener.onClosed(opened.socket, 1000, "client_disconnect")
        }

        client.disconnect()

        assertEquals(0, client.pendingGracefulCloseCount())
        assertEquals(0, opened.socket.cancelCount)
    }

    @Test
    fun repeatedDisconnectsTrackClosingTransportsIndependently() {
        val factory = FakeWebSocketFactory()
        val waits = ControlledWaits()
        val client = ZenWebSocketClient(wait = waits::suspendFor, webSocketFactory = factory::open)
        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val first = factory.opens.single()
        first.listener.onOpen(first.socket, response(first.request))
        waits.next()
        client.disconnect()
        val firstClose = waits.next()

        client.connect("ws://server.example:8000", "ROOM", RecordingListener())
        val second = factory.opens.last()
        second.listener.onOpen(second.socket, response(second.request))
        waits.next()
        client.disconnect()
        val secondClose = waits.next()

        first.listener.onClosed(first.socket, 1000, "client_disconnect")
        firstClose.awaitCancelled()
        secondClose.resumeAndAwait()
        awaitCondition { second.socket.cancelCount == 1 }

        assertEquals(0, first.socket.cancelCount)
        assertEquals(1, second.socket.cancelCount)
    }

    @Test
    fun queueLimitIncludesEncodedMessageSizeForAudioAndControls() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            audioQueueLimitBytes = 100,
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        opened.listener.onMessage(opened.socket, joinedMessage())
        opened.socket.queuedBytes = 99

        assertFalse(client.sendAudio(ByteArray(2)))
        assertFalse(client.requestPtt("request"))
        assertTrue(opened.socket.sentBytes.isEmpty())
        assertEquals(listOf(ControlProtocol.join("ROOM")), opened.socket.sentText)
        client.disconnect()
    }

    @Test
    fun rejectedPingsAreNotAddedToPendingWatchdogState() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            wait = { CompletableDeferred<Unit>().await() },
            audioQueueLimitBytes = 100,
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        opened.listener.onMessage(opened.socket, joinedMessage())
        opened.socket.queuedBytes = 99

        assertFalse(client.sendPing(1, 100))
        assertFalse(client.sendPing(2, 200))
        assertEquals(0, client.pendingPingCount())
        opened.socket.queuedBytes = 0
        assertTrue(client.sendPing(3, 300))

        assertEquals(0, opened.socket.cancelCount)
        assertEquals(1, client.pendingPingCount())
        assertEquals(1, opened.socket.sentText.count { it.contains("\"type\":\"ping\"") })
        client.disconnect()
    }

    @Test
    fun binaryMessagesAreDeliveredOnlyFromCurrentSocket() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)

        client.connect("ws://first.example:8000", "ONE", listener)
        val first = factory.opens.single()
        client.connect("ws://second.example:8000", "TWO", listener)
        val second = factory.opens.last()

        first.listener.onMessage(first.socket, ByteString.of(1))
        second.listener.onMessage(second.socket, ByteString.of(2))

        assertEquals(listOf(listOf(2.toByte())), listener.audio.map(ByteArray::toList))
        client.disconnect()
    }

    @Test
    fun rejectedAudioSendReportsDiagnostic() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        opened.listener.onMessage(opened.socket, joinedMessage())
        opened.socket.acceptBytes = false

        assertFalse(client.sendAudio(byteArrayOf(1)))
        assertTrue(listener.diagnostics.contains("Audio WebSocket queue rejected a frame"))
        client.disconnect()
    }

    @Test
    fun audioAndControlWaitForApplicationSessionReadiness() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)

        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()

        assertFalse(client.sendAudio(byteArrayOf(1)))
        assertFalse(client.requestPtt("one"))
        assertTrue(opened.socket.sentBytes.isEmpty())

        opened.listener.onOpen(opened.socket, response(opened.request))

        assertEquals(listOf(ControlProtocol.join("ROOM")), opened.socket.sentText)
        assertFalse(client.sendAudio(byteArrayOf(2)))
        assertFalse(client.requestPtt("two"))
        assertTrue(opened.socket.sentBytes.isEmpty())

        opened.listener.onMessage(opened.socket, joinedMessage())

        assertTrue(client.sendAudio(byteArrayOf(3)))
        assertTrue(client.requestPtt("three"))
        assertEquals(listOf(listOf(3.toByte())), opened.socket.sentBytes.map(ByteArray::toList))
        client.disconnect()
    }

    @Test
    fun reconnectQueuesResumeBeforeRetainedAudioCanBeSent() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            wait = { if (it == ZenWebSocketClient.READINESS_TIMEOUT_MS) CompletableDeferred<Unit>().await() },
            reconnectPolicy = ReconnectPolicy(longArrayOf(0), steadyDelayMs = 0),
            reconnectJitterMs = { 0 },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val initial = factory.opens.single()
        initial.listener.onOpen(initial.socket, response(initial.request))
        initial.listener.onMessage(initial.socket, joinedMessage())

        initial.listener.onFailure(initial.socket, IllegalStateException("offline"), null)
        awaitCondition { factory.opens.size == 2 }
        val resumed = factory.opens.last()

        assertFalse(client.sendAudio(byteArrayOf(1)))
        resumed.listener.onOpen(resumed.socket, response(resumed.request))
        assertTrue(resumed.socket.sentText.single().contains("\"type\":\"resume\""))
        assertFalse(client.sendAudio(byteArrayOf(2)))
        assertTrue(resumed.socket.sentBytes.isEmpty())

        resumed.listener.onMessage(resumed.socket, resumedMessage())

        assertTrue(client.sendAudio(byteArrayOf(3)))
        assertEquals(listOf(listOf(3.toByte())), resumed.socket.sentBytes.map(ByteArray::toList))
        client.disconnect()
    }

    @Test
    fun backlogWarningCanBeReportedAgainAfterQueueDrains() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(webSocketFactory = factory::open)
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onMessage(
            opened.socket,
            joinedMessage(),
        )
        val limit = ZenWebSocketClient.audioQueueLimitBytes(500)

        opened.socket.queuedBytes = limit
        client.sendAudio(byteArrayOf(1))
        opened.socket.queuedBytes = 0
        client.sendAudio(byteArrayOf(1))
        opened.socket.queuedBytes = limit
        client.sendAudio(byteArrayOf(1))

        assertEquals(2, listener.diagnostics.count { it.startsWith("Audio backlog exceeded") })
        client.disconnect()
    }

    @Test
    fun reportsAudioQueueAgainstServerBacklogTarget() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            audioQueueLimitBytes = 1_500,
            webSocketFactory = factory::open,
        )

        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))
        opened.listener.onMessage(
            opened.socket,
            joinedMessage(),
        )

        client.sendAudio(ByteArray(1_000))
        client.sendAudio(ByteArray(1_000))

        assertTrue(listener.audioQueues.any { it.second })
        assertTrue(listener.diagnostics.any { it.startsWith("Audio backlog exceeded") })
        client.disconnect()
    }


    @Test
    fun duplicateFailureSchedulesOnlyOneRetry() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val retryGate = CountDownLatch(1)
        val client = ZenWebSocketClient(
            wait = { retryGate.await(2, TimeUnit.SECONDS); Unit },
            reconnectJitterMs = { 0 },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val first = factory.opens.single()

        first.listener.onFailure(first.socket, IllegalStateException("offline"), null)
        awaitCondition { listener.reconnecting == listOf(1) }
        first.listener.onMessage(first.socket, "{\"type\":\"participant_count\",\"count\":9}")
        assertTrue(listener.controls.isEmpty())
        assertEquals(listOf("IllegalStateException: offline"), listener.disconnections)
        first.listener.onClosed(first.socket, 1006, "duplicate")
        retryGate.countDown()
        awaitCondition { factory.opens.size == 2 }

        assertEquals(2, factory.opens.size)
        assertEquals(listOf(1), listener.reconnecting)
        assertEquals(1, listener.disconnections.size)
        client.disconnect()
        factory.opens.last().listener.onFailure(
            factory.opens.last().socket,
            IllegalStateException("late"),
            null,
        )
        assertEquals(2, factory.opens.size)
    }

    @Test
    fun reconnectsBeyondFastRetriesAndResetsAfterJoin() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            wait = { if (it == ZenWebSocketClient.READINESS_TIMEOUT_MS) CompletableDeferred<Unit>().await() },
            reconnectPolicy = ReconnectPolicy(longArrayOf(0, 0), steadyDelayMs = 0),
            reconnectJitterMs = { 0 },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)

        repeat(7) { index ->
            val failed = factory.opens.last()
            failed.listener.onFailure(failed.socket, IllegalStateException("offline"), null)
            awaitCondition { factory.opens.size == index + 2 }
        }

        assertEquals((1..7).toList(), listener.reconnecting)
        assertTrue(listener.connectionErrors.isEmpty())

        val joined = factory.opens.last()
        joined.listener.onMessage(
            joined.socket,
            joinedMessage(),
        )
        joined.listener.onFailure(joined.socket, IllegalStateException("offline again"), null)
        awaitCondition { factory.opens.size == 9 }

        assertEquals(1, listener.reconnecting.last())
        client.disconnect()
    }

    @Test
    fun invalidServerMessageReportsErrorAndReconnects() {
        val factory = FakeWebSocketFactory()
        val listener = RecordingListener()
        val client = ZenWebSocketClient(
            wait = { if (it == ZenWebSocketClient.READINESS_TIMEOUT_MS) CompletableDeferred<Unit>().await() },
            reconnectPolicy = ReconnectPolicy(longArrayOf(0), steadyDelayMs = 0),
            reconnectJitterMs = { 0 },
            webSocketFactory = factory::open,
        )
        client.connect("ws://server.example:8000", "ROOM", listener)
        val opened = factory.opens.single()
        opened.listener.onOpen(opened.socket, response(opened.request))

        opened.listener.onMessage(opened.socket, "{}")

        awaitCondition { factory.opens.size == 2 }
        assertEquals(1, listener.connectionErrors.size)
        assertTrue(listener.diagnostics.single().startsWith("Invalid server message"))
        client.disconnect()
    }

    private fun response(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private fun joinedMessage() = """{
        "type":"snapshot",
        "channel":"ROOM",
        "member_id":"member-1",
        "resume_token":"resume-1",
        "generation":1,
        "channel_incarnation_id":"incarnation-1",
        "revision":0,
        "participant_count":1,
        "eligible_from_index":0,
        "next_burst_index":0,
        "audio_policy":{"recovery_horizon_ms":5000},
        "floor":null
    }""".trimIndent()

    private fun resumedMessage() = """{
        "type":"snapshot",
        "channel":"ROOM",
        "member_id":"member-1",
        "resume_token":"resume-1",
        "generation":2,
        "channel_incarnation_id":"incarnation-1",
        "revision":1,
        "participant_count":1,
        "eligible_from_index":0,
        "next_burst_index":0,
        "audio_policy":{"recovery_horizon_ms":5000},
        "floor":null
    }""".trimIndent()

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition was not met", condition())
    }

    private class RecordingListener : ConnectionListener {
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val reconnecting = CopyOnWriteArrayList<Int>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val disconnections = CopyOnWriteArrayList<String>()
        val audioQueues = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val connectionErrors = CopyOnWriteArrayList<Unit>()
        override fun onControl(event: ControlEvent) { controls += event }
        override fun onAudio(message: ByteArray) { audio += message }
        override fun onReconnecting(attempt: Int) { reconnecting += attempt }
        override fun onConnectionError() { connectionErrors += Unit }
        override fun onConnectionDiagnostic(detail: String) { diagnostics += detail }
        override fun onConnectionDisconnected(detail: String) { disconnections += detail }
        override fun onAudioQueue(queueBytes: Long, limitExceeded: Boolean) {
            audioQueues += queueBytes to limitExceeded
        }
    }

    private class FakeWebSocketFactory(
        private val beforeReturn: ((OpenedSocket) -> Unit)? = null,
    ) {
        val opens = CopyOnWriteArrayList<OpenedSocket>()
        fun open(request: Request, listener: WebSocketListener): WebSocket {
            val socket = FakeWebSocket(request)
            val opened = OpenedSocket(request, socket, listener)
            opens += opened
            beforeReturn?.invoke(opened)
            return socket
        }
    }

    private class ControlledWaits {
        private val calls = LinkedBlockingQueue<ControlledWait>()

        suspend fun suspendFor(delayMs: Long) {
            val call = ControlledWait(delayMs)
            calls.add(call)
            call.await()
        }

        fun next(): ControlledWait = requireNotNull(calls.poll(2, TimeUnit.SECONDS))
        fun poll(): ControlledWait? = calls.poll(100, TimeUnit.MILLISECONDS)
    }

    private class ControlledWait(val delayMs: Long) {
        val gate = CompletableDeferred<Unit>()
        private val resumed = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val cancelled = AtomicBoolean(false)
        fun resume() = gate.complete(Unit)
        fun resumeAndAwait() {
            resume()
            assertTrue(resumed.await(2, TimeUnit.SECONDS))
        }

        fun awaitCancelled() {
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(cancelled.get())
        }

        suspend fun await() {
            try {
                gate.await()
                resumed.countDown()
            } catch (error: CancellationException) {
                cancelled.set(true)
                throw error
            } finally {
                finished.countDown()
            }
        }
    }

    private data class OpenedSocket(
        val request: Request,
        val socket: FakeWebSocket,
        val listener: WebSocketListener,
    )

    private class FakeWebSocket(private val originalRequest: Request) : WebSocket {
        val sentText = CopyOnWriteArrayList<String>()
        val sentBytes = CopyOnWriteArrayList<ByteArray>()
        var acceptBytes = true
        var onClose: (() -> Unit)? = null
        var cancelCount = 0
        val closeCodes = CopyOnWriteArrayList<Int>()
        var queuedBytes = 0L
        override fun request() = originalRequest
        override fun queueSize() = queuedBytes
        override fun send(text: String): Boolean {
            sentText += text
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            if (!acceptBytes) return false
            queuedBytes += bytes.size
            sentBytes += bytes.toByteArray()
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            closeCodes += code
            onClose?.invoke()
            return true
        }
        override fun cancel() {
            cancelCount += 1
        }
    }
}
