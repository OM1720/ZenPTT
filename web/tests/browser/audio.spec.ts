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

test.use({ launchOptions: { args: [
  '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  `--use-file-for-fake-audio-capture=${microphoneFile}`,
] } })

interface Probe {
  sent: number
  received: number
  beforeGrant: number
  played: number
  grants: number
  rms: () => number
  microphone: () => { enabled: boolean; state: string } | null
  context: () => string
  playing: boolean
  pcm: () => Promise<{ input: string; output: string }>
  resetPcm: () => Promise<void>
}
declare global { interface Window { audioProbe: Probe } }

async function observe(page: Page) {
  await page.addInitScript(() => {
    const probe: Probe = {
      sent: 0, received: 0, beforeGrant: 0, played: 0, grants: 0,
      rms: () => 0, microphone: () => null, context: () => '',
      playing: false, pcm: async () => ({ input: '', output: '' }), resetPcm: async () => {},
    }
    window.audioProbe = probe
    const Socket = window.WebSocket
    window.WebSocket = class extends Socket {
      private granted = false
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols)
        this.addEventListener('message', event => {
          if (typeof event.data !== 'string') { probe.received++; return }
          const message = JSON.parse(event.data) as { type: string }
          if (message.type === 'ptt_granted') { this.granted = true; probe.grants++ }
          if (message.type === 'ptt_ended') this.granted = false
        })
      }
      send(data: Parameters<WebSocket['send']>[0]) {
        if (typeof data !== 'string') { probe.sent++; if (!this.granted) probe.beforeGrant++ }
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
        source.connect(merger, 0, 0)
        return source
      }
    }
    const Worklet = window.AudioWorkletNode
    window.AudioWorkletNode = class extends Worklet {
      constructor(...args: ConstructorParameters<typeof AudioWorkletNode>) {
        super(...args)
        // A test-only silent tap observes the real worklet; it does not replace rendering.
        const observer = args[0].createScriptProcessor(1024, 2, 1)
        merger = args[0].createChannelMerger(2)
        merger.connect(observer)
        observer.connect(args[0].destination)
        this.connect(merger, 0, 1)
        let input: number[] = [], output: number[] = []
        observer.onaudioprocess = event => {
          if (input.length >= args[0].sampleRate * 20) throw new Error('PCM observer budget exceeded')
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
        probe.resetPcm = async () => { input = []; output = [] }
        const analyser = args[0].createAnalyser()
        this.connect(analyser)
        const pcm = new Float32Array(analyser.fftSize)
        probe.rms = () => {
          analyser.getFloatTimeDomainData(pcm)
          return Math.sqrt(pcm.reduce((sum, sample) => sum + sample * sample, 0) / pcm.length)
        }
        this.port.addEventListener('message', event => {
          if ((event.data as { type: string }).type === 'played') probe.played++
          const reply = event.data as { type: string; active: boolean }
          if (reply.type === 'playback') probe.playing = reply.active
        })
      }
    }
  })
}

async function join(page: Page, room: string) {
  await observe(page)
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

test('real AudioWorklet/WASM path streams before release in both directions', async ({ context }) => {
  const first = await context.newPage(), second = await context.newPage()
  const room = `AUDIO.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`
  await join(first, room)
  await join(second, room)
  for (const [sender, receiver] of [[first, second], [second, first]] as const) {
    await sender.evaluate(() => window.audioProbe.resetPcm())
    await receiver.evaluate(() => window.audioProbe.resetPcm())
    await press(sender)
    await expect.poll(() => sender.evaluate(() => window.audioProbe.sent)).toBeGreaterThan(100)
    await expect.poll(() => receiver.evaluate(() => window.audioProbe.rms())).toBeGreaterThan(0.005)
    await expect(sender.getByRole('button', { name: 'Push to talk' })).toHaveText('Transmitting')
    await sender.mouse.up()
    await expect(sender.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
    await expect.poll(() => receiver.evaluate(() => window.audioProbe.playing)).toBe(false)
    const source = await sender.evaluate(() => window.audioProbe.pcm())
    const received = await receiver.evaluate(() => window.audioProbe.pcm())
    assertSignal(trimSilence(unpack(source.input)), trimSilence(unpack(received.output)), rate, 60)
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
  await join(page, 'ECHO')
  for (let round = 0; round < 2; round++) {
    await page.evaluate(() => window.audioProbe.resetPcm())
    const before = await page.evaluate(() => window.audioProbe.sent)
    await press(page)
    await expect.poll(() => page.evaluate(() => window.audioProbe.sent)).toBeGreaterThan(before + 100)
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
