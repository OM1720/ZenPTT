import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { assertSignal, signal } from '../../tests/signal'
import { OpusCodec } from './OpusCodec'

const module = new WebAssembly.Module(readFileSync(new URL('../generated/opus.wasm', import.meta.url)))
function roundTrip(pcm: Float32Array) {
  const codec = new OpusCodec(module), output: number[] = []
  for (let i = 0; i < pcm.length; i += 320) output.push(...codec.decode(codec.encode(pcm.slice(i, i + 320))))
  return Float32Array.from(output)
}
it('accepts two real Opus signals and bounded gain/delay', () => {
  for (const variant of [0, 1]) {
    const source = signal(48000, 2, variant)
    const received = roundTrip(signal(16000, 2, variant))
    expect(() => assertSignal(source, received, 48000)).not.toThrow()
    const shifted = new Float32Array(received.length)
    shifted.set(received.subarray(0, received.length - 960).map(x => x * 0.7), 960)
    expect(() => assertSignal(source, shifted, 48000)).not.toThrow()
  }
})
it.each(['silence', 'noise', 'tone', 'reordered', 'truncated'])('rejects %s after real Opus', kind => {
  const input = signal(16000), reference = signal(48000)
  let seed = 12345
  const bad = kind === 'silence' ? new Float32Array(input.length)
    : kind === 'noise' ? input.map(() => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return (seed / 2 ** 32 - 0.5) * 0.5 })
    : kind === 'tone' ? input.map((_, i) => {
      const level = [0.2, 0.7, 0.35, 0.9, 0.15, 0.55, 0.8, 0.3][Math.floor(i / 4000)]!
      return level * 0.4 * Math.sin(2 * Math.PI * 1900 * i / 16000)
    })
    : kind === 'reordered' ? Float32Array.from([...input.slice(16000), ...input.slice(0, 16000)])
    : input.slice(0, input.length - 3200)
  expect(() => assertSignal(reference, roundTrip(bad), 48000)).toThrow(kind === 'tone' ? /spectrum/ : /PCM/)
})
