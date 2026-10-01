// Owns browser microphone permission, AudioContext, worklet wiring, and local cues.
import workletUrl from './processor.ts?worker&url'
import wasmUrl from '../generated/opus.wasm?url'
import type { AudioCommand, AudioEvents, AudioReply, ClientAudio } from './messages'
import type { PlaybackCursor } from '../recovery'
import { MAX_BURST_FRAMES } from '../mediaLimits'

export function audioErrorMessage(error: unknown): string {
  const name = error instanceof DOMException ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'Microphone permission denied. Allow access in your browser and try again.'
  if (name === 'NotFoundError') return 'No microphone found. Connect a microphone and try again.'
  if (name === 'OverconstrainedError') return 'Selected microphone is unavailable. Choose System default in Settings.'
  if (name === 'NotReadableError') return 'Microphone is unavailable or in use. Check the device and try again.'
  return 'Audio could not start. Check your microphone and try again.'
}

export class BrowserAudio implements ClientAudio {
  private context: AudioContext | null = null
  private stream: MediaStream | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private node: AudioWorkletNode | null = null
  private disposed = false
  private pendingFrames = 0
  private limit = MAX_BURST_FRAMES
  private playbackEpoch = 0
  private captureTimer?: ReturnType<typeof setTimeout>
  private captureRequest: string | null = null
  private captureLimitAt = Infinity
  private microphoneChange = 0
  private indicator: AudioBufferSourceNode | null = null

  constructor(private readonly events: AudioEvents) {}

