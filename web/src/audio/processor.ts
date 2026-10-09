// Runs Opus capture and ordered playback inside the 48 kHz audio worklet.
import { OpusCodec } from './OpusCodec'
import { Resampler } from './Resampler'
import type { AudioCommand, AudioReply } from './messages'
import type { PlaybackCursor } from '../recovery'
import { MAX_BURST_DURATION_MS, MAX_BURST_FRAMES } from '../mediaLimits'

declare const sampleRate: number
declare const currentFrame: number
declare class AudioWorkletProcessor {
  readonly port: MessagePort
}
declare function registerProcessor(name: string, processor: new (options: { processorOptions: { module: WebAssembly.Module } }) => AudioWorkletProcessor): void

interface QueuedFrame { pcm: Float32Array | null; burstId: string; cursor: PlaybackCursor; epoch: number; lostFrames: number }

export const PLAYBACK_START_DELAY_MS = 150

class ZenAudioProcessor extends AudioWorkletProcessor {
  private readonly codec: OpusCodec
  private resampler = new Resampler(sampleRate, 16000)
  private captureId: string | null = null
  private captureCount = 0
  private readonly captureFrame = new Float32Array(320)
  private captureOffset = 0
  private captureDeadline = Infinity
  private captureAbsoluteDeadline = Infinity
  private burstId = ''
  private losses = 0
  private lostFrames = 0
  private lastQuality = ''
  private queue: QueuedFrame[] = []
  private offset = 0
  private limit = MAX_BURST_FRAMES
  private queuedFrames = 0
  private playingBurst = ''
  private startAt = 0
  private active = false
  private failed = false

  constructor(options: { processorOptions: { module: WebAssembly.Module } }) {
    super()
    this.codec = new OpusCodec(options.processorOptions.module)
    if (sampleRate !== 48000) throw new Error('Audio context must run at 48 kHz')
    this.port.onmessage = (event: MessageEvent<AudioCommand>) => {
      if (this.failed) return
      try { this.command(event.data) } catch { this.fail() }
    }
    this.reply({ type: 'ready' })
  }

  private reply(message: AudioReply) {
    this.port.postMessage({ renderFrame: currentFrame, queuedFrames: this.queuedFrames, ...message })
  }
  private fail() {
    this.failed = true
    this.captureId = null
    this.queue = []
    this.reply({ type: 'error' })
  }
  private command(message: AudioCommand) {
    switch (message.type) {
      case 'capture':
        this.codec.resetEncoder()
        this.resampler = new Resampler(sampleRate, 16000)
        this.captureId = message.requestId
        this.captureOffset = this.captureCount = 0
        this.captureAbsoluteDeadline = this.captureDeadline = currentFrame + sampleRate * MAX_BURST_DURATION_MS / 1000
        this.reply({ type: 'capture_started', requestId: message.requestId })
        break
      case 'capture_limit':
        if (this.captureId === message.requestId) this.captureDeadline = message.milliseconds === null ? this.captureAbsoluteDeadline : Math.min(this.captureDeadline, currentFrame + sampleRate * message.milliseconds / 1000)
        break
      case 'stop': {
        const paddedSamples = this.captureId === message.requestId && this.captureOffset ? 320 - this.captureOffset : 0
        if (this.captureId === message.requestId) {
          if (this.captureOffset && this.captureCount < MAX_BURST_FRAMES) {
            this.captureFrame.fill(0, this.captureOffset)
            this.emitPacket()
          }
          this.captureId = null
        }
        this.reply({ type: 'stopped', requestId: message.requestId, captureFrames: this.captureCount, paddedSamples })
        break
      }
      case 'start':
        this.burstId = message.burstId
        this.losses = 0
        this.lostFrames = 0
        this.codec.resetDecoder()
        break
      case 'frame': {
        if (++this.queuedFrames > this.limit) throw new Error('Receive queue full')
        let pcm: Float32Array
        if (message.packet) {
          this.losses = 0
          pcm = this.codec.decode(message.packet)
        } else {
          this.losses++
          this.lostFrames++
          pcm = this.losses <= 3 ? this.codec.decode(null) : new Float32Array(960)
          if (this.losses === 4) this.codec.resetDecoder()
        }
        this.queue.push({ pcm, burstId: this.burstId, cursor: message.cursor, epoch: message.epoch, lostFrames: this.lostFrames })
        if (!this.active) {
          this.active = true
          this.reply({ type: 'playback', active: true })
        }
        break
      }
      case 'end':
        if (this.queue.length >= this.limit * 2) throw new Error('Receive queue full')
        this.queue.push({ pcm: null, burstId: this.burstId, cursor: message.cursor, epoch: message.epoch, lostFrames: this.lostFrames })
        break
      case 'reset':
        this.queue = []
        this.offset = this.queuedFrames = 0
        this.playingBurst = ''
        this.active = false
        this.reply({ type: 'playback', active: false })
        this.codec.resetDecoder()
        break
      case 'limit': this.limit = message.frames; break
    }
  }

