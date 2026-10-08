// Retains bounded uplink Opus packets for ACK-driven replay and defines playback cursors.
import { encodeMedia, MAX_MESSAGE_BYTES, MAX_PACKETS, ProtocolError, UPLINK } from './protocol'
import { FRAME_DURATION_MS, MAX_BURST_FRAMES } from './mediaLimits'

export interface PlaybackCursor { burstIndex: number; nextSequence: number }
export const compareCursor = (a: PlaybackCursor, b: PlaybackCursor) => a.burstIndex - b.burstIndex || a.nextSequence - b.nextSequence
export const historyMs = (horizon: number) => Math.max(15000, 3 * horizon)
export const ackWatchdogMs = (horizon: number) => Math.min(3000, Math.max(500, 0.6 * horizon))

export class UplinkBuffer {
  readonly packets = new Map<number, Uint8Array>()
  nextSequence = 0
  acknowledged = 0
  sendCursor = 0
  finalSequence: number | null = null

  constructor(readonly burstId: string, readonly startedAt: number, readonly horizon: number) {}

  append(packet: Uint8Array, now: number) {
    if (this.finalSequence !== null || this.nextSequence >= MAX_BURST_FRAMES) throw new ProtocolError()
    this.expire(now)
    if (this.packets.size >= this.horizon / FRAME_DURATION_MS) throw new Error('Uplink retention is full')
    const sequence = this.nextSequence++
    // Expiry removes local custody only. The server alone declares missing positions.
    if (now - (this.startedAt + sequence * FRAME_DURATION_MS) <= this.horizon) this.packets.set(sequence, packet.slice())
  }

  expire(now: number) {
    for (const sequence of this.packets.keys()) {
      if (now - (this.startedAt + sequence * FRAME_DURATION_MS) > this.horizon) this.packets.delete(sequence)
    }
  }

  acknowledge(next: number): boolean {
    if (next > this.nextSequence) throw new ProtocolError()
    if (next <= this.acknowledged) return false
    this.acknowledged = next
    for (const sequence of this.packets.keys()) if (sequence < next) this.packets.delete(sequence)
    return true
  }

  reject(first: number, next: number) {
    for (const sequence of this.packets.keys()) if (sequence >= first && sequence < next) this.packets.delete(sequence)
  }

  rewind() { this.sendCursor = this.acknowledged }
  end() { this.finalSequence ??= this.nextSequence }

  envelope(maxPackets = MAX_PACKETS): { bytes: Uint8Array; next: number; count: number } | null {
    const packets: Uint8Array[] = []
    let first = -1, size = 24
    for (const [sequence, packet] of this.packets) {
      if (sequence < Math.max(this.sendCursor, this.acknowledged)) continue
      if (first < 0) first = sequence
      if (sequence !== first + packets.length || packets.length === maxPackets || size + packet.length + 2 > MAX_MESSAGE_BYTES) break
      packets.push(packet)
      size += packet.length + 2
    }
    return first < 0 ? null : { bytes: encodeMedia(UPLINK, { burstId: this.burstId, firstSequence: first, packets }), next: first + packets.length, count: packets.length }
  }
}
