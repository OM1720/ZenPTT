// Validates v4 control messages and binary media envelopes at the browser boundary.
import { isLosslessNumber, parse, splitNumber, stringify } from 'lossless-json'
import { FRAME_DURATION_MS, MAX_BURST_FRAMES, MAX_PACKET_BYTES } from './mediaLimits'

export { MAX_PACKET_BYTES } from './mediaLimits'

export const SUBPROTOCOL = 'zenptt.v4'
export const MAX_MESSAGE_BYTES = 4096
export const MAX_PACKETS = 50
export const MAX_SEQUENCE = 0xffffffff
export const MAX_BURST_INDEX = 0x7fffffff
export const MEDIA_HEADER_BYTES = 24
export const UPLINK = 1
export const DOWNLINK = 2
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const CHANNEL_PATTERN = /^[A-Z0-9]+(?:\.[A-Z0-9]+)*$/
const utf8 = new TextEncoder()

export class ProtocolError extends Error {
  constructor() {
    super('Invalid server message')
    this.name = 'ProtocolError'
  }
}

type Validator<T> = (value: unknown) => T

function object<S extends Record<string, Validator<unknown>>>(shape: S) {
  return (value: unknown): { [K in keyof S]: ReturnType<S[K]> } => {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new ProtocolError()
    const record = value as Record<string, unknown>
    const keys = Object.keys(shape)
    if (Object.keys(record).length !== keys.length || keys.some(key => !Object.hasOwn(record, key))) {
      throw new ProtocolError()
    }
    return Object.fromEntries(keys.map(key => [key, shape[key]!(record[key])])) as {
      [K in keyof S]: ReturnType<S[K]>
    }
  }
}

function text(minimum = 0, maximum = MAX_MESSAGE_BYTES): Validator<string> {
  return value => {
    if (typeof value !== 'string') throw new ProtocolError()
    const length = Array.from(value).length
    if (length < minimum || length > maximum) throw new ProtocolError()
    return value
  }
}

function oneOf<const T extends readonly string[]>(...values: T): Validator<T[number]> {
  return value => {
    if (typeof value !== 'string' || !values.includes(value)) throw new ProtocolError()
    return value as T[number]
  }
}

// Preserve 64-bit timestamps and counters without accepting rounded fractional values.
function exactInteger(value: unknown): bigint {
  if (isLosslessNumber(value)) {
    const { sign, digits, exponent } = splitNumber(value.toString())
    const trailingZeros = exponent - digits.length + 1
    // A short exponent must not expand into an unbounded allocation.
    if (!Number.isSafeInteger(trailingZeros) || trailingZeros < 0 || exponent >= MAX_MESSAGE_BYTES) throw new ProtocolError()
    return BigInt(sign + digits + '0'.repeat(trailingZeros))
  }
  if (typeof value === 'bigint') return value
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new ProtocolError()
  return BigInt(value)
}

function counter(minimum = 0n, maximum?: bigint): Validator<bigint> {
  return value => {
    const result = exactInteger(value)
    if (result < minimum || (maximum !== undefined && result > maximum)) throw new ProtocolError()
    return result
  }
}

function integer(minimum = 0, maximum = MAX_SEQUENCE): Validator<number> {
  const validate = counter(BigInt(minimum), BigInt(maximum))
  return value => Number(validate(value))
}

function nullable<T>(validate: Validator<T>): Validator<T | null> {
  return value => value === null ? null : validate(value)
}

const uuid: Validator<string> = value => {
  const result = text(36, 36)(value)
  if (!UUID_PATTERN.test(result)) throw new ProtocolError()
  return result
}
const channel: Validator<string> = value => {
  const result = text(1, 256)(value)
  if (CHANNEL_PATTERN.exec(result)?.[0] !== result) throw new ProtocolError()
  return result
}
const normalChannel: Validator<string> = value => {
  const result = channel(value)
  if (result === 'ECHO') throw new ProtocolError()
  return result
}
const boolean: Validator<boolean> = value => {
  if (typeof value !== 'boolean') throw new ProtocolError()
  return value
}
const burstIndex = integer(0, MAX_BURST_INDEX)
const sequence = integer()
const requestId = text(1, 128)
const timestamp = counter(0n, 0x7fffffffffffffffn)
const floor = nullable(object({ burst_id: uuid, burst_index: burstIndex, owned: boolean }))
const recoveryHorizon: Validator<number> = value => {
  const result = integer(1000, 60000)(value)
  if (result % FRAME_DURATION_MS !== 0) throw new ProtocolError()
  return result
}
const gap = object({ first_sequence: sequence, count: integer(1, MAX_SEQUENCE + 1) })
const gaps: Validator<ReturnType<typeof gap>[]> = value => {
  if (!Array.isArray(value)) throw new ProtocolError()
  const result = value.map(gap)
  let previousEnd = 0
  for (const range of result) {
    if (range.first_sequence < previousEnd || range.first_sequence + range.count > MAX_SEQUENCE + 1) {
      throw new ProtocolError()
    }
    previousEnd = range.first_sequence + range.count
  }
  return result
}

