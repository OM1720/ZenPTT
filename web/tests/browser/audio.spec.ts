import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { assertSignal, signal, trimSilence } from '../signal'

// Chrome supplies this deterministic signal through its real getUserMedia pipeline.
const fixtureDirectory = resolve('../.cache/web-audio')
mkdirSync(fixtureDirectory, { recursive: true })
const microphoneFile = resolve(fixtureDirectory, 'microphone.wav')
const rate = 48000, samples = rate * 12
const wav = Buffer.alloc(44 + samples * 2)
wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8)
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22)
wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34)
wav.write('data', 36); wav.writeUInt32LE(samples * 2, 40)
const fixture = signal(rate, 12)
for (let i = 0; i < samples; i++) wav.writeInt16LE(Math.round(32767 * fixture[i]!), 44 + i * 2)
writeFileSync(microphoneFile, wav)

const proxyUrls = (process.env.ZENPTT_WEB_PROXY_URLS ?? '').split(',').filter(Boolean)
test.use({ bypassCSP: proxyUrls.length > 0, launchOptions: { args: [
  '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  `--use-file-for-fake-audio-capture=${microphoneFile}`,
] } })

interface Probe {
  sent: number
  sentFrames: number
  received: number
  receivedFrames: number
  beforeGrant: number
  played: number
  ended: number
  grants: number
  rms: () => number
  microphone: () => { enabled: boolean; state: string } | null
  context: () => string
  playing: boolean
  trace: AudioTrace
  pcm: () => Promise<{ input: string; output: string }>
  resetPcm: () => Promise<void>
}
interface AudioTrace {
  sent: { atMs: number; sequence: number; count: number }[]
  received: { atMs: number; sequence: number; count: number }[]
  played: { atMs: number; sequence: number }[]
  quality: { atMs: number; blocked: boolean; lostFrames: number }[]
  playback: { atMs: number; active: boolean }[]
  observer: { callbacks: number; wallGapMaxMs: number; audioGapMaxMs: number;
    wallGaps: { atMs: number; durationMs: number }[] }
  prebuffer: { startedAtMs: number | null; releasedAtMs: number | null }
}
declare global { interface Window { audioProbe: Probe } }

