// Orders downlink bursts and deduplicates replay until the audio worklet confirms playback.
import { ProtocolError } from './protocol'
import type { MediaEnvelope } from './protocol'
import type { ClientAudio } from './audio/messages'
import { compareCursor } from './recovery'
import type { PlaybackCursor } from './recovery'
import { MAX_BURST_FRAMES } from './mediaLimits'

interface Burst {
  id: string
  index: number
  next: number
  sealed: boolean
  frames: Map<number, Uint8Array | null>
}

// Keep only unplayed packet identities, so a resumed stream cannot enqueue them twice.
export class ReceiveBuffer {
  cursor: PlaybackCursor = { burstIndex: 0, nextSequence: 0 }
  private readonly bursts = new Map<number, Burst>()
  private incoming: { id: string; index: number } | null = null
  private frameCount = 0

  constructor(private readonly audio: ClientAudio, private readonly limit: number) {}

  reset(cursor: PlaybackCursor) {
    this.cursor = { ...cursor }
    this.bursts.clear()
    this.incoming = null
    this.frameCount = 0
    this.audio.resetPlayback()
  }
  interrupted() { this.incoming = null }

  start(id: string, index: number) {
    this.incoming = { id, index }
    if (index < this.cursor.burstIndex) return
    const previous = this.bursts.get(index)
    if (previous) { if (previous.id !== id) throw new ProtocolError(); return }
    const latest = [...this.bursts.values()].at(-1)
    if (latest && (index <= latest.index || !latest.sealed)) throw new ProtocolError()
    if (this.bursts.size >= this.limit) throw new Error('Receive metadata is full')
    this.bursts.set(index, { id, index, next: index === this.cursor.burstIndex ? this.cursor.nextSequence : 0, sealed: false, frames: new Map() })
    this.audio.startBurst(id)
  }

  media(media: MediaEnvelope) {
    if (!this.incoming || this.incoming.id !== media.burstId || media.firstSequence + media.packets.length > MAX_BURST_FRAMES) throw new ProtocolError()
    media.packets.forEach((packet, offset) => this.frame(media.firstSequence + offset, packet))
  }

  gaps(id: string, index: number, ranges: { first_sequence: number; count: number }[]) {
    this.match(id, index)
    for (const range of ranges) {
      if (range.first_sequence + range.count > MAX_BURST_FRAMES) throw new ProtocolError()
      for (let i = 0; i < range.count; i++) this.frame(range.first_sequence + i, null)
    }
  }

  seal(id: string, index: number, final: number) {
    this.match(id, index)
    if (index < this.cursor.burstIndex) return
    const burst = this.bursts.get(index)
    if (!burst || final !== burst.next) throw new ProtocolError()
    if (burst.sealed) return
    burst.sealed = true
    this.audio.endBurst({ burstIndex: index + 1, nextSequence: 0 })
  }

  played(cursor: PlaybackCursor) {
    if (compareCursor(cursor, this.cursor) <= 0) return
    this.cursor = { ...cursor }
    for (const [index, burst] of this.bursts) {
      if (index < cursor.burstIndex) { this.frameCount -= burst.frames.size; this.bursts.delete(index) }
      else if (index === cursor.burstIndex) {
        for (const sequence of burst.frames.keys()) {
          if (sequence < cursor.nextSequence) { burst.frames.delete(sequence); this.frameCount-- }
        }
      }
    }
  }

  private match(id: string, index: number) {
    if (!this.incoming || this.incoming.id !== id || this.incoming.index !== index) throw new ProtocolError()
  }

  private frame(sequence: number, packet: Uint8Array | null) {
    const incoming = this.incoming!
    if (compareCursor({ burstIndex: incoming.index, nextSequence: sequence + 1 }, this.cursor) <= 0) return
    const burst = this.bursts.get(incoming.index)
    if (!burst) throw new ProtocolError()
    if (sequence < burst.next) {
      const previous = burst.frames.get(sequence)
      if (previous === undefined || (packet === null ? previous !== null : previous === null || previous.length !== packet.length || previous.some((byte, i) => byte !== packet[i]))) throw new ProtocolError()
      return
    }
    if (sequence !== burst.next || burst.sealed) throw new ProtocolError()
    if (this.frameCount >= this.limit) throw new Error('Receive queue is full')
    burst.frames.set(sequence, packet?.slice() ?? null)
    burst.next++
    this.frameCount++
    this.audio.play(packet, { burstIndex: incoming.index, nextSequence: sequence + 1 })
  }
}
