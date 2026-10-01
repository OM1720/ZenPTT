import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { describe, expect, it } from 'vitest'
import { OpusCodec } from '../src/audio/OpusCodec'

const module = new WebAssembly.Module(readFileSync(new URL('../src/generated/opus.wasm', import.meta.url)))
const native = process.env.NATIVE_OPUS
if (!native) throw new Error('Run codec verification in the pinned Linux container (NATIVE_OPUS is required).')
const source = Float32Array.from({ length: 16000 }, (_, i) => 0.35 * Math.sin(2 * Math.PI * 440 * i / 16000) + 0.1 * Math.sin(2 * Math.PI * 1100 * i / 16000))
function unpack(bytes: Uint8Array): Uint8Array[] {
  const packets: Uint8Array[] = []
  for (let offset = 0; offset < bytes.length;) {
    const size = bytes[offset]! + (bytes[offset + 1]! << 8)
    packets.push(bytes.slice(offset + 2, offset + 2 + size))
    offset += size + 2
  }
  return packets
}
function pack(packets: Uint8Array[]) {
  return Buffer.concat(packets.flatMap(packet => [Buffer.from([packet.length & 255, packet.length >> 8]), Buffer.from(packet)]))
}
function floats(bytes: Uint8Array) {
  return new Float32Array(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength))
}
function verifySignal(pcm: Float32Array) {
  expect(pcm).toHaveLength(48000)
  expect(pcm.every(Number.isFinite)).toBe(true)
  const energy = pcm.subarray(4800).reduce((sum, sample) => sum + sample * sample, 0) / 43200
  expect(energy).toBeGreaterThan(0.04)
  expect(energy).toBeLessThan(0.1)
}

describe('WASM and independent native libopus interoperability', () => {
  it('cross-decodes both encoders and agrees with native output', () => {
    const directory = mkdtempSync(join(tmpdir(), 'zenptt-opus-'))
    try {
      const input = join(directory, 'input.f32'), encoded = join(directory, 'native.packets'), decoded = join(directory, 'decoded.f32')
      writeFileSync(input, new Uint8Array(source.buffer))
      execFileSync(native!, ['encode', input, encoded])
      const nativePackets = unpack(readFileSync(encoded))
      expect(nativePackets).toHaveLength(50)
      const decoder = new OpusCodec(module)
      const wasmDecoded = Float32Array.from(nativePackets.flatMap(packet => Array.from(decoder.decode(packet))))
      verifySignal(wasmDecoded)
      execFileSync(native!, ['decode', encoded, decoded])
      const nativeDecoded = floats(readFileSync(decoded))
      let squaredError = 0
      for (let i = 0; i < nativeDecoded.length; i++) squaredError += (nativeDecoded[i]! - wasmDecoded[i]!) ** 2
      expect(Math.sqrt(squaredError / nativeDecoded.length)).toBeLessThan(0.0001)

      const encoder = new OpusCodec(module)
      const packets: Uint8Array[] = []
      for (let i = 0; i < source.length; i += 320) packets.push(encoder.encode(source.subarray(i, i + 320)))
      expect(packets.every(packet => packet.length > 0 && packet.length <= 1275)).toBe(true)
      expect(packets.reduce((sum, packet) => sum + packet.length, 0)).toBeLessThan(2600)
      writeFileSync(encoded, pack(packets))
      execFileSync(native!, ['decode', encoded, decoded])
      verifySignal(floats(readFileSync(decoded)))
      encoder.resetEncoder()
      expect(encoder.encode(source.subarray(0, 320))).toEqual(packets[0])
      decoder.resetDecoder()
      expect(decoder.decode(nativePackets[0]!)).toEqual(wasmDecoded.subarray(0, 960))
      expect(decoder.decode(null)).toHaveLength(960)
      expect(() => decoder.decode(new Uint8Array([255]))).toThrow()
    } finally { rmSync(directory, { recursive: true, force: true }) }
  })
})
