import { describe, expect, it } from 'vitest'
import {
  decodeMedia, DOWNLINK, encodeControl, encodeMedia, MAX_MESSAGE_BYTES,
  normalizeChannel, parseControl, ProtocolError, UPLINK,
} from './protocol'

const burst = '00000000-0000-0000-0000-000000000003'
const snapshot = '{"type":"snapshot","channel":"ROOM1","member_id":"00000000-0000-0000-0000-000000000001","resume_token":"opaque","generation":1,"channel_incarnation_id":"00000000-0000-0000-0000-000000000002","revision":1,"participant_count":2,"eligible_from_index":0,"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":5000},"floor":null}'

describe('independent v4 control vectors', () => {
  const messages = [
    snapshot,
    `{"type":"channel_state","revision":2,"participant_count":2,"next_burst_index":1,"floor":{"burst_id":"${burst}","burst_index":0,"owned":false}}`,
    '{"type":"resume_rejected","reason":"expired"}',
    `{"type":"ptt_granted","request_id":"r1","burst_id":"${burst}","burst_index":0,"lease_remaining_ms":5000}`,
    '{"type":"ptt_denied","request_id":"r1","reason":"channel_busy"}',
    `{"type":"uplink_ack","burst_id":"${burst}","next_sequence":32}`,
    `{"type":"audio_rejected","burst_id":"${burst}","first_sequence":24,"next_sequence":32,"reason":"expired"}`,
    `{"type":"burst_started","burst_id":"${burst}","burst_index":0}`,
    `{"type":"burst_released","burst_id":"${burst}","burst_index":0,"reason":"released"}`,
    `{"type":"burst_gaps","burst_id":"${burst}","burst_index":0,"ranges":[{"first_sequence":0,"count":2},{"first_sequence":4,"count":1}]}`,
    `{"type":"burst_sealed","burst_id":"${burst}","burst_index":0,"final_next_sequence":50,"reason":"complete"}`,
    '{"type":"listen_reset","burst_index":1,"next_sequence":0,"reason":"not_eligible"}',
    `{"type":"ptt_ended","burst_id":"${burst}","burst_index":0,"state":"draining","reason":"released","final_next_sequence":null}`,
    '{"type":"pong","id":7,"sent_at_ms":9223372036854775807}',
    '{"type":"error","code":"invalid_channel","message":"Invalid channel"}',
  ]

  it.each(messages)('accepts documented control %s', raw => {
    expect(parseControl(raw).type).toBe(JSON.parse(raw).type)
  })

  it.each(messages)('rejects missing and extra fields for %s', raw => {
    const message = JSON.parse(raw) as Record<string, unknown>
    expect(() => parseControl(JSON.stringify({ ...message, extra: 1 }))).toThrow(ProtocolError)
    for (const field of Object.keys(message)) {
      const missing = { ...message }
      delete missing[field]
      expect(() => parseControl(JSON.stringify(missing))).toThrow(ProtocolError)
    }
  })

  it('retains int64 timestamps and unbounded revision precision', () => {
    const pong = parseControl('{"type":"pong","id":7,"sent_at_ms":9223372036854775807}')
    expect(pong).toEqual({ type: 'pong', id: 7, sent_at_ms: 9223372036854775807n })
    expect(parseControl('{"type":"pong","id":7,"sent_at_ms":9.223372036854775807e18}')).toEqual(pong)
    expect(parseControl('{"type":"pong","id":7,"sent_at_ms":1000000000000000000.0}')).toEqual({ type: 'pong', id: 7, sent_at_ms: 1000000000000000000n })
    const state = parseControl(snapshot.replace('"revision":1', '"revision":9007199254740993'))
    expect(state.type === 'snapshot' && state.revision).toBe(9007199254740993n)
    expect(parseControl('{"type":"pong","id":7.0,"sent_at_ms":1e3}')).toEqual({ type: 'pong', id: 7, sent_at_ms: 1000n })
  })

  it.each([
    'null', '[]', '{}', 'true', '{"type":"unknown"}',
    '{"type":"pong","type":"pong","id":0,"sent_at_ms":0}',
    '{"type":"pong","id":0,"sent_at_ms":0,"__proto__":{}}',
    '{"type":"pong","id":0,"sent_at_ms":0,"\\u005f_proto__":{}}',
    '{"type":"pong","id":0,"\\u0069d":0,"sent_at_ms":0}',
    '{"type":"pong","id":true,"sent_at_ms":0}',
    '{"type":"pong","id":"1","sent_at_ms":0}',
    '{"type":"pong","id":2147483648,"sent_at_ms":0}',
    '{"type":"pong","id":0,"sent_at_ms":9223372036854775808}',
    '{"type":"pong","id":0,"sent_at_ms":-1}',
    '{"type":"pong","id":1.000000000000000000001,"sent_at_ms":0}',
    '{"type":"pong","id":NaN,"sent_at_ms":0}',
    '{"type":"pong","id":0,"sent_at_ms":Infinity}',
    '{"type":"pong","id":0,"sent_at_ms":1e999999}',
    snapshot.replace('"participant_count":2', '"participant_count":0'),
    snapshot.replace('"generation":1', '"generation":2147483648'),
    snapshot.replace('"channel":"ROOM1"', '"channel":"ROOM..1"'),
    snapshot.replace('"member_id":"00000000-0000-0000-0000-000000000001"', '"member_id":"not-a-uuid"'),
    snapshot.replace('"recovery_horizon_ms":5000', '"recovery_horizon_ms":5001'),
    snapshot.replace('"recovery_horizon_ms":5000', '"recovery_horizon_ms":5000,"recovery_horizon_ms":5000'),
    snapshot.replace('"floor":null', '"floor":{"burst_id":"00000000-0000-0000-0000-000000000003","burst_index":0,"owned":1}'),
    `{"type":"burst_started","burst_id":"${burst}","burst_index":2147483648}`,
    `{"type":"audio_rejected","burst_id":"${burst}","first_sequence":2,"next_sequence":1,"reason":"expired"}`,
    `{"type":"burst_gaps","burst_id":"${burst}","burst_index":0,"ranges":[{"first_sequence":0,"count":0}]}`,
    `{"type":"burst_gaps","burst_id":"${burst}","burst_index":0,"ranges":[{"first_sequence":0,"count":3},{"first_sequence":2,"count":1}]}`,
    `{"type":"burst_gaps","burst_id":"${burst}","burst_index":0,"ranges":[{"first_sequence":4294967295,"count":2}]}`,
    '{"type":"ptt_denied","request_id":"","reason":"channel_busy"}',
    '{"type":"resume_rejected","reason":"unknown"}',
    '{"type":"error","code":"unknown","message":"SECRET"}',
  ])('rejects malformed values without reflecting their content: %s', raw => {
    expect(() => parseControl(raw)).toThrowError('Invalid server message')
  })

  it('bounds UTF-8 bytes, not JavaScript character count', () => {
    const raw = JSON.stringify({ type: 'error', code: 'internal_error', message: 'é'.repeat(2100) })
    expect(raw.length).toBeLessThan(MAX_MESSAGE_BYTES)
    expect(() => parseControl(raw)).toThrow(ProtocolError)
  })

  it('serializes commands using the existing field names and exact numeric values', () => {
    expect(encodeControl({ type: 'join', channel: 'ROOM1' })).toBe('{"type":"join","channel":"ROOM1"}')
    expect(encodeControl({ type: 'join_echo' })).toBe('{"type":"join_echo"}')
    expect(encodeControl({ type: 'disconnect' })).toBe('{"type":"disconnect"}')
    expect(encodeControl({ type: 'ping', id: 7, sent_at_ms: 9223372036854775807n })).toBe('{"type":"ping","id":7,"sent_at_ms":9223372036854775807}')
    expect(encodeControl({ type: 'listen', burst_index: 8, next_sequence: 40 })).toBe('{"type":"listen","burst_index":8,"next_sequence":40}')
    expect(() => encodeControl({ type: 'join', channel: 'ECHO' })).toThrow()
    expect(() => encodeControl({ type: 'burst_end', burst_id: burst, final_next_sequence: 3001 })).toThrow()
    expect(() => encodeControl({ type: 'resume', resume_token: 'token', generation: 1 })).toThrow()
  })

  it('normalizes channels without silently trimming invalid whitespace', () => {
    expect(normalizeChannel('room.1')).toBe('ROOM.1')
    expect(normalizeChannel('echo')).toBe('ECHO')
    for (const value of ['', ' ROOM1', 'ROOM1 ', 'ROOM1\n', 'ROOM1\r\n', 'ROOM..1', '.ROOM', 'A'.repeat(257)]) {
      expect(() => normalizeChannel(value)).toThrow()
    }
  })
})

