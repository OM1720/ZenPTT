// Binds the pinned Opus WASM module to fixed-size encode and decode frames.
import { MAX_PACKET_BYTES } from '../mediaLimits'

const FRAME_INPUT = 320
const FRAME_OUTPUT = 960

interface OpusExports extends WebAssembly.Exports {
  memory: WebAssembly.Memory
  _initialize: () => void
  zen_init: () => number
  zen_input: () => number
  zen_output: () => number
  zen_packet: () => number
  zen_encode: () => number
  zen_decode: (size: number) => number
  zen_encoder_reset: () => number
  zen_decoder_reset: () => number
}

export class OpusCodec {
  private readonly api: OpusExports
  private readonly input: Float32Array
  private readonly output: Float32Array
  private readonly packet: Uint8Array

  constructor(module: WebAssembly.Module) {
    const fail = () => { throw new Error('Opus runtime failed') }
    this.api = new WebAssembly.Instance(module, {
      // libopus links libc diagnostics; an audio worklet has no filesystem or stdout.
      wasi_snapshot_preview1: { fd_close: fail, fd_write: fail, fd_seek: fail },
    }).exports as OpusExports
    this.api._initialize()
    if (this.api.zen_init() !== 0) fail()
    this.input = new Float32Array(this.api.memory.buffer, this.api.zen_input(), FRAME_INPUT)
    this.output = new Float32Array(this.api.memory.buffer, this.api.zen_output(), FRAME_OUTPUT)
    this.packet = new Uint8Array(this.api.memory.buffer, this.api.zen_packet(), MAX_PACKET_BYTES)
  }

  resetEncoder() { if (this.api.zen_encoder_reset() !== 0) throw new Error('Opus encoder reset failed') }
  resetDecoder() { if (this.api.zen_decoder_reset() !== 0) throw new Error('Opus decoder reset failed') }

  encode(pcm: Float32Array): Uint8Array {
    if (pcm.length !== FRAME_INPUT) throw new Error('Invalid PCM frame')
    this.input.set(pcm)
    const size = this.api.zen_encode()
    if (size < 1 || size > MAX_PACKET_BYTES) throw new Error('Opus encoding failed')
    return this.packet.slice(0, size)
  }

  decode(packet: Uint8Array | null): Float32Array {
    if (packet && (packet.length < 1 || packet.length > MAX_PACKET_BYTES)) throw new Error('Invalid Opus packet')
    if (packet) this.packet.set(packet)
    if (this.api.zen_decode(packet?.length ?? 0) !== FRAME_OUTPUT) throw new Error('Invalid mono 20 ms Opus frame')
    return this.output.slice()
  }
}