export const ERROR_MESSAGES = {
  invalid_message: 'Invalid message',
  invalid_channel: 'Invalid channel',
  channel_full: 'Channel is full',
  not_joined: 'Join a channel first',
  invalid_state: 'Action is not available',
  server_busy: 'Server is busy',
  internal_error: 'Server error',
} as const

const serverSchemas = {
  snapshot: object({
    type: oneOf('snapshot'), channel, member_id: uuid, resume_token: text(1, 256),
    generation: integer(1, MAX_BURST_INDEX), channel_incarnation_id: uuid,
    revision: counter(), participant_count: counter(1n), eligible_from_index: counter(),
    next_burst_index: counter(), audio_policy: object({ recovery_horizon_ms: recoveryHorizon }), floor,
  }),
  channel_state: object({
    type: oneOf('channel_state'), revision: counter(), participant_count: counter(1n),
    next_burst_index: counter(), floor,
  }),
  resume_rejected: object({ type: oneOf('resume_rejected'), reason: oneOf('invalid_token', 'expired', 'stale_generation') }),
  ptt_granted: object({
    type: oneOf('ptt_granted'), request_id: requestId, burst_id: uuid,
    burst_index: burstIndex, lease_remaining_ms: counter(),
  }),
  ptt_denied: object({ type: oneOf('ptt_denied'), request_id: requestId, reason: oneOf('channel_busy', 'invalid_state', 'server_busy') }),
  uplink_ack: object({ type: oneOf('uplink_ack'), burst_id: uuid, next_sequence: sequence }),
  audio_rejected: object({
    type: oneOf('audio_rejected'), burst_id: uuid, first_sequence: sequence, next_sequence: sequence,
    reason: oneOf('unknown_burst', 'expired', 'invalid_range', 'payload_mismatch', 'server_busy'),
  }),
  burst_started: object({ type: oneOf('burst_started'), burst_id: uuid, burst_index: burstIndex }),
  burst_released: object({
    type: oneOf('burst_released'), burst_id: uuid, burst_index: burstIndex,
    reason: oneOf('released', 'canceled', 'lease_expired', 'duration_limit', 'member_left', 'echo'),
  }),
  burst_gaps: object({ type: oneOf('burst_gaps'), burst_id: uuid, burst_index: burstIndex, ranges: gaps }),
  burst_sealed: object({
    type: oneOf('burst_sealed'), burst_id: uuid, burst_index: burstIndex,
    final_next_sequence: sequence, reason: oneOf('complete', 'expired', 'canceled', 'member_left'),
  }),
  listen_reset: object({
    type: oneOf('listen_reset'), burst_index: burstIndex, next_sequence: sequence,
    reason: oneOf('expired', 'not_eligible', 'invalid_cursor'),
  }),
  ptt_ended: object({
    type: oneOf('ptt_ended'), burst_id: uuid, burst_index: burstIndex,
    state: oneOf('draining', 'sealed'), final_next_sequence: nullable(sequence),
    reason: oneOf('released', 'complete', 'expired', 'canceled', 'lease_expired', 'duration_limit'),
  }),
  pong: object({ type: oneOf('pong'), id: integer(0, MAX_BURST_INDEX), sent_at_ms: timestamp }),
  error: object({
    type: oneOf('error'),
    code: oneOf('invalid_message', 'invalid_channel', 'channel_full', 'not_joined', 'invalid_state', 'server_busy', 'internal_error'),
    message: text(),
  }),
}

export type ServerControl = ReturnType<(typeof serverSchemas)[keyof typeof serverSchemas]>
export type Snapshot = Extract<ServerControl, { type: 'snapshot' }>

function validateJsonKeys(raw: string) {
  // lossless-json permits identical duplicate values and assigns __proto__ specially.
  // Inspect keys before parsing so neither behavior can bypass exact-field validation.
  const stack: (Set<string> | null)[] = []
  for (const token of raw.matchAll(/"(?:[^"\\]|\\.)*"|[{}[\]]/g)) {
    const value = token[0]
    if (value === '{') stack.push(new Set())
    else if (value === '[') stack.push(null)
    else if (value === '}' || value === ']') stack.pop()
    else if (/^\s*:/.test(raw.slice(token.index + value.length))) {
      const key: unknown = JSON.parse(value)
      const keys = stack.at(-1)
      if (typeof key !== 'string' || !keys || keys.has(key) || key === '__proto__') throw new ProtocolError()
      keys.add(key)
    }
  }
}

function validateMessage<S extends Record<string, Validator<unknown>>>(value: unknown, schemas: S): ReturnType<S[keyof S]> {
  if (value === null || typeof value !== 'object' || !('type' in value)
    || typeof value.type !== 'string' || !Object.hasOwn(schemas, value.type)) throw new ProtocolError()
  return schemas[value.type]!(value) as ReturnType<S[keyof S]>
}