async function observe(page: Page, proxyUrl?: string) {
  const extraPrebufferMs = Number(process.env.ZENPTT_WEB_PROXY_PREBUFFER_MS ?? 0)
  const lightweightObserver = process.env.ZENPTT_WEB_PROXY_LIGHTWEIGHT === '1'
  await page.addInitScript(({ proxy, prebufferMs, lightweight }:
    { proxy: string | null; prebufferMs: number; lightweight: boolean }) => {
    const emptyTrace = (): AudioTrace => ({ sent: [], received: [], played: [], quality: [],
      playback: [], observer: { callbacks: 0, wallGapMaxMs: 0, audioGapMaxMs: 0, wallGaps: [] },
      prebuffer: { startedAtMs: null, releasedAtMs: null } })
    const probe: Probe = {
      sent: 0, sentFrames: 0, received: 0, receivedFrames: 0,
      beforeGrant: 0, played: 0, ended: 0, grants: 0,
      rms: () => 0, microphone: () => null, context: () => '',
      playing: false, trace: emptyTrace(),
      pcm: async () => ({ input: '', output: '' }), resetPcm: async () => {},
    }
    window.audioProbe = probe
    const Socket = window.WebSocket
    window.WebSocket = class extends Socket {
      private granted = false
      constructor(url: string | URL, protocols?: string | string[]) {
        super(proxy ?? url, protocols)
        this.addEventListener('message', event => {
          if (typeof event.data !== 'string') {
            probe.received++
            if (event.data instanceof ArrayBuffer) {
              const view = new DataView(event.data)
              const count = view.getUint16(22)
              probe.receivedFrames += count
              probe.trace.received.push({ atMs: performance.now(), sequence: view.getUint32(18), count })
            }
            return
          }
          const message = JSON.parse(event.data) as { type: string }
          if (message.type === 'ptt_granted') { this.granted = true; probe.grants++ }
          if (message.type === 'ptt_ended') this.granted = false
        })
      }
      send(data: Parameters<WebSocket['send']>[0]) {
        if (typeof data !== 'string') {
          probe.sent++
          if (data instanceof ArrayBuffer) {
            const view = new DataView(data)
            const count = view.getUint16(22)
            probe.sentFrames += count
            probe.trace.sent.push({ atMs: performance.now(), sequence: view.getUint32(18), count })
          }
          if (!this.granted) probe.beforeGrant++
        }
        super.send(data)
      }
    }
    const Context = window.AudioContext
    let merger: ChannelMergerNode
    window.AudioContext = class extends Context {
      createMediaStreamSource(stream: MediaStream) {
        const track = stream.getAudioTracks()[0]!
        probe.microphone = () => ({ enabled: track.enabled, state: track.readyState })
        probe.context = () => this.state
        const source = super.createMediaStreamSource(stream)
        if (!lightweight) source.connect(merger, 0, 0)
        return source
      }
    }
    const Worklet = window.AudioWorkletNode
    window.AudioWorkletNode = class extends Worklet {
      constructor(...args: ConstructorParameters<typeof AudioWorkletNode>) {
        super(...args)
        if (prebufferMs > 0) {
          const port = this.port, send = port.postMessage.bind(port)
          const held: { type?: string }[] = []
          let started = false, released = false
          Object.defineProperty(port, 'postMessage', { value: (message: { type?: string }) => {
            if (released || (!started && message.type !== 'frame')) { send(message); return }
            held.push(message)
            if (!started) {
              started = true
              probe.trace.prebuffer.startedAtMs = performance.now()
              setTimeout(() => {
                released = true
                probe.trace.prebuffer.releasedAtMs = performance.now()
                for (const queued of held) send(queued)
                held.length = 0
              }, prebufferMs)
            }
          } })
        }
        if (lightweight) probe.resetPcm = async () => { probe.trace = emptyTrace() }
        else {
          // A test-only silent tap observes the real worklet; it does not replace rendering.
          const observer = args[0].createScriptProcessor(1024, 2, 1)
          merger = args[0].createChannelMerger(2)
          merger.connect(observer)
          observer.connect(args[0].destination)
          this.connect(merger, 0, 1)
          let input: number[] = [], output: number[] = []
          let lastWallMs: number | null = null, lastAudioMs: number | null = null
          observer.onaudioprocess = event => {
            if (input.length >= args[0].sampleRate * 20) throw new Error('PCM observer budget exceeded')
            const wallMs = performance.now(), audioMs = event.playbackTime * 1000
            const observed = probe.trace.observer
            observed.callbacks++
            if (lastWallMs !== null && lastAudioMs !== null) {
              const wallGap = wallMs - lastWallMs, audioGap = audioMs - lastAudioMs
              observed.wallGapMaxMs = Math.max(observed.wallGapMaxMs, wallGap)
              observed.audioGapMaxMs = Math.max(observed.audioGapMaxMs, audioGap)
              if (wallGap > 40) observed.wallGaps.push({ atMs: wallMs, durationMs: wallGap })
            }
            lastWallMs = wallMs; lastAudioMs = audioMs
            input.push(...event.inputBuffer.getChannelData(0))
            output.push(...event.inputBuffer.getChannelData(1))
          }
          // Transfer packed PCM: tracing every scalar sample otherwise dominates the test.
          const pack = (values: number[]) => {
            const bytes = new Uint8Array(Float32Array.from(values).buffer)
            let binary = ''
            for (let offset = 0; offset < bytes.length; offset += 8192) {
              binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
            }
            return btoa(binary)
          }
          probe.pcm = async () => ({ input: pack(input), output: pack(output) })
          probe.resetPcm = async () => {
            input = []; output = []; probe.trace = emptyTrace()
            lastWallMs = lastAudioMs = null
          }
        }
        const analyser = args[0].createAnalyser()
        this.connect(analyser)
        const pcm = new Float32Array(analyser.fftSize)
        probe.rms = () => {
          analyser.getFloatTimeDomainData(pcm)
          return Math.sqrt(pcm.reduce((sum, sample) => sum + sample * sample, 0) / pcm.length)
        }
        this.port.addEventListener('message', event => {
          const reply = event.data as { type: string; active: boolean; frame?: boolean;
            cursor?: { nextSequence: number }; blocked?: boolean; lostFrames?: number }
          if (reply.type === 'played' && reply.frame) {
            probe.played++
            probe.trace.played.push({ atMs: performance.now(), sequence: reply.cursor!.nextSequence - 1 })
          }
          if (reply.type === 'played' && !reply.frame) probe.ended++
          if (reply.type === 'quality') probe.trace.quality.push({ atMs: performance.now(),
            blocked: reply.blocked!, lostFrames: reply.lostFrames! })
          if (reply.type === 'playback') {
            probe.playing = reply.active
            probe.trace.playback.push({ atMs: performance.now(), active: reply.active })
          }
        })
      }
    }
  }, { proxy: proxyUrl ?? null, prebufferMs: extraPrebufferMs, lightweight: lightweightObserver })
}