  private constraints(deviceId: string): MediaStreamConstraints {
    return { audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true,
      ...(deviceId ? { deviceId: { exact: deviceId } } : {}) } }
  }

  async prepare(deviceId = ''): Promise<void> {
    // Both calls happen directly in the user's gesture, before the first await.
    const context = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' })
    this.context = context
    const resume = context.resume()
    const microphone = navigator.mediaDevices.getUserMedia(this.constraints(deviceId)).then(stream => {
      stream.getAudioTracks().forEach(track => { track.enabled = false })
      if (this.disposed) stream.getTracks().forEach(track => track.stop())
      else this.stream = stream
      return stream
    })
    try {
      const [stream, module] = await Promise.all([
        microphone,
        fetch(wasmUrl).then(response => {
          if (!response.ok) throw new Error('Codec unavailable')
          return response.arrayBuffer()
        }).then(bytes => WebAssembly.compile(bytes)),
        resume,
        context.audioWorklet.addModule(workletUrl),
      ])
      if (this.disposed) return
      if (context.state !== 'running' || context.sampleRate !== 48000) throw new Error('Audio context unavailable')
      const node = new AudioWorkletNode(context, 'zenptt-audio', {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
        channelCount: 1, channelCountMode: 'explicit', processorOptions: { module },
      })
      this.node = node
      await new Promise<void>((resolve, reject) => {
        const deadline = setTimeout(() => reject(new Error('Audio worklet timed out')), 5000)
        node.onprocessorerror = () => {
          clearTimeout(deadline)
          reject(new Error('Audio processor failed'))
          if (!this.disposed) this.events.error('Audio processing stopped. Reconnect to the channel.')
        }
        node.port.onmessage = (event: MessageEvent<AudioReply>) => {
          if (this.disposed) return
          const message = event.data
          if (message.type === 'ready') { clearTimeout(deadline); resolve() }
          else if (message.type === 'packet') this.events.packet(message.requestId, message.packet)
          else if (message.type === 'stopped') this.events.captureEnded(message.requestId)
          else if (message.type === 'capture_expired') this.events.captureExpired(message.requestId)
          else if (message.type === 'quality') this.events.quality(message.lostFrames, message.blocked)
          else if (message.type === 'played' && message.epoch === this.playbackEpoch) {
            if (message.frame) this.pendingFrames = Math.max(0, this.pendingFrames - 1)
            this.events.played(message.cursor)
          }
          else if (message.type === 'playback') this.events.playback(message.active)
          else if (message.type === 'error') {
            clearTimeout(deadline)
            reject(new Error('Audio processor failed'))
            this.events.error('Audio processing stopped. Reconnect to the channel.')
          }
        }
      })
      if (this.disposed) return
      this.source = context.createMediaStreamSource(stream)
      this.source.connect(node)
      node.connect(context.destination)
      for (const track of stream.getAudioTracks()) {
        track.onended = () => { if (!this.disposed) this.events.error('Microphone disconnected. Connect it and reconnect to the channel.') }
      }
      context.onstatechange = () => {
        if (!this.disposed && context.state !== 'running') this.events.error('Audio was interrupted. Reconnect to the channel.')
      }
      this.setQueueLimit(this.limit)
    } catch (error) {
      this.dispose()
      throw error
    }
  }

  private post(command: AudioCommand) { if (!this.disposed) this.node?.port.postMessage(command) }
  async changeMicrophone(deviceId: string): Promise<void> {
    if (!this.context || !this.node || this.disposed) return
    const generation = ++this.microphoneChange
    const stream = await navigator.mediaDevices.getUserMedia(this.constraints(deviceId))
    stream.getAudioTracks().forEach(track => { track.enabled = false })
    if (this.disposed || generation !== this.microphoneChange) { stream.getTracks().forEach(track => track.stop()); return }
    let source: MediaStreamAudioSourceNode
    try { source = this.context.createMediaStreamSource(stream); source.connect(this.node) }
    catch (error) { stream.getTracks().forEach(track => track.stop()); throw error }
    this.stream?.getTracks().forEach(track => { track.onended = null; track.stop() })
    this.source?.disconnect()
    this.stream = stream
    this.source = source
    stream.getAudioTracks().forEach(track => {
      track.onended = () => { if (!this.disposed) this.events.error('Microphone disconnected. Connect it and reconnect to the channel.') }
    })
  }

  cue(kind: 'free' | 'rejected' | 'interrupted' | 'grant') {
    const context = this.context
    if (this.disposed || !context || context.state !== 'running') return
    try {
      this.indicator?.stop()
      const duration = kind === 'free' ? 0.04 : kind === 'rejected' ? 0.19 : kind === 'grant' ? 0.1 : 0.22
      const buffer = context.createBuffer(1, Math.round(context.sampleRate * duration), context.sampleRate)
      const pcm = buffer.getChannelData(0)
      for (let i = 0; i < pcm.length; i++) {
        const t = i / context.sampleRate
        const frequency = kind === 'free' ? 680 : kind === 'rejected' ? 360 : kind === 'grant' ? 1200 : 700 - 175 * t / duration
        const envelope = Math.min(1, t / 0.004, (duration - t) / 0.004)
        pcm[i] = kind === 'rejected' && t >= 0.07 && t < 0.12 ? 0
          : Math.sin(2 * Math.PI * frequency * t) * (kind === 'free' ? 0.22 * Math.exp(-t * 90) : 0.25) * envelope
      }
      const source = context.createBufferSource()
      source.buffer = buffer
      source.connect(context.destination)
      source.onended = () => { source.disconnect(); if (this.indicator === source) this.indicator = null }
      this.indicator = source
      source.start()
    } catch { /* Optional local feedback must never break the communication path. */ }
  }

  startCapture(requestId: string) {
    this.captureRequest = requestId
    this.captureLimitAt = Infinity
    this.cue('grant')
    // Keep the grant cue out of the microphone capture, as on Android.
    this.captureTimer = setTimeout(() => {
      if (this.disposed || this.captureRequest !== requestId) return
      this.stream?.getAudioTracks().forEach(track => { track.enabled = true })
      this.post({ type: 'capture', requestId })
      if (Number.isFinite(this.captureLimitAt)) this.post({ type: 'capture_limit', requestId, milliseconds: Math.max(0, this.captureLimitAt - performance.now()) })
    }, 100)
  }
  stopCapture(requestId: string) {
    if (this.captureRequest === requestId) {
      this.captureRequest = null
      clearTimeout(this.captureTimer)
      this.stream?.getAudioTracks().forEach(track => { track.enabled = false })
    }
    this.post({ type: 'stop', requestId })
  }
  limitCapture(requestId: string, milliseconds: number | null) {
    if (this.captureRequest === requestId) this.captureLimitAt = milliseconds === null ? Infinity : Math.min(this.captureLimitAt, performance.now() + milliseconds)
    this.post({ type: 'capture_limit', requestId, milliseconds })
  }
  startBurst(burstId: string) { this.post({ type: 'start', burstId }) }
  play(packet: Uint8Array | null, cursor: PlaybackCursor) {
    if (++this.pendingFrames > this.limit) {
      this.events.error('Incoming audio queue is full. Disconnect and connect again.')
      return
    }
    this.post({ type: 'frame', packet, cursor, epoch: this.playbackEpoch })
  }
  endBurst(cursor: PlaybackCursor) { this.post({ type: 'end', cursor, epoch: this.playbackEpoch }) }
  resetPlayback() { this.pendingFrames = 0; this.post({ type: 'reset', epoch: ++this.playbackEpoch }) }
  setQueueLimit(frames: number) { this.limit = frames; this.post({ type: 'limit', frames }) }
  dispose() {
    if (this.disposed) return
    this.disposed = true
    clearTimeout(this.captureTimer)
    this.indicator?.stop()
    this.stream?.getTracks().forEach(track => { track.onended = null; track.stop() })
    this.source?.disconnect()
    this.node?.disconnect()
    this.node?.port.close()
    if (this.context) {
      this.context.onstatechange = null
      void this.context.close().catch(() => {})
    }
    this.stream = this.source = this.node = this.context = null
  }
}