export function parseControl(raw: string): ServerControl {
  try {
    if (typeof raw !== 'string' || utf8.encode(raw).length > MAX_MESSAGE_BYTES) throw new ProtocolError()
    validateJsonKeys(raw)
    const result = validateMessage(parse(raw), serverSchemas)
    if (result.type === 'audio_rejected' && result.next_sequence < result.first_sequence) throw new ProtocolError()
    return result
  } catch {
    // Parser diagnostics may contain credentials or channel codes from the input.
    throw new ProtocolError()
  }
}

const clientSchemas = {
  join: object({ type: oneOf('join'), channel: normalChannel }),
  join_echo: object({ type: oneOf('join_echo') }),
  resume: object({ type: oneOf('resume'), resume_token: text(1, 256), generation: integer(2, MAX_BURST_INDEX) }),
  listen: object({ type: oneOf('listen'), burst_index: burstIndex, next_sequence: sequence }),
  ptt_request: object({ type: oneOf('ptt_request'), request_id: requestId }),
  ptt_cancel: object({ type: oneOf('ptt_cancel'), request_id: requestId }),
  burst_end: object({ type: oneOf('burst_end'), burst_id: uuid, final_next_sequence: integer(0, MAX_BURST_FRAMES) }),
  ping: object({ type: oneOf('ping'), id: integer(0, MAX_BURST_INDEX), sent_at_ms: timestamp }),
  disconnect: object({ type: oneOf('disconnect') }),
}
export type ClientControl = ReturnType<(typeof clientSchemas)[keyof typeof clientSchemas]>

export function encodeControl(message: ClientControl): string {
  const raw = stringify(validateMessage(message, clientSchemas))
  if (raw === undefined || utf8.encode(raw).length > MAX_MESSAGE_BYTES) throw new ProtocolError()
  return raw
}

export function normalizeChannel(value: string): string {
  const result = value.toUpperCase()
  try { return channel(result) } catch { throw new Error('Use letters A-Z, numbers, and single periods (up to 256 characters).') }
}

export interface MediaEnvelope {
  burstId: string
  firstSequence: number
  packets: Uint8Array[]
}

function validateMediaHeader(direction: number, first: number, count: number) {
  if ((direction !== UPLINK && direction !== DOWNLINK) || !Number.isInteger(first)
    || first < 0 || first > MAX_SEQUENCE || !Number.isInteger(count) || count < 1
    || count > MAX_PACKETS || first + count - 1 > MAX_SEQUENCE) throw new ProtocolError()
}

export function encodeMedia(direction: number, envelope: MediaEnvelope): Uint8Array {
  const { burstId, firstSequence, packets } = envelope
  uuid(burstId)
  validateMediaHeader(direction, firstSequence, packets.length)
  let size = MEDIA_HEADER_BYTES
  for (const packet of packets) {
    if (!(packet instanceof Uint8Array) || packet.length < 1 || packet.length > MAX_PACKET_BYTES) throw new ProtocolError()
    size += 2 + packet.length
  }
  if (size > MAX_MESSAGE_BYTES) throw new ProtocolError()
  const bytes = new Uint8Array(size)
  const view = new DataView(bytes.buffer)
  bytes[0] = 4
  bytes[1] = direction
  const hex = burstId.replaceAll('-', '')
  for (let i = 0; i < 16; i++) bytes[2 + i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  view.setUint32(18, firstSequence)
  view.setUint16(22, packets.length)
  let offset = MEDIA_HEADER_BYTES
  for (const packet of packets) {
    view.setUint16(offset, packet.length)
    bytes.set(packet, offset + 2)
    offset += 2 + packet.length
  }
  return bytes
}

export function decodeMedia(bytes: Uint8Array, expectedDirection = DOWNLINK): MediaEnvelope {
  if (!(bytes instanceof Uint8Array) || bytes.length <= MEDIA_HEADER_BYTES
    || bytes.length > MAX_MESSAGE_BYTES) throw new ProtocolError()
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const firstSequence = view.getUint32(18)
  const count = view.getUint16(22)
  validateMediaHeader(expectedDirection, firstSequence, count)
  if (bytes[0] !== 4 || bytes[1] !== expectedDirection) throw new ProtocolError()
  const hex = Array.from(bytes.subarray(2, 18), byte => byte.toString(16).padStart(2, '0')).join('')
  const burstId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  const packets: Uint8Array[] = []
  let offset = MEDIA_HEADER_BYTES
  for (let i = 0; i < count; i++) {
    if (offset + 2 > bytes.length) throw new ProtocolError()
    const length = view.getUint16(offset)
    offset += 2
    if (length < 1 || length > MAX_PACKET_BYTES || offset + length > bytes.length) throw new ProtocolError()
    packets.push(bytes.slice(offset, offset + length))
    offset += length
  }
  if (offset !== bytes.length) throw new ProtocolError()
  return { burstId, firstSequence, packets }
}
