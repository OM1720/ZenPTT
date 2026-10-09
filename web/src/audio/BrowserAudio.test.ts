import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ZenPttClient } from '../ZenPttClient'
import { BrowserAudio } from './BrowserAudio'
import type { AudioReply } from './messages'

class Track {
  enabled = true
  readyState = 'live'
  onended: (() => void) | null = null
  stop() { this.readyState = 'ended' }
}
class Stream {
  track = new Track()
  getTracks() { return [this.track] }
  getAudioTracks() { return [this.track] }
}
class Context {
  state = 'running'
  sampleRate = 48000
  destination = {}
  onstatechange: (() => void) | null = null
  audioWorklet = { addModule: async () => {} }
  source = { connect: vi.fn(), disconnect: vi.fn() }
  resume = async () => {}
  close = async () => { this.state = 'closed' }
  createMediaStreamSource() { return this.source }
  createBuffer() { return { getChannelData: () => new Float32Array(4800) } }
  createBufferSource() { return { connect() {}, start() {}, stop() {}, disconnect() {} } }
  constructor() { contexts.push(this) }
}
class Worklet {
  onprocessorerror: (() => void) | null = null
  connect = vi.fn()
  disconnect = vi.fn()
  port = {
    onmessage: null as ((event: { data: AudioReply }) => void) | null,
    postMessage: vi.fn(), close: vi.fn(),
  }
  constructor() {
    worklets.push(this)
    queueMicrotask(() => this.reply({ type: 'ready' }))
  }
  reply(data: AudioReply) { this.port.onmessage?.({ data }) }
}
class Socket extends EventTarget {
  readyState = 0
  bufferedAmount = 0
  protocol = 'zenptt.v4'
  sent: (string | ArrayBuffer)[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  send(data: string | ArrayBuffer) { this.sent.push(data) }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')) }
  receive(data: object) { this.onmessage?.({ data: JSON.stringify(data) }) }
}

let contexts: Context[], worklets: Worklet[], streams: Stream[], sockets: Socket[]
let client: ZenPttClient
const microphone = vi.fn()
beforeEach(() => {
  vi.useFakeTimers()
  contexts = []; worklets = []; streams = []; sockets = []
  microphone.mockReset().mockImplementation(async () => {
    const stream = new Stream(); streams.push(stream); return stream
  })
  vi.stubGlobal('AudioContext', Context)
  vi.stubGlobal('AudioWorkletNode', Worklet)
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: microphone } })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true,
    arrayBuffer: async () => new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]).buffer })))
  client = new ZenPttClient('http://localhost/web/', () => {
    const socket = new Socket(); sockets.push(socket); return socket as unknown as WebSocket
  }, events => new BrowserAudio(events))
})
afterEach(async () => {
  await client.disconnect()
  expect(vi.getTimerCount()).toBe(0)
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function join() {
  void client.connect('ROOM')
  await vi.advanceTimersByTimeAsync(0)
  const socket = sockets.at(-1)!
  socket.readyState = 1; socket.onopen?.()
  socket.receive({ type: 'snapshot', channel: 'ROOM', member_id: '00000000-0000-0000-0000-000000000001',
    resume_token: 'opaque', generation: 1, channel_incarnation_id: '00000000-0000-0000-0000-000000000002',
    revision: 1, participant_count: 1, eligible_from_index: 0, next_burst_index: 0,
    audio_policy: { recovery_horizon_ms: 5000 }, floor: null })
  await client.enableAudio()
  expect(client.getState().audio).toBe('ready')
  return socket
}

it.each(['processor', 'microphone', 'context'] as const)('releases real adapter resources after %s failure and can reconnect', async failure => {
  const socket = await join()
  expect(client.pttDown()).toBe(true)
  const request = socket.sent.filter((value): value is string => typeof value === 'string')
    .map(value => JSON.parse(value) as { type: string; request_id: string }).find(value => value.type === 'ptt_request')!
  socket.receive({ type: 'ptt_granted', request_id: request.request_id,
    burst_id: '00000000-0000-0000-0000-000000000003', burst_index: 0, lease_remaining_ms: 5000 })
  await vi.advanceTimersByTimeAsync(100)
  const context = contexts[0]!, stream = streams[0]!, worklet = worklets[0]!
  expect(stream.track.enabled).toBe(true)
  if (failure === 'processor') worklet.onprocessorerror!()
  if (failure === 'microphone') { stream.track.readyState = 'ended'; stream.track.onended!() }
  if (failure === 'context') { context.state = 'suspended'; context.onstatechange!() }
  expect(client.getState()).toMatchObject({ status: 'error', audio: 'off', pttAvailable: false })
  expect(client.getState().error).toMatch(/Audio processing stopped|Microphone disconnected|Audio was interrupted/)
  expect(context.state).toBe('closed')
  expect(stream.track.readyState).toBe('ended')
  expect(context.source.disconnect).toHaveBeenCalledOnce()
  expect(worklet.disconnect).toHaveBeenCalledOnce()
  expect(worklet.port.close).toHaveBeenCalledOnce()
  const sent = socket.sent.length
  worklet.reply({ type: 'packet', requestId: request.request_id, packet: new Uint8Array([1]) })
  await vi.advanceTimersByTimeAsync(200)
  expect(socket.sent).toHaveLength(sent)
  await join()
  expect(client.pttDown()).toBe(true)
})

it('stops a microphone change that completes after disconnect without restoring audio', async () => {
  await join()
  const late = new Stream()
  let resolve!: (stream: Stream) => void
  microphone.mockReturnValueOnce(new Promise<Stream>(done => { resolve = done }))
  const changing = client.selectMicrophone('second')
  await client.disconnect()
  resolve(late)
  await changing
  expect(late.track.readyState).toBe('ended')
  expect(contexts[0]!.source.connect).toHaveBeenCalledTimes(1)
  expect(client.getState()).toMatchObject({ status: 'offline', audio: 'off' })
  await join()
  expect(client.getState().audio).toBe('ready')
})

it('release during the grant cue cancels capture and still requests a stop acknowledgement', async () => {
  const socket = await join()
  client.pttDown()
  const request = socket.sent.filter((value): value is string => typeof value === 'string')
    .map(value => JSON.parse(value) as { type: string; request_id: string }).find(value => value.type === 'ptt_request')!
  socket.receive({ type: 'ptt_granted', request_id: request.request_id,
    burst_id: '00000000-0000-0000-0000-000000000003', burst_index: 0, lease_remaining_ms: 5000 })
  client.pttUp()
  const worklet = worklets[0]!
  expect(worklet.port.postMessage).toHaveBeenCalledWith({ type: 'stop', requestId: request.request_id })
  worklet.reply({ type: 'stopped', requestId: request.request_id })
  await vi.advanceTimersByTimeAsync(101)
  expect(worklet.port.postMessage.mock.calls.some(([message]) => message.type === 'capture')).toBe(false)
  expect(streams[0]!.track.enabled).toBe(false)
  expect(client.getState().status).toBe('connected')
})