  private emitPacket() {
    if (this.captureId && this.captureCount < MAX_BURST_FRAMES) {
      this.reply({ type: 'packet', requestId: this.captureId, packet: this.codec.encode(this.captureFrame), captureSequence: this.captureCount })
      this.captureCount++
    }
    this.captureOffset = 0
  }

  private quality(lostFrames: number, blocked: boolean, renderFrame: number) {
    const key = `${lostFrames}:${blocked}`
    if (key === this.lastQuality) return
    this.lastQuality = key
    this.reply({ type: 'quality', lostFrames, blocked, renderFrame })
  }

  process(inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    if (this.failed) return false
    try {
      const input = inputs[0]?.[0]
      if (this.captureId && currentFrame >= this.captureDeadline) {
        const requestId = this.captureId
        const paddedSamples = this.captureOffset ? 320 - this.captureOffset : 0
        if (this.captureOffset && this.captureCount < MAX_BURST_FRAMES) { this.captureFrame.fill(0, this.captureOffset); this.emitPacket() }
        this.captureId = null
        this.reply({ type: 'capture_expired', requestId, captureFrames: this.captureCount, paddedSamples })
      }
      if (this.captureId && input && this.captureCount < MAX_BURST_FRAMES) {
        for (const sample of this.resampler.push(input)) {
          this.captureFrame[this.captureOffset++] = sample
          if (this.captureOffset === 320) this.emitPacket()
        }
      }
      const output = outputs[0]?.[0]
      if (!output) return true
      for (let i = 0; i < output.length; i++) {
        while (this.queue.length && this.queue[0]!.pcm === null) {
          const end = this.queue.shift()!
          this.reply({ type: 'played', cursor: end.cursor, epoch: end.epoch, frame: false, renderFrame: currentFrame + i })
          this.playingBurst = ''
        }
        const frame = this.queue[0]
        if (!frame) {
          if (this.playingBurst) this.quality(this.lostFrames, true, currentFrame + i)
          if (this.active) { this.active = false; this.reply({ type: 'playback', active: false }) }
          continue
        }
        if (this.playingBurst !== frame.burstId) {
          this.playingBurst = frame.burstId
          this.startAt = currentFrame + i + sampleRate * PLAYBACK_START_DELAY_MS / 1000
        }
        if (currentFrame + i < this.startAt) continue
        if (this.offset === 0) this.quality(frame.lostFrames, false, currentFrame + i)
        output[i] = frame.pcm![this.offset++]!
        if (this.offset === frame.pcm!.length) {
          this.offset = 0
          this.queue.shift()
          this.queuedFrames--
          this.reply({ type: 'played', cursor: frame.cursor, epoch: frame.epoch, frame: true, renderFrame: currentFrame + i + 1 })
        }
      }
    } catch { this.fail(); return false }
    return true
  }
}

registerProcessor('zenptt-audio', ZenAudioProcessor)
