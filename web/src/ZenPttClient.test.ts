import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ZenPttClient, websocketUrl } from './ZenPttClient'
import type { AudioEvents, ClientAudio } from './audio/messages'
import { decodeMedia, encodeMedia, UPLINK, DOWNLINK } from './protocol'

const snapshot = '{"type":"snapshot","channel":"ROOM1","member_id":"00000000-0000-0000-0000-000000000001","resume_token":"opaque","generation":1,"channel_incarnation_id":"00000000-0000-0000-0000-000000000002","revision":1,"participant_count":1,"eligible_from_index":0,"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":5000},"floor":null}'
const state = '{"type":"channel_state","revision":2,"participant_count":2,"next_burst_index":0,"floor":null}'

class FakeSocket extends EventTarget {
  readyState = 0
  bufferedAmount = 0
  binaryType = 'blob'
  protocol = 'zenptt.v4'
  sent: string[] = []
  binary: ArrayBuffer[] = []
  closeCodes: (number | undefined)[] = []
  closeImmediately = true
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  open() { this.readyState = 1; this.onopen?.() }
  receive(data: unknown) { this.onmessage?.({ data }) }
  send(raw: string | ArrayBuffer) { if (typeof raw === 'string') this.sent.push(raw); else this.binary.push(raw) }
  close(code?: number) {
    this.closeCodes.push(code)
    this.readyState = this.closeImmediately ? 3 : 2
    if (this.closeImmediately) this.dispatchEvent(new Event('close'))
  }
}