async function join(page: Page, room: string, proxyUrl?: string) {
  await observe(page, proxyUrl)
  await page.goto('/web/')
  await page.getByLabel('Channel', { exact: true }).fill(room)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled({ timeout: 15000 })
  expect(await page.evaluate(() => window.audioProbe.microphone())).toEqual({ enabled: false, state: 'live' })
}

async function press(page: Page) {
  const button = page.getByRole('button', { name: 'Push to talk' })
  const box = await button.boundingBox()
  if (!box) throw new Error('PTT is not visible')
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await expect(button).toHaveText('Transmitting')
}

function unpack(packed: string) {
  const bytes = Buffer.from(packed, 'base64')
  return Array.from({ length: bytes.length / 4 }, (_, i) => bytes.readFloatLE(i * 4))
}

function internalSilence(samples: Float32Array, rate: number) {
  const runs: { startMs: number; durationMs: number }[] = []
  let start = -1
  for (let i = 0; i <= samples.length; i++) {
    if (i < samples.length && Math.abs(samples[i]!) <= 0.002) {
      if (start < 0) start = i
    } else if (start >= 0) {
      if (i - start >= rate / 100) runs.push({ startMs: Math.round(start * 1000 / rate),
        durationMs: Math.round((i - start) * 1000 / rate) })
      start = -1
    }
  }
  return runs
}

