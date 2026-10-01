import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ReceiveBuffer } from './ReceiveBuffer'
import type { ClientAudio } from './audio/messages'

describe('ordered receive recovery', () => {
  let audio: ClientAudio, receiver: ReceiveBuffer
  beforeEach(() => {
    audio = { prepare: vi.fn(), changeMicrophone: vi.fn(), cue: vi.fn(), startCapture: vi.fn(), stopCapture: vi.fn(), limitCapture: vi.fn(), startBurst: vi.fn(), play: vi.fn(), endBurst: vi.fn(), resetPlayback: vi.fn(), setQueueLimit: vi.fn(), dispose: vi.fn() }
    receiver = new ReceiveBuffer(audio, 3)
  })
  const frames = (id: string, first: number, ...values: number[]) => ({ burstId: id, firstSequence: first, packets: values.map(value => new Uint8Array([value])) })
  it('resumes from playback, deduplicating queued frames while accepting the new tail', () => {
    receiver.start('a', 0)
    receiver.media(frames('a', 0, 1, 2))
    receiver.played({ burstIndex: 0, nextSequence: 1 })
    expect(receiver.cursor).toEqual({ burstIndex: 0, nextSequence: 1 })
    receiver.interrupted()
    receiver.start('a', 0)
    receiver.media(frames('a', 0, 1, 2, 3))
    receiver.seal('a', 0, 3)
    receiver.seal('a', 0, 3)
    expect(audio.startBurst).toHaveBeenCalledOnce()
    expect(audio.play).toHaveBeenCalledTimes(3)
    expect(audio.endBurst).toHaveBeenCalledOnce()
    receiver.played({ burstIndex: 1, nextSequence: 0 })
    receiver.interrupted()
    receiver.start('a', 0)
    receiver.media(frames('a', 0, 1, 2, 3))
    receiver.seal('a', 0, 3)
    expect(audio.play).toHaveBeenCalledTimes(3)
  })
  it('deduplicates server gaps and preserves burst order', () => {
    receiver.start('a', 0)
    receiver.gaps('a', 0, [{ first_sequence: 0, count: 2 }])
    receiver.gaps('a', 0, [{ first_sequence: 0, count: 2 }])
    expect(audio.play).toHaveBeenCalledTimes(2)
    expect(() => receiver.start('b', 1)).toThrow()
  })
  it('rejects conflicting duplicates, unresolved jumps, and excess queue growth', () => {
    receiver.start('a', 0)
    receiver.media(frames('a', 0, 1, 2, 3))
    expect(() => receiver.media(frames('a', 0, 9))).toThrow()
    expect(() => receiver.media(frames('a', 4, 5))).toThrow()
    expect(() => receiver.media(frames('a', 3, 4))).toThrow()
    receiver.played({ burstIndex: 0, nextSequence: 1 })
    receiver.media(frames('a', 3, 4))
  })
  it('honors a history reset without inserting loss frames', () => {
    receiver.start('a', 0)
    receiver.media(frames('a', 0, 1))
    receiver.reset({ burstIndex: 2, nextSequence: 10 })
    receiver.start('c', 2)
    receiver.media(frames('c', 10, 7))
    expect(audio.resetPlayback).toHaveBeenCalledOnce()
    expect(audio.play).toHaveBeenLastCalledWith(new Uint8Array([7]), { burstIndex: 2, nextSequence: 11 })
    expect(audio.play).toHaveBeenCalledTimes(2)
  })
})
