import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] } })
type Cut = 'grant' | 'ack' | 'end' | 'downlink'

async function faults(page: Page, cut: Cut, outageMs = 0) {
  let cutDone = false, blockedUntil = 0, resumes = 0, duplicates = 0, received = 0
  const packets = new Map<string, Buffer>()
  await page.routeWebSocket('**/ws', route => {
    const server = route.connectToServer()
    const interrupt = () => {
      cutDone = true
      blockedUntil = Date.now() + outageMs
      void route.close({ code: 1001, reason: 'Local recovery test' })
      void server.close({ code: 4000, reason: 'Local recovery test' })
    }
    if (Date.now() < blockedUntil) {
      void route.close({ code: 1001 })
      void server.close({ code: 4000 })
      return
    }
    route.onMessage(message => {
      if (typeof message === 'string') {
        if ((JSON.parse(message) as { type: string }).type === 'resume') resumes++
      } else {
        const id = message.subarray(2, 18).toString('hex')
        const first = message.readUInt32BE(18), count = message.readUInt16BE(22)
        let offset = 24
        for (let i = 0; i < count; i++) {
          const size = message.readUInt16BE(offset)
          const packet = message.subarray(offset + 2, offset + 2 + size)
          const key = `${id}:${first + i}`
          const previous = packets.get(key)
          if (previous) { expect(packet).toEqual(previous); duplicates++ }
          else packets.set(key, Buffer.from(packet))
          offset += size + 2
        }
      }
      server.send(message)
    })
    server.onMessage(message => {
      if (typeof message === 'string') {
        const control = JSON.parse(message) as { type: string; next_sequence?: number }
        if (!cutDone && ((cut === 'grant' && control.type === 'ptt_granted')
          || (cut === 'ack' && control.type === 'uplink_ack' && control.next_sequence! >= 5)
          || (cut === 'end' && control.type === 'ptt_ended'))) { interrupt(); return }
      } else {
        received++
        if (!cutDone && cut === 'downlink' && received >= 4) {
          route.send(message)
          interrupt()
          return
        }
      }
      route.send(message)
    })
  })
  return { cutDone: () => cutDone, resumes: () => resumes, duplicates: () => duplicates, packets: () => packets.size,
    restore: () => { blockedUntil = 0 } }
}