test('real AudioWorklet/WASM path streams before release in both directions', async ({ context }) => {
  const diagnosticsDirectory = process.env.ZENPTT_WEB_PROXY_DIAGNOSTICS
  const lightweightObserver = process.env.ZENPTT_WEB_PROXY_LIGHTWEIGHT === '1'
  if (diagnosticsDirectory) test.setTimeout(90000)
  const targetFrames = Number(process.env.ZENPTT_WEB_PROXY_TARGET_FRAMES ?? 100)
  const first = await context.newPage(), second = await context.newPage()
  const room = `AUDIO.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`
  await join(first, room, proxyUrls[0])
  await join(second, room, proxyUrls[1])
  const metrics: object[] = []
  for (const [sender, receiver] of [[first, second], [second, first]] as const) {
    const sentBefore = await sender.evaluate(() => window.audioProbe.sent)
    const sentFramesBefore = await sender.evaluate(() => window.audioProbe.sentFrames)
    const receivedBefore = await receiver.evaluate(() => window.audioProbe.received)
    const receivedFramesBefore = await receiver.evaluate(() => window.audioProbe.receivedFrames)
    const playedBefore = await receiver.evaluate(() => window.audioProbe.played)
    const endedBefore = await receiver.evaluate(() => window.audioProbe.ended)
    await sender.evaluate(() => window.audioProbe.resetPcm())
    await receiver.evaluate(() => window.audioProbe.resetPcm())
    await press(sender)
    await expect.poll(() => sender.evaluate(() => window.audioProbe.sentFrames),
      { timeout: targetFrames * 50 + 10000 }).toBeGreaterThan(sentFramesBefore + targetFrames)
    await expect.poll(() => receiver.evaluate(() => window.audioProbe.rms())).toBeGreaterThan(0.005)
    await expect(sender.getByRole('button', { name: 'Push to talk' })).toHaveText('Transmitting')
    await sender.mouse.up()
    await expect(sender.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
    await expect.poll(() => receiver.evaluate(() => window.audioProbe.ended),
      { timeout: 30000 }).toBeGreaterThan(endedBefore)
    await expect.poll(() => receiver.evaluate(() => window.audioProbe.playing)).toBe(false)
    const source = await sender.evaluate(() => window.audioProbe.pcm())
    const received = await receiver.evaluate(() => window.audioProbe.pcm())
    const input = trimSilence(unpack(source.input)), output = trimSilence(unpack(received.output))
    const direction = sender === first ? 'A-to-B' : 'B-to-A'
    const inputSilence = internalSilence(input, rate), outputSilence = internalSilence(output, rate)
    const senderTrace = await sender.evaluate(() => window.audioProbe.trace)
    const receiverTrace = await receiver.evaluate(() => window.audioProbe.trace)
    let signalError: string | null = null
    if (!lightweightObserver) {
      try { assertSignal(input, output, rate, 60) } catch (error) { signalError = String(error) }
    }
    metrics.push({ direction,
      mediaMessages: await sender.evaluate(() => window.audioProbe.sent) - sentBefore,
      sentFrames: await sender.evaluate(() => window.audioProbe.sentFrames) - sentFramesBefore,
      receivedMessages: await receiver.evaluate(() => window.audioProbe.received) - receivedBefore,
      receivedFrames: await receiver.evaluate(() => window.audioProbe.receivedFrames) - receivedFramesBefore,
      playedFrames: await receiver.evaluate(() => window.audioProbe.played) - playedBefore,
      inputSamples: input.length, outputSamples: output.length,
      inputInternalSilenceMs: inputSilence.reduce((sum, run) => sum + run.durationMs, 0),
      outputInternalSilenceMs: outputSilence.reduce((sum, run) => sum + run.durationMs, 0),
      queueBlockEvents: receiverTrace.quality.filter(event => event.blocked).length,
      observerWallGapMaxMs: Math.round(receiverTrace.observer.wallGapMaxMs), signalError })
    if (diagnosticsDirectory) {
      writeFileSync(resolve(diagnosticsDirectory, `${direction}-trace.json`),
        JSON.stringify({ sender: senderTrace, receiver: receiverTrace, inputSilence, outputSilence }, null, 2))
      if (!lightweightObserver) {
        writeFileSync(resolve(diagnosticsDirectory, `${direction}-input.f32le`), Buffer.from(input.buffer))
        writeFileSync(resolve(diagnosticsDirectory, `${direction}-output.f32le`), Buffer.from(output.buffer))
      }
    }
    if (process.env.ZENPTT_WEB_PROXY_METRICS) {
      writeFileSync(process.env.ZENPTT_WEB_PROXY_METRICS, JSON.stringify(metrics, null, 2))
    }
    if (signalError && !process.env.ZENPTT_WEB_PROXY_METRICS) throw new Error(signalError)
  }
  for (const page of [first, second]) {
    expect(await page.evaluate(() => window.audioProbe.beforeGrant)).toBe(0)
    expect(await page.evaluate(() => window.audioProbe.played)).toBeGreaterThan(0)
    await page.getByRole('button', { name: 'Disconnect' }).click()
    await expect.poll(() => page.evaluate(() => window.audioProbe.microphone()?.state)).toBe('ended')
    await expect.poll(() => page.evaluate(() => window.audioProbe.context())).toBe('closed')
  }
})

test('ECHO bot decodes browser Opus and returns audible PCM through the worklet', async ({ page }) => {
  await join(page, 'ECHO', proxyUrls[0])
  for (let round = 0; round < 2; round++) {
    await page.evaluate(() => window.audioProbe.resetPcm())
    const before = await page.evaluate(() => window.audioProbe.sentFrames)
    await press(page)
    await expect.poll(() => page.evaluate(() => window.audioProbe.sentFrames)).toBeGreaterThan(before + 100)
    await page.mouse.up()
    await expect.poll(() => page.evaluate(() => window.audioProbe.rms()), { timeout: 15000 }).toBeGreaterThan(0.005)
    await expect.poll(() => page.evaluate(() => window.audioProbe.playing)).toBe(false)
    expect(await page.evaluate(() => window.audioProbe.beforeGrant)).toBe(0)
    await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
    const pcm = await page.evaluate(() => window.audioProbe.pcm())
    assertSignal(trimSilence(unpack(pcm.input)), trimSilence(unpack(pcm.output)), rate, 80)
  }
  await page.getByRole('button', { name: 'Disconnect' }).click()
})
