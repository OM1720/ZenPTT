import { readFileSync } from 'node:fs'
import { beforeAll, beforeEach, afterAll, describe, expect, it, vi } from 'vitest'
import { OpusCodec } from './OpusCodec'
import type { AudioCommand, AudioReply } from './messages'

const module = new WebAssembly.Module(readFileSync(new URL('../generated/opus.wasm', import.meta.url)))
interface Processor {
  port: { onmessage: (event: { data: AudioCommand }) => void; replies: AudioReply[] }
  process: (inputs: Float32Array[][], outputs: Float32Array[][]) => boolean
}
let ProcessorClass: new (options: { processorOptions: { module: WebAssembly.Module } }) => Processor
beforeAll(async () => {
  vi.stubGlobal('sampleRate', 48000)
  vi.stubGlobal('currentFrame', 0)
  vi.stubGlobal('AudioWorkletProcessor', class {
    port = {
      onmessage: () => {}, replies: [] as AudioReply[],
      postMessage(message: AudioReply) { this.replies.push(message) },
    }
  })
  vi.stubGlobal('registerProcessor', (_name: string, processor: typeof ProcessorClass) => { ProcessorClass = processor })
  await import('./processor')
})
afterAll(() => vi.unstubAllGlobals())
beforeEach(() => vi.stubGlobal('currentFrame', 0))
const command = (processor: Processor, data: AudioCommand) => processor.port.onmessage({ data })
const make = () => new ProcessorClass({ processorOptions: { module } })
const tone = Float32Array.from({ length: 320 }, (_, i) => 0.4 * Math.sin(2 * Math.PI * 500 * i / 16000))

describe('worklet rendering with the real WASM codec', () => {
  it('stops on the render clock without waiting for a main-thread timer', () => {
    const processor = make()
    command(processor, { type: 'capture', requestId: 'request' })
    command(processor, { type: 'capture_limit', requestId: 'request', milliseconds: 1000 })
    vi.stubGlobal('currentFrame', 24000)
    command(processor, { type: 'capture_limit', requestId: 'request', milliseconds: 1000 })
    vi.stubGlobal('currentFrame', 48000)
    processor.process([[new Float32Array(128)]], [[new Float32Array(128)]])
    expect(processor.port.replies.at(-1)).toEqual({ type: 'capture_expired', requestId: 'request' })
    expect(processor.port.replies.filter(message => message.type === 'packet')).toHaveLength(0)
  })
  it('resuming cannot extend the original 60-second capture deadline', () => {
    const processor = make()
    command(processor, { type: 'capture', requestId: 'request' })
    vi.stubGlobal('currentFrame', 48000 * 59)
    command(processor, { type: 'capture_limit', requestId: 'request', milliseconds: 5000 })
    command(processor, { type: 'capture_limit', requestId: 'request', milliseconds: null })
    vi.stubGlobal('currentFrame', 48000 * 60)
    processor.process([], [[new Float32Array(128)]])
    expect(processor.port.replies.at(-1)).toEqual({ type: 'capture_expired', requestId: 'request' })
  })
  it('buffers exactly 100 ms then renders an open burst before its end', () => {
    const processor = make(), encoder = new OpusCodec(module)
    command(processor, { type: 'start', burstId: 'first' })
    for (let i = 0; i < 10; i++) command(processor, { type: 'frame', packet: encoder.encode(tone), cursor: { burstIndex: 0, nextSequence: i + 1 }, epoch: 0 })
    const rendered: number[] = []
    for (let frame = 0; frame < 7680; frame += 128) {
      vi.stubGlobal('currentFrame', frame)
      const output = new Float32Array(128)
      expect(processor.process([], [[output]])).toBe(true)
      rendered.push(...output)
    }
    expect(rendered.slice(0, 4800).every(value => value === 0)).toBe(true)
    expect(rendered.slice(4800).some(value => Math.abs(value) > 0.1)).toBe(true)
    expect(processor.port.replies.filter(message => message.type === 'played')).toHaveLength(3)
  })
  it('captures only when started and flushes one padded final frame on release', () => {
    const processor = make()
    const input = Float32Array.from({ length: 128 }, (_, i) => 0.4 * Math.sin(i / 10))
    const render = () => processor.process([[input]], [[new Float32Array(128)]])
    for (let i = 0; i < 20; i++) render()
    expect(processor.port.replies.filter(message => message.type === 'packet')).toHaveLength(0)
    command(processor, { type: 'capture', requestId: 'request' })
    for (let i = 0; i < 10; i++) render()
    command(processor, { type: 'stop', requestId: 'request' })
    const packets = processor.port.replies.filter(message => message.type === 'packet')
    expect(packets).toHaveLength(2)
    const decoder = new OpusCodec(module)
    for (const packet of packets) expect(decoder.decode(packet.packet)).toHaveLength(960)
    expect(processor.port.replies.at(-1)).toEqual({ type: 'stopped', requestId: 'request' })
    for (let i = 0; i < 20; i++) render()
    expect(processor.port.replies.filter(message => message.type === 'packet')).toHaveLength(2)
  })
  it('fails explicitly on overflow instead of evicting unheard frames', () => {
    const processor = make(), encoder = new OpusCodec(module)
    command(processor, { type: 'limit', frames: 2 })
    command(processor, { type: 'start', burstId: 'first' })
    for (let i = 0; i < 3; i++) command(processor, { type: 'frame', packet: encoder.encode(tone), cursor: { burstIndex: 0, nextSequence: i + 1 }, epoch: 0 })
    expect(processor.port.replies.at(-1)).toEqual({ type: 'error' })
    expect(processor.process([], [[new Float32Array(128)]])).toBe(false)
  })
})
