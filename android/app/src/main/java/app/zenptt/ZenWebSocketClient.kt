// Owns one ZenPTT v4 WebSocket, logical resume, liveness, and reconnect scheduling.
package app.zenptt

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

interface ConnectionListener {
    fun onControl(event: ControlEvent)
    fun onAudio(message: ByteArray)
    fun onReconnecting(attempt: Int)
    fun onConnectionError()
    fun onConnectionDiagnostic(detail: String) = Unit
    fun onConnectionDisconnected(detail: String) = Unit
    fun onAudioQueue(queueBytes: Long, limitExceeded: Boolean) = Unit
}

interface NetworkMonitor {
    fun start(onAvailable: () -> Unit)
    fun stop()
}

object NoOpNetworkMonitor : NetworkMonitor {
    override fun start(onAvailable: () -> Unit) = Unit
    override fun stop() = Unit
}

interface ConnectionClient {
    fun connect(address: String, channel: String, listener: ConnectionListener)
    fun requestPtt(requestId: String): Boolean = false
    fun releasePtt(requestId: String): Boolean = false
    fun cancelPtt(requestId: String): Boolean = releasePtt(requestId)
    fun finishBurst(burstId: String, finalNextSequence: Long): Boolean = false
    fun listen(burstIndex: Long, nextSequence: Long): Boolean = false
    fun sendAudio(message: ByteArray): Boolean
    fun disconnect()
}

data class ReconnectAttempt(val number: Int, val delayMs: Long)

class ReconnectPolicy(
    private val delaysMs: LongArray = ZenWebSocketClient.RECONNECT_DELAYS_MS,
    private val steadyDelayMs: Long = ZenWebSocketClient.STEADY_RECONNECT_DELAY_MS,
) {
    private var index = 0

    fun next(): ReconnectAttempt {
        val number = if (index == Int.MAX_VALUE) Int.MAX_VALUE else index + 1
        val delayMs = delaysMs.getOrNull(index) ?: steadyDelayMs
        val attempt = ReconnectAttempt(number, delayMs)
        index = number
        return attempt
    }

    fun reset() {
        index = 0
    }
}

private data class ResumeContext(val token: String, val generation: Int)

class ZenWebSocketClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val reconnectJitterMs: () -> Long = { Random.nextLong(0, 101) },
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val audioQueueLimitBytes: Long = MAX_INTERNAL_QUEUE_BYTES,
    private val networkMonitor: NetworkMonitor = NoOpNetworkMonitor,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = httpClient::newWebSocket,
) : ConnectionClient {
    private val stateLock = Any()
    private val sendLock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var readySocket: WebSocket? = null
    private var listener: ConnectionListener? = null
    private var address = ""
    private var channel = ""
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var readinessJob: Job? = null
    private var openingGeneration: Long? = null
    @Volatile private var intentionalClose = false
    @Volatile private var generation = 0L
    private var resumeContext: ResumeContext? = null
    private var lastResumeGeneration = 0
    private val audioBacklogReported = AtomicBoolean(false)
    private val pendingPings = ConcurrentHashMap.newKeySet<Int>()
    private val gracefulCloseJobs = IdentityHashMap<WebSocket, Job?>()
    private var pingId = 0

    override fun connect(address: String, channel: String, listener: ConnectionListener) {
        val previousSocket = synchronized(stateLock) {
            val sameLogicalTarget = this.address == address && this.channel == channel
            intentionalClose = true
            generation += 1
            reconnectJob?.cancel()
            pingJob?.cancel()
            readinessJob?.cancel()
            val previous = socket
            socket = null
            readySocket = null
            openingGeneration = null
            if (!sameLogicalTarget) {
                resumeContext = null
                lastResumeGeneration = 0
            }
            this.address = address
            this.channel = channel
            this.listener = listener
            reconnectPolicy.reset()
            pendingPings.clear()
            intentionalClose = false
            previous
        }
        previousSocket?.cancel()
        networkMonitor.start(::networkAvailable)
        openSocket()
    }

    override fun requestPtt(requestId: String): Boolean = sendControl(ControlProtocol.pttRequest(requestId))

    override fun cancelPtt(requestId: String): Boolean =
        sendControl(ControlProtocol.pttCancel(requestId))

    override fun releasePtt(requestId: String): Boolean = cancelPtt(requestId)

    override fun finishBurst(burstId: String, finalNextSequence: Long): Boolean =
        sendControl(ControlProtocol.burstEnd(burstId, finalNextSequence))

    override fun listen(burstIndex: Long, nextSequence: Long): Boolean =
        sendControl(ControlProtocol.listen(burstIndex, nextSequence))

    override fun sendAudio(message: ByteArray): Boolean {
        val webSocket = readySocket ?: return false
        val sent = sendBytesBounded(webSocket, message)
        if (!sent) currentListener()?.onConnectionDiagnostic("Audio WebSocket queue rejected a frame")
        return sent
    }

    private fun sendControl(message: String): Boolean {
        val webSocket = readySocket ?: return false
        return sendTextBounded(webSocket, message)
    }

    internal fun sendPing(id: Int, sentAtMs: Long): Boolean {
        val webSocket = readySocket ?: return false
        return sendPing(webSocket, id, sentAtMs)
    }

    internal fun pendingPingCount(): Int = pendingPings.size

    internal fun pendingGracefulCloseCount(): Int = synchronized(stateLock) {
        gracefulCloseJobs.size
    }

    private fun sendPing(webSocket: WebSocket, id: Int, sentAtMs: Long): Boolean {
        val sent = sendTextBounded(webSocket, ControlProtocol.ping(id, sentAtMs))
        if (sent) pendingPings += id
        return sent
    }

    private fun sendBytesBounded(webSocket: WebSocket, message: ByteArray): Boolean =
        synchronized(sendLock) {
            reportAudioQueue(webSocket, message.size.toLong())
            val sent = if (fitsQueue(webSocket, message.size.toLong())) {
                webSocket.send(ByteString.of(*message))
            } else false
            reportAudioQueue(webSocket)
            sent
        }

    private fun sendTextBounded(webSocket: WebSocket, message: String): Boolean =
        synchronized(sendLock) {
            val encodedSize = message.toByteArray(Charsets.UTF_8).size.toLong()
            if (!fitsQueue(webSocket, encodedSize)) return@synchronized false
            webSocket.send(message)
        }

    private fun fitsQueue(webSocket: WebSocket, encodedSize: Long): Boolean {
        val queuedSize = webSocket.queueSize()
        return encodedSize <= audioQueueLimitBytes &&
            queuedSize <= audioQueueLimitBytes - encodedSize
    }

    private fun reportAudioQueue(webSocket: WebSocket, offeredBytes: Long = 0) {
        val queueBytes = webSocket.queueSize()
        val exceeded = offeredBytes > audioQueueLimitBytes ||
            queueBytes >= audioQueueLimitBytes - offeredBytes
        currentListener()?.onAudioQueue(queueBytes, exceeded)
        if (exceeded && audioBacklogReported.compareAndSet(false, true)) {
            currentListener()?.onConnectionDiagnostic("Audio backlog exceeded: ${queueBytes}B")
        } else if (queueBytes < audioQueueLimitBytes / 2) {
            audioBacklogReported.set(false)
        }
    }

    private fun currentListener(): ConnectionListener? = synchronized(stateLock) { listener }

    override fun disconnect() {
        val closingSocket = synchronized(stateLock) {
            intentionalClose = true
            generation += 1
            reconnectJob?.cancel()
            pingJob?.cancel()
            readinessJob?.cancel()
            val current = socket
            if (current != null) gracefulCloseJobs[current] = null
            socket = null
            readySocket = null
            openingGeneration = null
            listener = null
            resumeContext = null
            lastResumeGeneration = 0
            pendingPings.clear()
            current
        }
        networkMonitor.stop()
        closingSocket?.let { webSocket ->
            val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
                wait(GRACEFUL_DISCONNECT_TIMEOUT_MS)
                val stillClosing = synchronized(stateLock) {
                    if (!gracefulCloseJobs.containsKey(webSocket)) false else {
                        gracefulCloseJobs.remove(webSocket)
                        true
                    }
                }
                if (stillClosing) webSocket.cancel()
            }
            val tracked = synchronized(stateLock) {
                if (!gracefulCloseJobs.containsKey(webSocket)) false else {
                    gracefulCloseJobs[webSocket] = timeoutJob
                    true
                }
            }
            if (tracked) timeoutJob.start() else timeoutJob.cancel()
            sendTextBounded(webSocket, ControlProtocol.disconnect())
            synchronized(sendLock) {
                webSocket.close(1000, "client_disconnect")
            }
        }
    }

    private fun openSocket() {
        val socketState = synchronized(stateLock) {
            if (intentionalClose) return
            generation += 1
            openingGeneration = generation
            Triple(generation, address, channel)
        }
        val (socketGeneration, socketAddress, socketChannel) = socketState
        val request = Request.Builder()
            .url(socketAddress.trimEnd('/') + "/ws")
            .header("Sec-WebSocket-Protocol", WEBSOCKET_SUBPROTOCOL)
            .build()
        val newSocket = webSocketFactory(request, SocketListener(socketGeneration, socketChannel))
        val accepted = synchronized(stateLock) {
            when {
                intentionalClose || generation != socketGeneration -> false
                socket === newSocket -> true
                socket == null && openingGeneration == socketGeneration -> {
                    socket = newSocket
                    readySocket = null
                    openingGeneration = null
                    true
                }
                else -> false
            }
        }
        if (!accepted) newSocket.cancel()
    }

    private fun scheduleReconnect(failedGeneration: Long, immediate: Boolean = false) {
        synchronized(stateLock) {
            if (intentionalClose || generation != failedGeneration || reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                val attempt = synchronized(stateLock) { reconnectPolicy.next() }
                val reconnectListener = synchronized(stateLock) {
                    if (!intentionalClose && generation == failedGeneration) listener else null
                } ?: return@launch
                reconnectListener.onReconnecting(attempt.number)
                if (!immediate) wait(attempt.delayMs + reconnectJitterMs())
                val retry = synchronized(stateLock) {
                    val current = !intentionalClose && generation == failedGeneration
                    reconnectJob = null
                    current
                }
                if (retry) openSocket()
            }
        }
    }

    private fun networkAvailable() {
        val failedGeneration = synchronized(stateLock) {
            if (intentionalClose || socket != null || openingGeneration != null) return
            reconnectJob?.cancel()
            reconnectJob = null
            generation
        }
        scheduleReconnect(failedGeneration, immediate = true)
    }

    private fun startPing(socketGeneration: Long, webSocket: WebSocket) {
        synchronized(stateLock) {
            if (intentionalClose || generation != socketGeneration || socket !== webSocket) return
            pingJob?.cancel()
            pendingPings.clear()
            pingJob = scope.launch {
                while (true) {
                    wait(PING_INTERVAL_MS)
                    if (pendingPings.size >= MAX_MISSED_PONGS) {
                        expireSocket(
                            socketGeneration,
                            webSocket,
                            "Application ping watchdog expired",
                        )
                        return@launch
                    }
                    val id = synchronized(stateLock) {
                        if (intentionalClose || generation != socketGeneration || socket !== webSocket) return@launch
                        ++pingId
                    }
                    sendPing(webSocket, id, elapsedRealtimeMs())
                }
            }
        }
    }

    private inner class SocketListener(
        private val socketGeneration: Long,
        private val socketChannel: String,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!activateCurrentSocket(webSocket)) return
            synchronized(stateLock) {
                readinessJob?.cancel()
                readinessJob = scope.launch {
                    wait(READINESS_TIMEOUT_MS)
                    val timedOut = synchronized(stateLock) {
                        isCurrent() && socket === webSocket && readySocket !== webSocket
                    }
                    if (timedOut) {
                        currentListener()?.onConnectionDiagnostic("Snapshot readiness watchdog expired")
                        webSocket.cancel()
                        handleSocketDisconnect(socketGeneration, webSocket)
                    }
                }
            }
            val resume = synchronized(stateLock) { resumeContext }
            if (resume == null) {
                sendFreshJoin(webSocket)
            } else {
                val nextGeneration = synchronized(stateLock) {
                    maxOf(resume.generation + 1, lastResumeGeneration + 1).also {
                        lastResumeGeneration = it
                    }
                }
                sendTextBounded(webSocket, ControlProtocol.resume(resume.token, nextGeneration))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentSocket(webSocket)) return
            val event = try {
                ControlProtocol.parse(text)
            } catch (error: Exception) {
                currentListener()?.onConnectionDiagnostic("Invalid server message: ${error.javaClass.simpleName}")
                currentListener()?.onConnectionError()
                webSocket.cancel()
                handleDisconnect(webSocket)
                return
            }
            if (!recordInboundActivity(webSocket)) return
            when (event) {
                is ControlEvent.Snapshot -> joined(event.session)
                is ControlEvent.ResumeRejected -> {
                    synchronized(stateLock) {
                        resumeContext = null
                        lastResumeGeneration = 0
                    }
                    currentListener()?.onControl(event)
                    sendFreshJoin(webSocket)
                    return
                }
                else -> Unit
            }
            currentListener()?.onControl(event)
        }

        private fun sendFreshJoin(webSocket: WebSocket) {
            val message = if (socketChannel == ECHO_CHANNEL) {
                ControlProtocol.joinEcho()
            } else {
                ControlProtocol.join(socketChannel)
            }
            sendTextBounded(webSocket, message)
        }

        private fun joined(session: SessionSnapshot) {
            synchronized(stateLock) {
                readinessJob?.cancel()
                readinessJob = null
                resumeContext = session.resumeToken.takeIf(String::isNotEmpty)?.let {
                    ResumeContext(it, session.generation)
                }
                lastResumeGeneration = session.generation
                if (isCurrent()) {
                    readySocket = socket
                    reconnectPolicy.reset()
                }
            }
            audioBacklogReported.set(false)
            startPing(socketGeneration, requireNotNull(socket))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!recordInboundActivity(webSocket)) return
            currentListener()?.onAudio(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val termination = terminateSocket(socketGeneration, webSocket) ?: return
            termination.listener?.onConnectionDisconnected(
                "WebSocket closed: code=$code reason=${reason.take(120)}",
            )
            scheduleReconnect(socketGeneration)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val termination = terminateSocket(socketGeneration, webSocket) ?: return
            val responseCode = response?.code?.let { " HTTP $it" }.orEmpty()
            val message = throwable.message?.lineSequence()?.firstOrNull()?.take(160) ?: "no message"
            termination.listener?.onConnectionDisconnected(
                "${throwable.javaClass.simpleName}: $message$responseCode",
            )
            scheduleReconnect(socketGeneration)
        }

        private fun handleDisconnect(webSocket: WebSocket) =
            handleSocketDisconnect(socketGeneration, webSocket)

        private fun currentListener(): ConnectionListener? = synchronized(stateLock) {
            if (isCurrent()) listener else null
        }

        private fun isCurrentSocket(webSocket: WebSocket): Boolean =
            synchronized(stateLock) { isCurrent() && socket === webSocket }

        private fun recordInboundActivity(webSocket: WebSocket): Boolean =
            synchronized(stateLock) {
                (isCurrent() && socket === webSocket).also { current ->
                    if (current) pendingPings.clear()
                }
            }

        private fun activateCurrentSocket(webSocket: WebSocket): Boolean = synchronized(stateLock) {
            if (!isCurrent()) false else when {
                socket === webSocket -> true
                socket == null && openingGeneration == socketGeneration -> {
                    socket = webSocket
                    readySocket = null
                    openingGeneration = null
                    true
                }
                else -> false
            }
        }

        private fun isCurrent(): Boolean = !intentionalClose && generation == socketGeneration
    }

    private data class SocketTermination(val listener: ConnectionListener?)

    private fun terminateSocket(socketGeneration: Long, webSocket: WebSocket): SocketTermination? {
        var gracefulJob: Job? = null
        val termination = synchronized(stateLock) {
            if (gracefulCloseJobs.containsKey(webSocket)) {
                gracefulJob = gracefulCloseJobs.remove(webSocket)
                null
            } else if (intentionalClose || generation != socketGeneration) {
                null
            } else if (
                socket !== webSocket &&
                !(socket == null && openingGeneration == socketGeneration)
            ) {
                null
            } else {
                pingJob?.cancel()
                readinessJob?.cancel()
                readinessJob = null
                pendingPings.clear()
                socket = null
                readySocket = null
                if (openingGeneration == socketGeneration) openingGeneration = null
                SocketTermination(listener)
            }
        }
        gracefulJob?.cancel()
        return termination
    }

    private fun expireSocket(socketGeneration: Long, webSocket: WebSocket, detail: String) {
        val termination = terminateSocket(socketGeneration, webSocket) ?: return
        termination.listener?.onConnectionDiagnostic(detail)
        termination.listener?.onConnectionDisconnected(detail)
        webSocket.cancel()
        scheduleReconnect(socketGeneration)
    }

    private fun handleSocketDisconnect(socketGeneration: Long, webSocket: WebSocket) {
        val reconnect = synchronized(stateLock) {
            if (intentionalClose || generation != socketGeneration) false else {
                pingJob?.cancel()
                readinessJob?.cancel()
                readinessJob = null
                pendingPings.clear()
                if (socket === webSocket) {
                    socket = null
                    readySocket = null
                }
                if (openingGeneration == socketGeneration) openingGeneration = null
                true
            }
        }
        if (reconnect) scheduleReconnect(socketGeneration)
    }

    companion object {
        const val WEBSOCKET_SUBPROTOCOL = "zenptt.v4"
        const val PING_INTERVAL_MS = 1_000L
        const val MAX_MISSED_PONGS = 10
        const val MAX_INTERNAL_QUEUE_BYTES = 8L * 1024
        const val READINESS_TIMEOUT_MS = 5_000L
        const val GRACEFUL_DISCONNECT_TIMEOUT_MS = 1_000L
        const val STEADY_RECONNECT_DELAY_MS = 5_000L
        val RECONNECT_DELAYS_MS = longArrayOf(0, 250, 500, 1_000, 2_000)

        internal fun audioQueueLimitBytes(audioBacklogMs: Int): Long = MAX_INTERNAL_QUEUE_BYTES
    }
}
