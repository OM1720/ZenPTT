import { describe, expect, it } from 'vitest'
import { UplinkBuffer } from './recovery'
import { decodeMedia, UPLINK } from './protocol'

const id = '00000000-0000-0000-0000-000000000003'
describe('original Opus custody', () => {
  it('replays exact original bytes after the cumulative ACK and ignores duplicate ACKs', () => {
    const buffer = new UplinkBuffer(id, 0, 5000)
    const original = new Uint8Array([1, 2, 3])
    buffer.append(original, 0)
    original.fill(9)
    buffer.append(new Uint8Array([4]), 20)
    expect(buffer.acknowledge(1)).toBe(true)
    expect(buffer.acknowledge(0)).toBe(false)
    buffer.sendCursor = 2
    buffer.rewind()
    const replay = decodeMedia(buffer.envelope()!.bytes, UPLINK)
    expect(replay.firstSequence).toBe(1)
    expect(replay.packets).toEqual([new Uint8Array([4])])
    expect(buffer.packets.size).toBe(1)
    expect(() => buffer.acknowledge(3)).toThrow()
  })
  it('expires custody without advancing ACK or inventing gaps', () => {
    const buffer = new UplinkBuffer(id, 0, 1000)
    buffer.append(new Uint8Array([1]), 0)
    buffer.append(new Uint8Array([2]), 20)
    buffer.expire(1001)
    expect([...buffer.packets.keys()]).toEqual([1])
    expect(buffer.acknowledged).toBe(0)
    buffer.end()
    expect(buffer.finalSequence).toBe(2)
    expect(() => buffer.append(new Uint8Array([3]), 40)).toThrow()
  })
  it('bounds frame retention and binary envelopes', () => {
    const buffer = new UplinkBuffer(id, 0, 1000)
    for (let i = 0; i < 50; i++) buffer.append(new Uint8Array(1275).fill(i), i * 20)
    expect(() => buffer.append(new Uint8Array([1]), 1000)).toThrow()
    let count = 0
    for (let envelope = buffer.envelope(); envelope; envelope = buffer.envelope()) {
      expect(envelope.bytes.length).toBeLessThanOrEqual(4096)
      count += decodeMedia(envelope.bytes, UPLINK).packets.length
      buffer.sendCursor = envelope.next
    }
    expect(count).toBe(50)
  })
})
