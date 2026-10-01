// Owns one v4 connection, grant-gated PTT, audio recovery, and UI state.
import {
  decodeMedia, encodeControl, ERROR_MESSAGES, MAX_BURST_INDEX, MAX_MESSAGE_BYTES,
  normalizeChannel, parseControl, ProtocolError, SUBPROTOCOL,
} from './protocol'
import type { ClientControl, MediaEnvelope, ServerControl } from './protocol'
import { audioErrorMessage, BrowserAudio } from './audio/BrowserAudio'
import type { AudioEvents, ClientAudio } from './audio/messages'
import { ackWatchdogMs, compareCursor, historyMs, UplinkBuffer } from './recovery'
import type { PlaybackCursor } from './recovery'
import { ReceiveBuffer } from './ReceiveBuffer'
import { receiveQuality, uplinkQuality } from './haloSpec'
import type { Quality } from './haloSpec'
import { FRAME_DURATION_MS, MAX_BURST_DURATION_MS, MAX_BURST_FRAMES } from './mediaLimits'

const OPEN_TIMEOUT_MS = 10000
const SNAPSHOT_TIMEOUT_MS = 5000
const DISCONNECT_TIMEOUT_MS = 1000
const PING_INTERVAL_MS = 1000
const MAX_UNANSWERED_PINGS = 10
const MAX_QUEUED_BYTES = 8192
const RECONNECT_DELAYS_MS = [0, 250, 500, 1000, 2000, 5000]

export interface ClientState {
  readonly status: 'offline' | 'connecting' | 'connected' | 'reconnecting' | 'error'
  readonly channel: string | null
  readonly participantCount: bigint | null
  readonly remoteTransmitting: boolean
  readonly error: string | null
  readonly pttAvailable: boolean
  readonly audio: 'off' | 'preparing' | 'ready'
  readonly audioError: string | null
  readonly ptt: 'idle' | 'requesting' | 'transmitting' | 'ending'
  readonly receiving: boolean
  readonly quality: Quality
  readonly pttError: boolean
  readonly rttMs: number | null
  readonly grantMs: number | null
  readonly lostFrames: number
  readonly playbackBlocked: boolean
}

type SocketFactory = (url: string, protocol: string) => WebSocket
interface Transport {
  socket: WebSocket
  channel: string
  joined: boolean
  revision: bigint
  pendingPings: number
  pingId: number
  deadline: ReturnType<typeof setTimeout>
  heartbeat?: ReturnType<typeof setInterval>
  wireGeneration: number
  resuming: boolean
  pingTimes: Map<number, number>
}

interface Transmission {
  requestId: string
  burstId: string | null
  buffer: UplinkBuffer | null
  progressAt: number
  requestedAt: number
  captureStopped: boolean
  stopping: boolean
  endSent: boolean
  restartAfter: boolean
  timer: ReturnType<typeof setTimeout>
}

function logFailure(code: string) {
  try { console.error(`[ZenPTT] ${code}`) } catch { /* Logging must not affect session cleanup. */ }
}

export function websocketUrl(pageUrl: string): string {
  const url = new URL(pageUrl)
  if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new Error('Open ZenPTT over HTTPS or localhost.')
  if (url.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
    throw new Error('HTTPS is required outside localhost.')
  }
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws'
  url.search = ''
  url.hash = ''
  url.username = ''
  url.password = ''
  return url.toString()
}

