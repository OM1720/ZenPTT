import { describe, expect, it } from 'vitest'
import { Resampler } from './Resampler'

function tone(rate: number, frequency: number) {
  return Float32Array.from({ length: rate }, (_, i) => 0.5 * Math.sin(2 * Math.PI * frequency * i / rate))
}
const rms = (pcm: Float32Array) => Math.sqrt(pcm.reduce((sum, value) => sum + value * value, 0) / pcm.length)

describe('streaming capture resampling', () => {
  it.each([44100, 48000, 96000])('keeps duration and speech-band energy at %i Hz', rate => {
    const output = new Resampler(rate, 16000).push(tone(rate, 700))
    expect(Math.abs(output.length - 16000)).toBeLessThanOrEqual(1)
    expect(rms(output.subarray(1600))).toBeGreaterThan(0.34)
    expect(rms(output.subarray(1600))).toBeLessThan(0.36)
  })
  it('rejects ultrasonic aliases before decimation', () => {
    const output = new Resampler(48000, 16000).push(tone(48000, 12000))
    expect(rms(output.subarray(1600))).toBeLessThan(0.002)
  })
  it('produces identical samples across irregular input block boundaries', () => {
    const input = tone(44100, 500)
    const whole = new Resampler(44100, 16000).push(input)
    const resampler = new Resampler(44100, 16000)
    const blocks: number[] = []
    for (let i = 0; i < input.length; i += 127) blocks.push(...resampler.push(input.subarray(i, i + 127)))
    expect(Float32Array.from(blocks)).toEqual(whole)
  })
})
