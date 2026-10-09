// Defines commands, replies, and the audio interface shared across the worklet boundary.
import type { PlaybackCursor } from '../recovery'

export interface AudioEvents {
  quality: (lostFrames: number, blocked: boolean) => void
  packet: (requestId: string, packet: Uint8Array) => void
  captureEnded: (requestId: string) => void
  captureExpired: (requestId: string) => void
  playback: (active: boolean) => void
  played: (cursor: PlaybackCursor) => void
  error: (message: string) => void
}

export interface ClientAudio {
  prepare(deviceId?: string): Promise<void>
  changeMicrophone(deviceId: string): Promise<void>
  cue(kind: 'free' | 'rejected' | 'interrupted'): void
  startCapture(requestId: string): void
  stopCapture(requestId: string): void
  limitCapture(requestId: string, milliseconds: number | null): void
  startBurst(burstId: string): void
  play(packet: Uint8Array | null, cursor: PlaybackCursor): void
  endBurst(cursor: PlaybackCursor): void
  resetPlayback(): void
  setQueueLimit(frames: number): void
  dispose(): void
}

export type AudioCommand =
  | { type: 'capture'; requestId: string }
  | { type: 'stop'; requestId: string }
  | { type: 'capture_limit'; requestId: string; milliseconds: number | null }
  | { type: 'start'; burstId: string }
  | { type: 'frame'; packet: Uint8Array | null; cursor: PlaybackCursor; epoch: number }
  | { type: 'end'; cursor: PlaybackCursor; epoch: number }
  | { type: 'reset'; epoch: number }
  | { type: 'limit'; frames: number }

export type AudioReply = (
  | { type: 'quality'; lostFrames: number; blocked: boolean }
  | { type: 'ready' }
  | { type: 'capture_started'; requestId: string }
  | { type: 'packet'; requestId: string; packet: Uint8Array }
  | { type: 'stopped'; requestId: string }
  | { type: 'capture_expired'; requestId: string }
  | { type: 'played'; cursor: PlaybackCursor; epoch: number; frame: boolean }
  | { type: 'playback'; active: boolean }
  | { type: 'error' }
) & { renderFrame?: number; queuedFrames?: number; captureSequence?: number; captureFrames?: number; paddedSamples?: number }