export class ZenPttClient {
  private state: ClientState = Object.freeze({
    status: 'offline', channel: null, participantCount: null,
    remoteTransmitting: false, error: null, pttAvailable: false,
    audio: 'off', audioError: null, ptt: 'idle', receiving: false,
    quality: 'good', pttError: false, rttMs: null, grantMs: null, lostFrames: 0, playbackBlocked: false,
  })
  private readonly listeners = new Set<() => void>()
  private transport: Transport | null = null
  private generation = 0
  private closing: Promise<void> = Promise.resolve()
  private readonly url: string
  private audio: ClientAudio | null = null
  private transmission: Transmission | null = null
  private readonly canceledRequests = new Set<string>()
  private receiver: ReceiveBuffer | null = null
  private listenIndex = 0
  private listenSequence = 0
  private queueLimit = MAX_BURST_FRAMES
  private horizon = 5000
  private session: { token: string; memberId: string; incarnation: string; generation: number } | null = null
  private reconnectTimer?: ReturnType<typeof setTimeout>
  private monitor?: ReturnType<typeof setInterval>
  private outageAt: number | null = null
  private reconnectAttempt = 0
  private held = false
  private releaseRequired = false
  private lastReset: PlaybackCursor | null = null
  private resetNotice: string | null = null
  private microphone = ''
  private cancelPing: (() => void) | null = null
  private readonly events: { at: number; status: ClientState['status']; audio: ClientState['audio']; ptt: ClientState['ptt'] }[] = []

  constructor(pageUrl: string, private readonly createSocket: SocketFactory = (url, protocol) => new WebSocket(url, protocol),
    private readonly createAudio: (events: AudioEvents) => ClientAudio = events => new BrowserAudio(events),
    private readonly now: () => number = () => performance.now()) {
    this.url = websocketUrl(pageUrl)
  }

  getState = (): ClientState => this.state

  async selectMicrophone(deviceId: string): Promise<void> {
    if (this.transmission || this.state.audio === 'preparing') throw new Error('Wait until audio is ready and PTT has finished.')
    if (deviceId.length > 512) throw new Error('Invalid microphone selection.')
    try { await this.audio?.changeMicrophone(deviceId) } catch (error) { throw new Error(audioErrorMessage(error), { cause: error }) }
    this.microphone = deviceId
  }

  // An unjoined, short-lived probe also works from Settings while disconnected.
  ping(): Promise<number> {
    this.cancelPing?.()
    return new Promise((resolve, reject) => {
      let started = this.now()
      const socket = this.createSocket(this.url, SUBPROTOCOL)
      const finish = (value?: number) => {
        clearTimeout(timer)
        socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null
        this.cancelPing = null
        try { socket.close(1000) } catch { /* A failed probe has no session to leave. */ }
        if (value === undefined) reject(new Error('Ping failed. Check the server connection.'))
        else { this.publish({ rttMs: value }); resolve(value) }
      }
      const timer = setTimeout(() => finish(), 5000)
      this.cancelPing = () => finish()
      socket.onopen = () => {
        if (socket.protocol !== SUBPROTOCOL) { finish(); return }
        started = this.now()
        try { socket.send(encodeControl({ type: 'ping', id: 0, sent_at_ms: BigInt(Date.now()) })) } catch { finish() }
      }
      socket.onmessage = event => {
        try { const message = parseControl(event.data); if (message.type === 'pong' && message.id === 0) finish(Math.round(this.now() - started)) } catch { finish() }
      }
      socket.onerror = socket.onclose = () => finish()
    })
  }