describe('independent binary framing vectors', () => {
  // Header: downlink, UUID ...0003, first sequence 42, two packets of lengths 1 and 2.
  const literal = Uint8Array.from([
    4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3,
    0, 0, 0, 42, 0, 2, 0, 1, 0xf8, 0, 2, 0xff, 0xfe,
  ])
  it('decodes bytes independent of the encoder, including offset views', () => {
    const expected = { burstId: burst, firstSequence: 42, packets: [new Uint8Array([0xf8]), new Uint8Array([0xff, 0xfe])] }
    expect(decodeMedia(literal)).toEqual(expected)
    const padded = new Uint8Array(literal.length + 7)
    padded.set(literal, 3)
    expect(decodeMedia(padded.subarray(3, 3 + literal.length))).toEqual(expected)
    expect(encodeMedia(DOWNLINK, expected)).toEqual(literal)
    expect(encodeMedia(UPLINK, expected)).toEqual(literal.map((value, index) => index === 1 ? 1 : value))
  })
  it('rejects every truncation, trailing bytes, and invalid header or length', () => {
    for (let length = 0; length < literal.length; length++) expect(() => decodeMedia(literal.slice(0, length))).toThrow()
    expect(() => decodeMedia(new Uint8Array([...literal, 0]))).toThrow()
    for (const [offset, value] of [[0, 3], [1, 1], [22, 1], [23, 0], [23, 51], [24, 255], [25, 0]]) {
      const invalid = literal.slice()
      invalid[offset!] = value!
      expect(() => decodeMedia(invalid)).toThrow()
    }
    const overflow = literal.slice()
    overflow.fill(255, 18, 22)
    expect(() => decodeMedia(overflow)).toThrow()
  })
  it('enforces size and packet limits and accepts the last uint32 position', () => {
    const envelope = { burstId: burst, firstSequence: 0xffffffff, packets: [new Uint8Array([1])] }
    expect(decodeMedia(encodeMedia(DOWNLINK, envelope))).toEqual(envelope)
    for (const packets of [[], [new Uint8Array(0)], [new Uint8Array(1276)], Array.from({ length: 51 }, () => new Uint8Array([1])), Array.from({ length: 4 }, () => new Uint8Array(1275))]) {
      expect(() => encodeMedia(UPLINK, { ...envelope, firstSequence: 0, packets })).toThrow()
    }
    expect(() => encodeMedia(UPLINK, { ...envelope, packets: [new Uint8Array([1]), new Uint8Array([2])] })).toThrow()
    expect(() => decodeMedia(new Uint8Array(4097))).toThrow()
  })
})