async function join(page: Page, room: string) {
  await page.goto('/web/')
  await page.getByLabel('Channel', { exact: true }).fill(room)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled()
  await page.getByRole('heading').focus()
}
async function press(page: Page) {
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled()
  await page.keyboard.down('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Transmitting')
}
async function release(page: Page) {
  await page.keyboard.up('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
}

for (const cut of ['grant', 'ack', 'end'] as const) {
  test(`real server resumes after a break at ${cut}`, async ({ page }) => {
    const fault = await faults(page, cut)
    await join(page, `RECOVERY.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
    await press(page)
    await expect.poll(fault.packets).toBeGreaterThan(15)
    await release(page)
    await expect.poll(fault.resumes).toBe(1)
    expect(fault.cutDone()).toBe(true)
    if (cut === 'ack') expect(fault.duplicates()).toBeGreaterThan(0)
    await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
    await press(page)
    await release(page)
    await page.getByRole('button', { name: 'Disconnect' }).click()
  })
}

test('a resumed listener does not render queued frames twice', async ({ context }) => {
  const sender = await context.newPage(), listener = await context.newPage()
  let finalSequence: number | undefined
  let burstIndex: number | undefined
  let acknowledged = 0
  sender.on('websocket', socket => socket.on('framereceived', ({ payload }) => {
    if (typeof payload !== 'string') return
    const message = JSON.parse(payload) as { type: string; burst_index?: number; next_sequence?: number; final_next_sequence?: number }
    if (message.type === 'ptt_granted') burstIndex = message.burst_index
    if (message.type === 'uplink_ack') acknowledged = Math.max(acknowledged, message.next_sequence!)
    if (message.type === 'ptt_ended') finalSequence = message.final_next_sequence
  }))
  const fault = await faults(listener, 'downlink')
  await listener.addInitScript(() => {
    const played: string[] = []
    Object.assign(window, { recoveryPlayed: played })
    const Worklet = window.AudioWorkletNode
    window.AudioWorkletNode = class extends Worklet {
      constructor(...args: ConstructorParameters<typeof AudioWorkletNode>) {
        super(...args)
        this.port.addEventListener('message', event => {
          const message = event.data as { type: string; frame: boolean; cursor: { burstIndex: number; nextSequence: number } }
          if (message.type === 'played' && message.frame) played.push(`${message.cursor.burstIndex}:${message.cursor.nextSequence}`)
        })
      }
    }
  })
  const room = `LISTENER.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`
  await join(listener, room)
  await join(sender, room)
  await press(sender)
  await expect.poll(fault.resumes).toBe(1)
  await expect.poll(() => listener.evaluate(() => (window as unknown as { recoveryPlayed: string[] }).recoveryPlayed.length)).toBeGreaterThan(20)
  await release(sender)
  await expect.poll(() => finalSequence).toBeGreaterThan(20)
  await expect.poll(() => acknowledged).toBe(finalSequence)
  const expected = Array.from({ length: finalSequence! }, (_, i) => `${burstIndex}:${i + 1}`)
  await expect.poll(() => listener.evaluate(() => (window as unknown as { recoveryPlayed: string[] }).recoveryPlayed)).toEqual(expected)
  await expect(listener.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
  const played = await listener.evaluate(() => (window as unknown as { recoveryPlayed: string[] }).recoveryPlayed)
  expect(played).toEqual(expected)
  await listener.getByRole('button', { name: 'Disconnect' }).click()
  await sender.getByRole('button', { name: 'Disconnect' }).click()
})

test('an outage beyond capture horizon requires a new physical press', async ({ page }) => {
  test.setTimeout(45000)
  const fault = await faults(page, 'ack', 6000)
  await join(page, `HORIZON.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await press(page)
  await expect.poll(fault.cutDone).toBe(true)
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk', { timeout: 20000 })
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  expect(fault.resumes()).toBe(1)
  await page.keyboard.up('Space')
  await press(page)
  await page.keyboard.up('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk', { timeout: 10000 })
  await page.getByRole('button', { name: 'Disconnect' }).click()
})

test('mouse and Space share a hold; blur and typing cannot leave PTT active', async ({ page }) => {
  await join(page, `INPUT.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await press(page)
  const button = page.getByRole('button', { name: 'Push to talk' })
  const box = (await button.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.keyboard.up('Space')
  await expect(button).toHaveText('Transmitting')
  await page.mouse.up()
  await expect(button).toHaveText('Push to talk')
  await page.getByLabel('Channel', { exact: true }).focus()
  await page.keyboard.press('Space')
  await expect(button).toHaveText('Push to talk')
  await page.getByRole('heading').focus()
  await press(page)
  await page.evaluate(() => window.dispatchEvent(new Event('blur')))
  await expect(button).toHaveText('Push to talk')
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(button).toHaveText('Push to talk')
  await page.keyboard.up('Space')
  await page.getByRole('button', { name: 'Disconnect' }).click()
})

test('exhausted recovery releases audio and needs an explicit new connection', async ({ page }) => {
  const fault = await faults(page, 'ack', 60000)
  await page.addInitScript(() => {
    const Context = window.AudioContext
    window.AudioContext = class extends Context {
      createMediaStreamSource(stream: MediaStream) {
        Object.assign(window, { recoveryResources: () => ({ context: this.state, track: stream.getAudioTracks()[0]!.readyState }) })
        return super.createMediaStreamSource(stream)
      }
    }
  })
  await join(page, `EXPIRED.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await press(page)
  await expect(page.getByRole('alert')).toHaveText('Recovery time expired. Connect again.', { timeout: 20000 })
  await expect.poll(() => page.evaluate(() => (window as unknown as {
    recoveryResources: () => { context: string; track: string }
  }).recoveryResources())).toEqual({ context: 'closed', track: 'ended' })
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeDisabled()
  fault.restore()
  await page.keyboard.up('Space')
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await page.getByRole('heading').focus()
  await press(page)
  await release(page)
  await page.getByRole('button', { name: 'Disconnect' }).click()
})