  diagnosticReport(build: string): string {
    const state = this.state
    // Whitelist scalars: never serialize state, sockets, errors, settings, or packet objects.
    return JSON.stringify({ format: 1, build, protocol: SUBPROTOCOL, capturedAt: new Date().toISOString(),
      status: state.status, audio: state.audio, ptt: state.ptt, quality: state.quality,
      rttMs: state.rttMs, grantMs: state.grantMs, lostFrames: state.lostFrames, playbackBlocked: state.playbackBlocked,
      participants: state.participantCount?.toString() ?? null, recoveryHorizonMs: this.horizon,
      retainedPackets: this.transmission?.buffer?.packets.size ?? 0, events: this.events,
    }, null, 2)
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  connect(channel: string): void {
    const normalized = normalizeChannel(channel)
    if (this.state.channel === normalized && ['connecting', 'connected', 'reconnecting'].includes(this.state.status)) return
    const generation = ++this.generation
    const closing = this.closeTransport()
    this.monitor = setInterval(() => this.tick(), 50)
    this.publish({ status: 'connecting', channel: normalized, participantCount: null, remoteTransmitting: false, error: null })
    void closing.then(() => {
      if (generation === this.generation) this.open(normalized)
    })
  }

  disconnect(): Promise<void> {
    ++this.generation
    const closing = this.closeTransport()
    this.publish({ status: 'offline', participantCount: null, remoteTransmitting: false, error: null })
    return closing
  }

  async enableAudio(): Promise<void> {
    // Start from the Connect gesture, before the asynchronous WebSocket handshake.
    if (!['connecting', 'connected', 'reconnecting'].includes(this.state.status) || this.state.audio !== 'off') return
    const audio = this.createAudio({
      packet: (id, packet) => { if (this.audio === audio) this.capturePacket(id, packet) },
      captureEnded: id => { if (this.audio === audio) this.captureEnded(id) },
      captureExpired: id => {
        if (this.audio === audio && this.transmission?.requestId === id) this.interruptCapture(this.outageAt === null
          ? 'Transmission reached the 60-second limit. Release PTT.'
          : 'Audio recovery time expired. Release PTT and press again after connection returns.')
      },
      playback: active => {
        if (this.audio !== audio) return
        if (!active && this.state.receiving && this.state.status === 'connected' && !this.state.remoteTransmitting && !this.transmission) audio.cue('free')
        this.publish({ receiving: active })
      },
      played: cursor => { if (this.audio === audio) this.receiver?.played(cursor) },
      quality: (lostFrames, blocked) => {
        if (this.audio === audio) this.publish({ lostFrames, playbackBlocked: blocked,
          ...(this.transmission ? {} : { quality: receiveQuality(lostFrames, blocked) }) })
      },
      error: message => { if (this.audio === audio) this.fail(message, 'audio_failed') },
    })
    this.audio = audio
    this.publish({ audio: 'preparing', audioError: null })
    try {
      await audio.prepare(this.microphone)
      if (this.audio !== audio) { audio.dispose(); return }
      audio.setQueueLimit(this.queueLimit)
      this.receiver = new ReceiveBuffer(audio, this.queueLimit)
      this.receiver.reset({ burstIndex: this.listenIndex, nextSequence: this.listenSequence })
      this.publish({ audio: 'ready' })
      this.listen()
    } catch (error) {
      if (this.audio !== audio) return
      audio.dispose()
      this.audio = null
      this.publish({ audio: 'off', audioError: audioErrorMessage(error) })
    }
  }

  pttDown(): boolean {
    if (this.held || this.releaseRequired) return false
    this.held = true
    return this.requestTransmission()
  }

  private requestTransmission(): boolean {
    const transport = this.transport
    if (!transport?.joined || !this.state.pttAvailable || this.transmission) return false
    const requestId = crypto.randomUUID()
    this.transmission = {
      requestId, burstId: null, buffer: null, progressAt: this.now(), requestedAt: this.now(),
      captureStopped: false, stopping: false, endSent: false, restartAfter: false,
      timer: setTimeout(() => {
        this.pttUp()
        this.publish({ error: 'Transmission request timed out. Release and try again.' })
      }, 5000),
    }
    this.publish({ ptt: 'requesting', error: null, pttError: false })
    return this.send(transport, { type: 'ptt_request', request_id: requestId })
  }

  pttUp(): void {
    this.held = false
    this.releaseRequired = false
    const tx = this.transmission
    if (!tx) return
    tx.restartAfter = false
    this.finishCapture()
  }

  private finishCapture() {
    const tx = this.transmission
    const transport = this.transport
    if (!tx || tx.stopping) return
    clearTimeout(tx.timer)
    if (!tx.burstId) {
      this.canceledRequests.add(tx.requestId)
      if (this.canceledRequests.size > 16) this.canceledRequests.delete(this.canceledRequests.values().next().value!)
      this.transmission = null
      this.publish({ ptt: 'idle' })
      if (transport?.joined) this.send(transport, { type: 'ptt_cancel', request_id: tx.requestId })
    } else {
      this.publish({ ptt: 'ending' })
      tx.stopping = true
      tx.timer = setTimeout(() => this.fail('Audio did not stop. Connect again.', 'capture_stop_timeout'), 1000)
      this.audio?.stopCapture(tx.requestId)
    }
  }

  private capturePacket(requestId: string, packet: Uint8Array) {
    const tx = this.transmission
    if (!tx?.buffer || tx.requestId !== requestId || tx.captureStopped || tx.buffer.nextSequence >= MAX_BURST_FRAMES) return
    try {
      if (tx.buffer.nextSequence === tx.buffer.acknowledged) tx.progressAt = this.now()
      tx.buffer.append(packet, this.now())
      this.flushUplink()
      if (tx.buffer.nextSequence === MAX_BURST_FRAMES) this.interruptCapture('Transmission reached the 60-second limit. Release PTT.')
    } catch { this.fail('Audio retention limit exceeded. Connect again.', 'uplink_retention_full') }
  }

  private captureEnded(requestId: string) {
    const tx = this.transmission
    if (!tx?.buffer || tx.requestId !== requestId || !tx.stopping || tx.captureStopped) return
    clearTimeout(tx.timer)
    tx.captureStopped = true
    tx.buffer.end()
    tx.progressAt = this.now()
    this.flushUplink()
  }

  private stopTransmission(restart = false) {
    const tx = this.transmission
    this.transmission = null
    if (tx) { clearTimeout(tx.timer); this.audio?.stopCapture(tx.requestId) }
    this.publish({ ptt: 'idle' })
    if (restart && this.held && !this.releaseRequired) this.requestTransmission()
  }

  private interruptCapture(message: string) {
    this.audio?.cue('interrupted')
    this.releaseRequired = this.held
    if (this.transmission) this.transmission.restartAfter = false
    this.finishCapture()
    this.publish({ error: message })
  }

  private flushUplink() {
    const tx = this.transmission, transport = this.transport
    if (!tx?.buffer || !transport?.joined) return
    tx.buffer.expire(this.now())
    for (let i = 0; i < 4; i++) {
      const next = tx.buffer.envelope()
      if (!next) break
      if (transport.socket.bufferedAmount + next.bytes.length > MAX_QUEUED_BYTES) return
      if (!this.sendRaw(transport, next.bytes)) return
      tx.buffer.sendCursor = next.next
    }
    if (tx.buffer.finalSequence !== null && !tx.endSent && !tx.buffer.envelope()) {
      tx.endSent = this.send(transport, { type: 'burst_end', burst_id: tx.buffer.burstId, final_next_sequence: tx.buffer.finalSequence })
    }
  }

  private listen() {
    if (!this.transport?.joined || !this.receiver) return
    const cursor = this.receiver.cursor
    this.send(this.transport, { type: 'listen', burst_index: cursor.burstIndex, next_sequence: cursor.nextSequence })
  }

  private tick() {
    const now = this.now()
    if (this.outageAt !== null && now - this.outageAt >= historyMs(this.horizon)) {
      this.fail('Recovery time expired. Connect again.', 'recovery_expired')
      return
    }
    const tx = this.transmission
    if (tx?.buffer) {
      const age = tx.buffer.acknowledged < tx.buffer.nextSequence ? Math.max(0, now - tx.buffer.startedAt - tx.buffer.acknowledged * FRAME_DURATION_MS) : 0
      const quality = uplinkQuality(age, this.horizon)
      if (quality !== this.state.quality) this.publish({ quality })
      tx.buffer.expire(now)
      if (this.outageAt !== null && now - this.outageAt >= this.horizon && !tx.stopping) this.interruptCapture('Audio recovery time expired. Release PTT and press again after connection returns.')
      this.flushUplink()
      if (this.transport?.joined && (tx.buffer.acknowledged < tx.buffer.nextSequence || tx.endSent)
        && now - tx.progressAt >= ackWatchdogMs(this.horizon)) this.recover(this.transport, 'Audio acknowledgement timed out.')
    }
  }

  private publish(changes: Partial<ClientState>) {
    const state = { ...this.state, ...changes }
    if (state.status !== this.state.status || state.ptt !== this.state.ptt || state.audio !== this.state.audio) {
      this.events.push({ at: Math.round(this.now()), status: state.status, ptt: state.ptt, audio: state.audio })
      if (this.events.length > 256) this.events.shift()
    }
    state.pttAvailable = ['connected', 'reconnecting'].includes(state.status) && state.audio === 'ready'
    this.state = Object.freeze(state)
    for (const listener of this.listeners) {
      try { listener() } catch { logFailure('state_listener_failed') }
    }
  }

  private open(channel: string) {
    const resuming = this.session !== null
    const wireGeneration = this.session ? ++this.session.generation : 1
    if (wireGeneration > MAX_BURST_INDEX) { this.fail('Session generation exhausted. Connect again.', 'generation_exhausted'); return }
    let socket: WebSocket
    try { socket = this.createSocket(this.url, SUBPROTOCOL) } catch {
      this.scheduleReconnect('Unable to open the connection.')
      return
    }
    socket.binaryType = 'arraybuffer'
    const transport: Transport = {
      socket, channel, joined: false, revision: -1n, pendingPings: 0, pingId: 0,
      resuming, wireGeneration, pingTimes: new Map(),
      deadline: setTimeout(() => this.recover(transport, 'Connection timed out.'), OPEN_TIMEOUT_MS),
    }
    this.transport = transport
    socket.onopen = () => {
      if (this.transport !== transport) return
      if (socket.protocol !== SUBPROTOCOL) {
        this.failCurrent(transport, 'The server does not support ZenPTT v4.', 'subprotocol_mismatch')
        return
      }
      clearTimeout(transport.deadline)
      transport.deadline = setTimeout(() => this.recover(transport, 'The server did not confirm the channel.'), SNAPSHOT_TIMEOUT_MS)
      if (!this.send(transport, this.session && transport.resuming
        ? { type: 'resume', resume_token: this.session.token, generation: transport.wireGeneration }
        : channel === 'ECHO' ? { type: 'join_echo' } : { type: 'join', channel })) return
      transport.heartbeat = setInterval(() => {
        if (this.transport !== transport) return
        if (transport.pendingPings >= MAX_UNANSWERED_PINGS) {
          this.recover(transport, 'The server stopped responding.')
          return
        }
        transport.pendingPings++
        transport.pingTimes.set(transport.pingId, this.now())
        if (transport.pingTimes.size > MAX_UNANSWERED_PINGS) transport.pingTimes.delete(transport.pingTimes.keys().next().value!)
        this.send(transport, { type: 'ping', id: transport.pingId, sent_at_ms: BigInt(Date.now()) })
        transport.pingId = (transport.pingId + 1) % (MAX_BURST_INDEX + 1)
      }, PING_INTERVAL_MS)
    }
    socket.onmessage = event => {
      if (this.transport !== transport) return
      let message: ServerControl | undefined
      try {
        if (typeof event.data === 'string') {
          message = parseControl(event.data)
        } else if (event.data instanceof ArrayBuffer && transport.joined) {
          const media = decodeMedia(new Uint8Array(event.data))
          if (this.state.audio === 'ready') this.receiveMedia(media)
        } else {
          throw new ProtocolError()
        }
      } catch {
        this.failCurrent(transport, 'Invalid server message. Connect again.', 'invalid_server_message')
        return
      }
      transport.pendingPings = 0
      if (message) {
        try { this.handle(transport, message) } catch {
          this.failCurrent(transport, 'Invalid server state. Connect again.', 'invalid_audio_state')
        }
      }
    }
    socket.onerror = () => this.recover(transport, 'Connection interrupted.')
    socket.onclose = event => {
      if (this.transport !== transport) return
      if ([1002, 1008, 1009].includes(event.code)) this.failCurrent(transport, 'The server closed the session. Connect again.', 'terminal_close')
      else this.recover(transport, 'Connection interrupted.')
    }
  }

  private handle(transport: Transport, message: ServerControl) {
    if (message.type === 'error') {
      const error = ERROR_MESSAGES[message.code]
      if (!transport.joined) {
        void this.disconnect()
        this.publish({ error })
      } else {
        if (this.transmission) this.pttUp()
        this.publish({ error })
      }
      return
    }
    if (message.type === 'pong') {
      const sent = transport.pingTimes.get(message.id)
      if (sent !== undefined) { transport.pingTimes.delete(message.id); this.publish({ rttMs: Math.round(this.now() - sent) }) }
      return
    }
    if (message.type === 'resume_rejected') {
      if (transport.joined || !transport.resuming) throw new ProtocolError()
      this.stopTransmission()
      this.releaseRequired = this.held
      this.canceledRequests.clear()
      this.session = null
      this.receiver?.reset({ burstIndex: 0, nextSequence: 0 })
      this.lastReset = null
      this.resetNotice = 'Previous session is unavailable. Joined a new session; old audio was discarded. Release PTT before talking.'
      transport.resuming = false
      transport.wireGeneration = 1
      clearTimeout(transport.deadline)
      transport.deadline = setTimeout(() => this.recover(transport, 'The server did not confirm the new session.'), SNAPSHOT_TIMEOUT_MS)
      this.send(transport, transport.channel === 'ECHO' ? { type: 'join_echo' } : { type: 'join', channel: transport.channel })
      return
    }
    if (message.type === 'snapshot') {
      if (transport.joined || message.channel !== transport.channel || message.generation !== transport.wireGeneration
        || (!transport.resuming && message.floor?.owned)
        || (transport.resuming && (message.member_id !== this.session?.memberId || message.channel_incarnation_id !== this.session.incarnation))) {
        this.failCurrent(transport, 'Invalid server session. Connect again.', 'invalid_snapshot')
        return
      }
      transport.joined = true
      transport.revision = message.revision
      if (message.eligible_from_index > BigInt(MAX_BURST_INDEX)) throw new ProtocolError()
      if (!transport.resuming) {
        this.listenIndex = Number(message.eligible_from_index)
        this.listenSequence = 0
      }
      this.horizon = message.audio_policy.recovery_horizon_ms
      this.queueLimit = Math.max(60000, 3 * message.audio_policy.recovery_horizon_ms) / FRAME_DURATION_MS
      this.audio?.setQueueLimit(this.queueLimit)
      if (!transport.resuming && this.receiver && this.audio) {
        this.receiver = new ReceiveBuffer(this.audio, this.queueLimit)
        this.receiver.reset({ burstIndex: this.listenIndex, nextSequence: 0 })
      }
      this.session = { token: message.resume_token, memberId: message.member_id, incarnation: message.channel_incarnation_id, generation: message.generation }
      this.outageAt = null
      this.reconnectAttempt = 0
      clearTimeout(transport.deadline)
      this.publish({
        status: 'connected', participantCount: message.participant_count,
        remoteTransmitting: message.floor !== null && !message.floor.owned, error: this.resetNotice,
      })
      this.resetNotice = null
      this.listen()
      if (this.transport !== transport) return
      for (const requestId of this.canceledRequests) {
        if (!this.send(transport, { type: 'ptt_cancel', request_id: requestId })) return
      }
      const tx = this.transmission
      if (tx?.buffer) {
        tx.buffer.rewind()
        tx.endSent = false
        tx.progressAt = this.now()
        if (!message.floor?.owned || message.floor.burst_id !== tx.burstId) {
          tx.restartAfter = this.held && !this.releaseRequired
          this.finishCapture()
        } else if (!tx.stopping) {
          this.audio?.limitCapture(tx.requestId, null)
        }
        this.flushUplink()
      } else if (tx) {
        this.send(transport, { type: 'ptt_request', request_id: tx.requestId })
      } else if (message.floor?.owned) {
        // A released request whose grant was lost is still canceled by its original ID.
        if (!this.canceledRequests.size) throw new ProtocolError()
      }
      return
    }
    if (!transport.joined) {
      this.failCurrent(transport, 'Unexpected server state. Connect again.', 'unexpected_server_state')
      return
    }
    if (message.type === 'channel_state' && message.revision >= transport.revision) {
      if (this.state.remoteTransmitting && message.floor === null && !this.state.receiving) this.audio?.cue('free')
      transport.revision = message.revision
      this.publish({ participantCount: message.participant_count, remoteTransmitting: message.floor !== null && !message.floor.owned })
      return
    }
    this.handleAudio(transport, message)
  }

  private handleAudio(transport: Transport, message: ServerControl) {
    const tx = this.transmission
    switch (message.type) {
      case 'ptt_granted':
        if (this.canceledRequests.has(message.request_id)) {
          this.send(transport, { type: 'ptt_cancel', request_id: message.request_id })
          return
        }
        if (!tx || tx.requestId !== message.request_id || this.state.audio !== 'ready') throw new ProtocolError()
        if (tx.burstId) { if (tx.burstId !== message.burst_id) throw new ProtocolError(); return }
        clearTimeout(tx.timer)
        tx.burstId = message.burst_id
        tx.buffer = new UplinkBuffer(message.burst_id, this.now(), this.horizon)
        tx.progressAt = this.now()
        tx.timer = setTimeout(() => this.interruptCapture('Transmission reached the 60-second limit. Release PTT.'), MAX_BURST_DURATION_MS)
        this.publish({ ptt: 'transmitting', quality: 'good', pttError: false, grantMs: Math.round(this.now() - tx.requestedAt) })
        this.audio!.startCapture(tx.requestId)
        break
      case 'ptt_denied':
        if (tx?.requestId === message.request_id) {
          this.stopTransmission()
          this.audio?.cue('rejected')
          this.publish({ pttError: true, error: message.reason === 'channel_busy' ? 'Channel is busy. Release and try again.' : 'Transmission unavailable. Release and try again.' })
        }
        break
      case 'ptt_ended':
        if (tx?.burstId === message.burst_id) {
          if (message.state === 'sealed') this.stopTransmission(tx.restartAfter)
          else if (!tx.stopping) this.finishCapture()
        }
        break
      case 'uplink_ack':
        if (tx?.burstId === message.burst_id && tx.buffer?.acknowledge(message.next_sequence)) tx.progressAt = this.now()
        break
      case 'audio_rejected':
        if (message.reason === 'payload_mismatch') {
          this.failCurrent(transport, 'Conflicting audio payload. Connect again.', 'payload_mismatch')
        } else if (tx?.burstId === message.burst_id) {
          tx.buffer?.reject(message.first_sequence, message.next_sequence)
          if (message.reason === 'unknown_burst' || message.reason === 'invalid_range') {
            this.releaseRequired = this.held
            this.stopTransmission()
            this.publish({ error: 'Transmission could not be recovered. Release PTT and try again.' })
          } else {
            this.interruptCapture('Some audio could not be delivered. Release PTT and try again.')
            this.flushUplink()
          }
        }
        break
      case 'burst_started':
        this.receiver?.start(message.burst_id, message.burst_index)
        break
      case 'burst_gaps':
        this.receiver?.gaps(message.burst_id, message.burst_index, message.ranges)
        break
      case 'burst_sealed':
        this.receiver?.seal(message.burst_id, message.burst_index, message.final_next_sequence)
        break
      case 'listen_reset':
        if (!this.receiver) return
        if (message.next_sequence > MAX_BURST_FRAMES) throw new ProtocolError()
        {
          const cursor = { burstIndex: message.burst_index, nextSequence: message.next_sequence }
          if (this.lastReset && compareCursor(cursor, this.lastReset) === 0) return
          this.lastReset = cursor
          if (compareCursor(cursor, this.receiver.cursor) >= 0) this.receiver.reset(cursor)
          this.listen()
        }
        break
    }
  }

  private receiveMedia(media: MediaEnvelope) {
    this.receiver?.media(media)
  }

  private send(transport: Transport, message: ClientControl): boolean {
    if (this.transport !== transport) return false
    try {
      return this.sendRaw(transport, encodeControl(message))
    } catch {
      this.failCurrent(transport, 'Unable to send to the server. Connect again.', 'send_failed')
      return false
    }
  }

  private sendRaw(transport: Transport, raw: string | Uint8Array): boolean {
    if (this.transport !== transport) return false
    try {
      const size = typeof raw === 'string' ? new TextEncoder().encode(raw).length : raw.byteLength
      if (transport.socket.readyState !== WebSocket.OPEN || transport.socket.bufferedAmount + size > MAX_QUEUED_BYTES) {
        this.recover(transport, 'Connection is congested.')
        return false
      }
      transport.socket.send(typeof raw === 'string' ? raw : raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength) as ArrayBuffer)
      return true
    } catch {
      this.recover(transport, 'Unable to send to the server.')
      return false
    }
  }