describe('session ownership', () => {
  let sockets: FakeSocket[]
  let client: ZenPttClient
  let audio: ClientAudio
  let audioEvents: AudioEvents
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    sockets = []
    audio = {
      changeMicrophone: vi.fn(), cue: vi.fn(),
      prepare: vi.fn().mockResolvedValue(undefined), startCapture: vi.fn(), stopCapture: vi.fn(),
      limitCapture: vi.fn(),
      startBurst: vi.fn(), play: vi.fn(), endBurst: vi.fn(), resetPlayback: vi.fn(),
      setQueueLimit: vi.fn(), dispose: vi.fn(),
    }
    client = new ZenPttClient('http://localhost:5173/web/', () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket as unknown as WebSocket
    }, events => { audioEvents = events; return audio })
  })
  afterEach(async () => {
    const closed = client.disconnect()
    await vi.advanceTimersByTimeAsync(1000)
    await closed
    expect(vi.getTimerCount()).toBe(0)
    vi.useRealTimers()
    vi.restoreAllMocks()
  })
  async function join() {
    client.connect('room1')
    await Promise.resolve()
    const socket = sockets[0]!
    socket.open()
    socket.receive(snapshot)
    return socket
  }

  it('derives the exact same-origin endpoint and rejects insecure remote pages', () => {
    expect(websocketUrl('https://example.test/web/?server=evil#x')).toBe('wss://example.test/ws')
    expect(websocketUrl('http://127.0.0.1:5173/web/')).toBe('ws://127.0.0.1:5173/ws')
    expect(() => websocketUrl('http://example.test/web/')).toThrow()
    expect(() => websocketUrl('file:///web/index.html')).toThrow()
  })
  it('exports bounded diagnostics without channels, tokens, IDs, or packet data', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    audioEvents.packet(id, new Uint8Array([222, 223, 224]))
    for (let i = 0; i < 300; i++) { await client.disconnect(); client.connect('PRIVATE.ROOM'); await Promise.resolve() }
    const report = client.diagnosticReport('test-build')
    expect(report.length).toBeLessThan(65536)
    for (const privateValue of ['ROOM1', 'PRIVATE.ROOM', 'opaque', burstId, id, '222']) expect(report).not.toContain(privateValue)
    expect(JSON.parse(report).events).toHaveLength(256)
  })
  it('probes without joining and cancels a pending Ping on disconnect', async () => {
    const result = client.ping()
    const probe = sockets[0]!
    probe.open()
    expect(probe.sent[0]).toContain('"type":"ping"')
    await vi.advanceTimersByTimeAsync(20)
    probe.receive('{"type":"pong","id":0,"sent_at_ms":0}')
    await expect(result).resolves.toBe(20)
    expect(client.getState().status).toBe('offline')
    const pending = client.ping()
    const rejected = expect(pending).rejects.toThrow('Ping failed')
    await client.disconnect()
    await rejected
  })
  it('joins once and stays connecting until the authoritative snapshot', async () => {
    client.connect('room1')
    client.connect('ROOM1')
    await Promise.resolve()
    expect(sockets).toHaveLength(1)
    sockets[0]!.open()
    expect(sockets[0]!.sent).toEqual(['{"type":"join","channel":"ROOM1"}'])
    expect(client.getState().status).toBe('connecting')
    sockets[0]!.receive(snapshot)
    client.connect('room1')
    expect(sockets).toHaveLength(1)
    expect(client.getState()).toMatchObject({ status: 'connected', participantCount: 1n, pttAvailable: false })
    expect(client.getState()).not.toHaveProperty('resume_token')
  })
  it('publishes counts and ignores older revisions', async () => {
    const socket = await join()
    socket.receive(state)
    socket.receive(state.replace('"revision":2', '"revision":1').replace('"participant_count":2', '"participant_count":1'))
    expect(client.getState().participantCount).toBe(2n)
  })
  it('keeps presence alive with application pings and bounds an unresponsive connection', async () => {
    const socket = await join()
    await vi.advanceTimersByTimeAsync(2500)
    expect(socket.sent.filter(raw => raw.includes('"ping"'))).toHaveLength(2)
    socket.receive('{"type":"pong","id":1,"sent_at_ms":0}')
    await vi.advanceTimersByTimeAsync(9500)
    expect(client.getState().status).toBe('connected')
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.getState().status).toBe('reconnecting')
  })
  it('sends an intentional disconnect and clears the count immediately', async () => {
    const socket = await join()
    await client.disconnect()
    expect(socket.sent.at(-1)).toBe('{"type":"disconnect"}')
    expect(socket.closeCodes).toEqual([1000])
    expect(client.getState()).toMatchObject({ status: 'offline', participantCount: null })
  })
  it('serializes switching and fences events from the old transport', async () => {
    const old = await join()
    const late = old.onmessage!
    old.closeImmediately = false
    client.connect('ROOM2')
    client.connect('ROOM3')
    expect(sockets).toHaveLength(1)
    late({ data: state })
    expect(client.getState().participantCount).toBeNull()
    await vi.advanceTimersByTimeAsync(1000)
    expect(sockets).toHaveLength(2)
    sockets[1]!.open()
    expect(sockets[1]!.sent).toEqual(['{"type":"join","channel":"ROOM3"}'])
  })
  it('cancels a queued connection when Disconnect is pressed immediately', async () => {
    client.connect('ROOM1')
    await client.disconnect()
    expect(sockets).toHaveLength(0)
    expect(client.getState().status).toBe('offline')
  })
  it('disconnects a handshake in progress', async () => {
    client.connect('ROOM1')
    await Promise.resolve()
    await client.disconnect()
    sockets[0]!.open()
    expect(sockets[0]!.sent).toEqual([])
    expect(client.getState().status).toBe('offline')
  })
  it('preserves the current session after invalid channel input', async () => {
    await join()
    expect(() => client.connect('ROOM..2')).toThrow()
    expect(client.getState()).toMatchObject({ status: 'connected', channel: 'ROOM1' })
    expect(sockets).toHaveLength(1)
  })
  it('does not send a PTT request, audio, or listen command before audio is enabled', async () => {
    const socket = await join()
    expect(client.pttDown()).toBe(false)
    client.pttUp()
    expect(socket.sent).toEqual(['{"type":"join","channel":"ROOM1"}'])
  })
  it('uses the isolated ECHO command', async () => {
    client.connect('echo')
    await Promise.resolve()
    sockets[0]!.open()
    expect(sockets[0]!.sent).toEqual(['{"type":"join_echo"}'])
    sockets[0]!.receive(snapshot.replace('ROOM1', 'ECHO'))
    expect(client.getState().status).toBe('connected')
  })
  it.each(['bad JSON', state, snapshot.replace('ROOM1', 'OTHER')])('rejects invalid pre-snapshot data: %s', async raw => {
    client.connect('ROOM1')
    await Promise.resolve()
    sockets[0]!.open()
    sockets[0]!.receive(raw)
    expect(client.getState().status).toBe('error')
  })
  it('rejects malformed binary and duplicate snapshots', async () => {
    const socket = await join()
    socket.receive(new Uint8Array([4, 2]).buffer)
    expect(client.getState().status).toBe('error')
    client.connect('ROOM1')
    await Promise.resolve()
    sockets[1]!.open()
    sockets[1]!.receive(snapshot)
    sockets[1]!.receive(snapshot)
    expect(client.getState().status).toBe('error')
  })
  it('handles rejected joins without displaying arbitrary server text', async () => {
    client.connect('ROOM1')
    await Promise.resolve()
    sockets[0]!.open()
    sockets[0]!.receive('{"type":"error","code":"channel_full","message":"SECRET"}')
    expect(client.getState()).toMatchObject({ status: 'offline', error: 'Channel is full' })
  })
  it('keeps a joined connection after a valid operation error', async () => {
    const socket = await join()
    socket.receive('{"type":"error","code":"server_busy","message":"Server is busy"}')
    expect(client.getState()).toMatchObject({ status: 'connected', error: 'Server is busy' })
  })
  it('bounds the open and snapshot deadlines', async () => {
    client.connect('ROOM1')
    await vi.advanceTimersByTimeAsync(10000)
    expect(client.getState().status).toBe('reconnecting')
    await client.disconnect()
    client.connect('ROOM1')
    await Promise.resolve()
    sockets[1]!.open()
    await vi.advanceTimersByTimeAsync(5000)
    expect(client.getState().error).toBe('The server did not confirm the channel.')
  })
  it('rejects a mismatched subprotocol', async () => {
    client.connect('ROOM1')
    await Promise.resolve()
    sockets[0]!.protocol = ''
    sockets[0]!.open()
    expect(client.getState().status).toBe('error')
    expect(sockets[0]!.sent).toEqual(['{"type":"disconnect"}'])
  })
  it('bounds outgoing transport pressure', async () => {
    const socket = await join()
    socket.bufferedAmount = 8192
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.getState().status).toBe('reconnecting')
    expect(socket.sent).toHaveLength(1)
  })
  it('isolates subscribers and removes unsubscribed listeners', async () => {
    client.subscribe(() => { throw new Error('listener failed') })
    const listener = vi.fn()
    const unsubscribe = client.subscribe(listener)
    await join()
    expect(listener).toHaveBeenCalledTimes(2)
    unsubscribe()
    await client.disconnect()
    expect(listener).toHaveBeenCalledTimes(2)
  })

  const burstId = '00000000-0000-0000-0000-000000000003'
  function grant(socket: FakeSocket, requestId: string) {
    socket.receive(JSON.stringify({ type: 'ptt_granted', request_id: requestId, burst_id: burstId, burst_index: 0, lease_remaining_ms: 5000 }))
  }
  function request(socket: FakeSocket): string {
    expect(client.pttDown()).toBe(true)
    return (JSON.parse(socket.sent.at(-1)!) as { request_id: string }).request_id
  }
  it('never captures or sends microphone audio before a matching grant', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    audioEvents.packet(id, new Uint8Array([1, 2]))
    expect(audio.startCapture).not.toHaveBeenCalled()
    expect(socket.binary).toHaveLength(0)
    grant(socket, id)
    expect(audio.startCapture).toHaveBeenCalledExactlyOnceWith(id)
    audioEvents.packet(id, new Uint8Array([1, 2]))
    expect(socket.binary).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(40)
    expect(decodeMedia(new Uint8Array(socket.binary[0]!), UPLINK)).toMatchObject({ burstId, firstSequence: 0 })
  })
  it('groups three fresh frames and flushes a smaller group after 40 ms from its first frame', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    audioEvents.packet(id, new Uint8Array([1]))
    await vi.advanceTimersByTimeAsync(20)
    audioEvents.packet(id, new Uint8Array([2]))
    expect(socket.binary).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(20)
    expect(decodeMedia(new Uint8Array(socket.binary[0]!), UPLINK).packets).toEqual([
      new Uint8Array([1]), new Uint8Array([2]),
    ])
    audioEvents.packet(id, new Uint8Array([3]))
    audioEvents.packet(id, new Uint8Array([4]))
    audioEvents.packet(id, new Uint8Array([5]))
    expect(decodeMedia(new Uint8Array(socket.binary[1]!), UPLINK)).toEqual({
      burstId, firstSequence: 2, packets: [new Uint8Array([3]), new Uint8Array([4]), new Uint8Array([5])],
    })
  })
  it('ends at the flushed exclusive watermark and permits a new request', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    audioEvents.packet(id, new Uint8Array([1]))
    client.pttUp()
    expect(audio.stopCapture).toHaveBeenCalledWith(id)
    expect(socket.sent.some(raw => raw.includes('burst_end'))).toBe(false)
    audioEvents.packet(id, new Uint8Array([2]))
    audioEvents.captureEnded(id)
    expect(decodeMedia(new Uint8Array(socket.binary[0]!), UPLINK).packets).toEqual([
      new Uint8Array([1]), new Uint8Array([2]),
    ])
    expect(JSON.parse(socket.sent.at(-1)!)).toEqual({ type: 'burst_end', burst_id: burstId, final_next_sequence: 2 })
    socket.receive(JSON.stringify({ type: 'ptt_ended', burst_id: burstId, burst_index: 0, state: 'sealed', final_next_sequence: 2, reason: 'complete' }))
    expect(client.getState().ptt).toBe('idle')
    expect(request(socket)).not.toBe(id)
  })
  it('cancels a pending press and rejects late grant capture', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    client.pttUp()
    grant(socket, id)
    expect(audio.startCapture).not.toHaveBeenCalled()
    expect(socket.sent.at(-1)).toBe(JSON.stringify({ type: 'ptt_cancel', request_id: id }))
    expect(client.getState().ptt).toBe('idle')
  })
  it('reports a sealed shorter prefix as interrupted even when the server says complete', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    for (const value of [1, 2, 3]) audioEvents.packet(id, new Uint8Array([value]))
    audioEvents.packet(id, new Uint8Array([4]))
    client.pttUp()
    audioEvents.captureEnded(id)
    socket.receive(JSON.stringify({ type: 'ptt_ended', burst_id: burstId, burst_index: 0,
      state: 'sealed', final_next_sequence: 3, reason: 'complete' }))
    expect(client.getState().ptt).toBe('idle')
    expect(client.getState().error).toContain('before all captured audio')
    expect(audio.cue).toHaveBeenCalledWith('interrupted')
  })
  it('handles refusal and request timeout without capture', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    socket.receive(JSON.stringify({ type: 'ptt_denied', request_id: id, reason: 'channel_busy' }))
    expect(client.getState().error).toContain('busy')
    client.pttUp()
    request(socket)
    await vi.advanceTimersByTimeAsync(5000)
    expect(client.getState().error).toContain('timed out')
    expect(audio.startCapture).not.toHaveBeenCalled()
  })
  it('waits for user audio preparation before registering listen', async () => {
    const socket = await join()
    expect(socket.sent).toHaveLength(1)
    await client.enableAudio()
    expect(socket.sent.at(-1)).toBe('{"type":"listen","burst_index":0,"next_sequence":0}')
    socket.receive(JSON.stringify({ type: 'burst_started', burst_id: burstId, burst_index: 0 }))
    const bytes = encodeMedia(DOWNLINK, { burstId, firstSequence: 0, packets: [new Uint8Array([1])] })
    socket.receive(bytes.buffer)
    expect(audio.play).toHaveBeenCalledExactlyOnceWith(new Uint8Array([1]), { burstIndex: 0, nextSequence: 1 })
    expect(audio.endBurst).not.toHaveBeenCalled()
    socket.receive(JSON.stringify({ type: 'burst_sealed', burst_id: burstId, burst_index: 0, final_next_sequence: 1, reason: 'complete' }))
    expect(audio.endBurst).toHaveBeenCalledOnce()
  })
  it.each(['NotAllowedError', 'NotFoundError', 'NotReadableError'])('keeps the connection usable after %s', async name => {
    await join()
    vi.mocked(audio.prepare).mockRejectedValueOnce(new DOMException('private detail', name))
    await client.enableAudio()
    expect(client.getState()).toMatchObject({ status: 'connected', audio: 'off', pttAvailable: false })
    expect(client.getState().audioError).toBeTypeOf('string')
    expect(client.getState().audioError).not.toContain('private detail')
    expect(audio.dispose).toHaveBeenCalledOnce()
  })
  it('prepares audio in the Connect gesture but waits for admission before listening', async () => {
    client.connect('ROOM1')
    const preparing = client.enableAudio()
    expect(audio.prepare).toHaveBeenCalledOnce()
    expect(sockets).toHaveLength(0)
    await preparing
    expect(client.getState()).toMatchObject({ status: 'connecting', audio: 'ready', pttAvailable: false })
    const socket = sockets[0]!
    socket.open()
    expect(socket.sent).toEqual(['{"type":"join","channel":"ROOM1"}'])
    socket.receive(snapshot)
    expect(socket.sent.at(-1)).toContain('"type":"listen"')
    expect(client.getState().pttAvailable).toBe(true)
    expect(audio.startCapture).not.toHaveBeenCalled()
    expect(socket.binary).toHaveLength(0)
  })
  it('keeps an early preparation failure visible after admission and allows retry', async () => {
    client.connect('ROOM1')
    vi.mocked(audio.prepare).mockRejectedValueOnce(new DOMException('private detail', 'NotAllowedError'))
    await client.enableAudio()
    const error = client.getState().audioError
    expect(error).toContain('Microphone permission denied')
    const socket = sockets[0]!
    socket.open()
    socket.receive(snapshot)
    expect(client.getState()).toMatchObject({ status: 'connected', audioError: error, pttAvailable: false })
    const retry = client.enableAudio()
    expect(client.getState().audioError).toBeNull()
    await retry
    expect(client.getState()).toMatchObject({ audio: 'ready', pttAvailable: true })
    expect(socket.sent.filter(raw => raw.includes('"type":"join"'))).toHaveLength(1)
    expect(audio.startCapture).not.toHaveBeenCalled()
  })
  it('does not queue a transmission while microphone permission is pending', async () => {
    await join()
    let complete!: () => void
    vi.mocked(audio.prepare).mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const preparing = client.enableAudio()
    expect(client.pttDown()).toBe(false)
    complete()
    await preparing
    expect(sockets[0]!.sent.some(raw => raw.includes('"type":"ptt_request"'))).toBe(false)
    expect(audio.startCapture).not.toHaveBeenCalled()
    client.pttUp()
    expect(client.pttDown()).toBe(true)
  })
  it('disposes preparation that completes after explicit disconnect', async () => {
    client.connect('ROOM1')
    let complete!: () => void
    vi.mocked(audio.prepare).mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const preparing = client.enableAudio()
    await client.disconnect()
    complete()
    await preparing
    expect(client.getState()).toMatchObject({ status: 'offline', audio: 'off' })
    expect(audio.dispose).toHaveBeenCalled()
  })
  it('preserves audio custody during a short transport failure', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    socket.onerror?.()
    audioEvents.packet(id, new Uint8Array([1]))
    expect(socket.binary).toHaveLength(0)
    expect(audio.dispose).not.toHaveBeenCalled()
    expect(client.getState()).toMatchObject({ status: 'reconnecting', audio: 'ready', ptt: 'transmitting' })
  })
  it('fails safely when the worklet never confirms stop', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    client.pttUp()
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.getState().status).toBe('error')
    expect(audio.dispose).toHaveBeenCalledOnce()
  })

  it('accepts a delayed stop once and ignores stale and duplicate confirmations', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    audioEvents.packet(id, new Uint8Array([1]))
    client.pttUp()
    audioEvents.captureEnded('stale-request')
    await vi.advanceTimersByTimeAsync(900)
    expect(socket.sent.filter(raw => raw.includes('burst_end'))).toHaveLength(0)
    audioEvents.captureEnded(id)
    audioEvents.captureEnded(id)
    audioEvents.packet(id, new Uint8Array([2]))
    await vi.advanceTimersByTimeAsync(200)
    expect(socket.sent.filter(raw => raw.includes('burst_end'))).toHaveLength(1)
    expect(JSON.parse(socket.sent.find(raw => raw.includes('burst_end'))!)).toMatchObject({ final_next_sequence: 1 })
    expect(client.getState().status).toBe('connected')
  })

  it('disposes capture on disconnect and ignores a late stop from the old audio session', async () => {
    const socket = await join()
    await client.enableAudio()
    const id = request(socket)
    grant(socket, id)
    client.pttUp()
    const previous = audioEvents
    await client.disconnect()
    const count = socket.sent.length
    previous.captureEnded(id)
    previous.packet(id, new Uint8Array([1]))
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.getState()).toMatchObject({ status: 'offline', audio: 'off' })
    expect(socket.sent).toHaveLength(count)
  })

  async function resume(old: FakeSocket, owned = false) {
    old.onerror?.()
    await vi.advanceTimersByTimeAsync(1)
    const socket = sockets.at(-1)!
    socket.open()
    expect(JSON.parse(socket.sent[0]!)).toMatchObject({ type: 'resume', generation: sockets.length })
    socket.receive(JSON.stringify({ ...JSON.parse(snapshot), generation: sockets.length,
      floor: owned ? { burst_id: burstId, burst_index: 0, owned: true } : null }))
    return socket
  }
  it('resumes a granted burst with exact unacknowledged packets and one capture', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    audioEvents.packet(id, new Uint8Array([1, 2]))
    audioEvents.packet(id, new Uint8Array([3, 4]))
    old.receive(JSON.stringify({ type: 'uplink_ack', burst_id: burstId, next_sequence: 1 }))
    const current = await resume(old, true)
    expect(old.closeCodes).toEqual([4000])
    expect(decodeMedia(new Uint8Array(current.binary[0]!), UPLINK)).toEqual({ burstId, firstSequence: 1, packets: [new Uint8Array([3, 4])] })
    await vi.advanceTimersByTimeAsync(40)
    expect(current.binary).toHaveLength(1)
    expect(audio.startCapture).toHaveBeenCalledOnce()
    expect(audio.dispose).not.toHaveBeenCalled()
    expect(client.getState().status).toBe('connected')
  })
  it('stores release during an outage and sends the immutable tail after resume', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    old.onerror?.()
    audioEvents.packet(id, new Uint8Array([8]))
    client.pttUp()
    audioEvents.captureEnded(id)
    await vi.advanceTimersByTimeAsync(1)
    const current = sockets.at(-1)!
    current.open()
    current.receive(snapshot.replace('"generation":1', '"generation":2'))
    expect(decodeMedia(new Uint8Array(current.binary[0]!), UPLINK).packets).toEqual([new Uint8Array([8])])
    expect(current.sent.at(-1)).toBe(JSON.stringify({ type: 'burst_end', burst_id: burstId, final_next_sequence: 1 }))
    expect(audio.startCapture).toHaveBeenCalledOnce()
  })
  it('keeps the capture deadline when the resumed listen immediately congests', async () => {
    const old = await join()
    await client.enableAudio()
    grant(old, request(old))
    old.onerror?.()
    await vi.advanceTimersByTimeAsync(1)
    const current = sockets.at(-1)!
    current.open()
    current.bufferedAmount = 8192
    current.receive(JSON.stringify({ ...JSON.parse(snapshot), generation: 2,
      floor: { burst_id: burstId, burst_index: 0, owned: true } }))
    expect(client.getState().status).toBe('reconnecting')
    expect(vi.mocked(audio.limitCapture).mock.calls.every(([, duration]) => duration !== null)).toBe(true)
  })
  it('replays a request lost before grant without starting capture prematurely', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    const current = await resume(old)
    expect(current.sent.at(-1)).toBe(JSON.stringify({ type: 'ptt_request', request_id: id }))
    expect(audio.startCapture).not.toHaveBeenCalled()
    grant(current, id)
    expect(audio.startCapture).toHaveBeenCalledExactlyOnceWith(id)
  })
  it('fences stale sockets and registers the actual playback cursor', async () => {
    const old = await join()
    await client.enableAudio()
    old.receive(JSON.stringify({ type: 'burst_started', burst_id: burstId, burst_index: 0 }))
    old.receive(encodeMedia(DOWNLINK, { burstId, firstSequence: 0, packets: [new Uint8Array([1]), new Uint8Array([2])] }).buffer)
    audioEvents.played({ burstIndex: 0, nextSequence: 1 })
    const staleMessage = old.onmessage!
    const current = await resume(old)
    expect(current.sent).toContain('{"type":"listen","burst_index":0,"next_sequence":1}')
    staleMessage({ data: 'invalid' })
    expect(client.getState().status).toBe('connected')
    current.receive(JSON.stringify({ type: 'burst_started', burst_id: burstId, burst_index: 0 }))
    current.receive(encodeMedia(DOWNLINK, { burstId, firstSequence: 1, packets: [new Uint8Array([2]), new Uint8Array([3])] }).buffer)
    expect(audio.play).toHaveBeenCalledTimes(3)
  })
  it('fresh-joins after rejected resume and discards the old held transmission', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    old.onerror?.()
    await vi.advanceTimersByTimeAsync(1)
    const current = sockets.at(-1)!
    current.open()
    current.receive('{"type":"resume_rejected","reason":"invalid_token"}')
    expect(current.sent.at(-1)).toBe('{"type":"join","channel":"ROOM1"}')
    current.receive(snapshot)
    audioEvents.packet(id, new Uint8Array([1]))
    expect(current.binary).toHaveLength(0)
    expect(client.pttDown()).toBe(false)
    client.pttUp()
    expect(client.pttDown()).toBe(true)
    expect(client.getState().audio).toBe('ready')
  })
  it('stops capture at H and expires the session after the history window', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    old.onerror?.()
    await vi.advanceTimersByTimeAsync(5000)
    expect(audio.stopCapture).toHaveBeenCalledWith(id)
    audioEvents.captureEnded(id)
    await vi.advanceTimersByTimeAsync(10000)
    expect(client.getState()).toMatchObject({ status: 'error', audio: 'off', ptt: 'idle' })
    expect(client.getState().error).toContain('Recovery time expired')
    expect(audio.dispose).toHaveBeenCalledOnce()
  })
  it('recovers on missing cumulative progress but not duplicate ACK activity', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    audioEvents.packet(id, new Uint8Array([1]))
    await vi.advanceTimersByTimeAsync(2000)
    old.receive(JSON.stringify({ type: 'uplink_ack', burst_id: burstId, next_sequence: 0 }))
    await vi.advanceTimersByTimeAsync(1000)
    expect(client.getState().status).toBe('reconnecting')
  })
  it('ends terminal payload conflicts without reconnecting', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    old.receive(JSON.stringify({ type: 'audio_rejected', burst_id: burstId, first_sequence: 0, next_sequence: 1, reason: 'payload_mismatch' }))
    await vi.advanceTimersByTimeAsync(20000)
    expect(sockets).toHaveLength(1)
    expect(client.getState()).toMatchObject({ status: 'error', audio: 'off' })
  })
  it('finishes a recovered tail before requesting a new floor while held', async () => {
    const old = await join()
    await client.enableAudio()
    const id = request(old)
    grant(old, id)
    audioEvents.packet(id, new Uint8Array([1]))
    const current = await resume(old, false)
    expect(audio.stopCapture).toHaveBeenCalledWith(id)
    expect(current.sent.filter(raw => raw.includes('ptt_request'))).toHaveLength(0)
    audioEvents.captureEnded(id)
    current.receive(JSON.stringify({ type: 'ptt_ended', burst_id: burstId, burst_index: 0, state: 'sealed', final_next_sequence: 1, reason: 'complete' }))
    expect(current.sent.filter(raw => raw.includes('ptt_request'))).toHaveLength(1)
    expect(client.getState().ptt).toBe('requesting')
  })
})