  private failCurrent(transport: Transport, message: string, code: string) {
    if (this.transport === transport) this.fail(message, code)
  }

  private recover(transport: Transport, message: string) {
    if (this.transport !== transport) return
    void this.closeSocket(this.session === null)
    this.receiver?.interrupted()
    this.scheduleReconnect(message)
  }

  private scheduleReconnect(message: string) {
    this.outageAt ??= this.now()
    const tx = this.transmission
    if (tx?.buffer && !tx.stopping) this.audio?.limitCapture(tx.requestId, Math.max(0, this.horizon - (this.now() - this.outageAt)))
    this.publish({ status: 'reconnecting', participantCount: null, remoteTransmitting: false, error: message })
    if (this.reconnectTimer !== undefined) return
    const generation = this.generation
    const delay = RECONNECT_DELAYS_MS[Math.min(this.reconnectAttempt++, RECONNECT_DELAYS_MS.length - 1)]!
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined
      if (generation === this.generation && this.state.channel) this.open(this.state.channel)
    }, delay)
  }

  private fail(message: string, code: string) {
    logFailure(code)
    ++this.generation
    void this.closeTransport()
    this.publish({ status: 'error', participantCount: null, remoteTransmitting: false, error: message })
  }

  private closeTransport(): Promise<void> {
    this.cancelPing?.()
    clearTimeout(this.reconnectTimer)
    clearInterval(this.monitor)
    this.reconnectTimer = this.monitor = undefined
    this.session = null
    this.outageAt = null
    this.reconnectAttempt = 0
    this.held = this.releaseRequired = false
    this.lastReset = null
    this.resetNotice = null
    if (this.transmission) { clearTimeout(this.transmission.timer); this.transmission = null }
    const audio = this.audio
    this.audio = null
    audio?.dispose()
    this.receiver = null
    this.canceledRequests.clear()
    // The next status publish includes resource cleanup without an extra UI notification.
    this.state = Object.freeze({ ...this.state, audio: 'off', audioError: null, ptt: 'idle', receiving: false, pttAvailable: false,
      pttError: false, quality: 'good', rttMs: null, grantMs: null, lostFrames: 0, playbackBlocked: false })
    return this.closeSocket(true)
  }

  private closeSocket(intentional: boolean): Promise<void> {
    const transport = this.transport
    if (!transport) return this.closing
    this.transport = null
    clearTimeout(transport.deadline)
    clearInterval(transport.heartbeat)
    const socket = transport.socket
    socket.onopen = null
    socket.onmessage = null
    socket.onerror = null
    socket.onclose = null
    this.closing = new Promise<void>(resolve => {
      if (socket.readyState === WebSocket.CLOSED) { resolve(); return }
      const finish = () => {
        clearTimeout(deadline)
        socket.removeEventListener('close', finish)
        resolve()
      }
      const deadline = setTimeout(finish, DISCONNECT_TIMEOUT_MS)
      socket.addEventListener('close', finish, { once: true })
      try {
        if (intentional && socket.readyState === WebSocket.OPEN && socket.bufferedAmount < MAX_QUEUED_BYTES - MAX_MESSAGE_BYTES) {
          socket.send(encodeControl({ type: 'disconnect' }))
        }
      } catch { logFailure('disconnect_send_failed') }
      try { socket.close(intentional ? 1000 : 4000) } catch { logFailure('socket_close_failed'); finish() }
    })
    return this.closing
  }
}
