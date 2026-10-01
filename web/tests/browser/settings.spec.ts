import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] } })

test('Settings preserves playback and membership; microphone and history survive reload', async ({ context }) => {
  const sender = await context.newPage(), listener = await context.newPage()
  await listener.addInitScript(() => {
    const probe = { played: 0, joins: 0, contexts: 0 }
    Object.assign(window, { uiProbe: probe })
    const Worklet = window.AudioWorkletNode
    window.AudioWorkletNode = class extends Worklet {
      constructor(...args: ConstructorParameters<typeof AudioWorkletNode>) {
        super(...args)
        probe.contexts++
        this.port.addEventListener('message', event => { if (event.data.type === 'played' && event.data.frame) probe.played++ })
      }
    }
    const Socket = window.WebSocket
    window.WebSocket = class extends Socket {
      send(data: Parameters<WebSocket['send']>[0]) {
        if (typeof data === 'string' && JSON.parse(data).type === 'join') probe.joins++
        super.send(data)
      }
    }
  })
  const room = `SETTINGS.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`
  for (const page of [listener, sender]) {
    await page.goto('/web/')
    await page.getByLabel('Channel', { exact: true }).fill(room)
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
    await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled()
  }
  await sender.getByRole('heading').focus()
  await sender.keyboard.down('Space')
  await expect(listener.locator('.halo')).toHaveAttribute('data-icon', 'volume')
  await listener.getByRole('button', { name: 'Settings', exact: true }).click()
  const readProbe = () => listener.evaluate(() => (window as unknown as { uiProbe: { played: number; joins: number; contexts: number } }).uiProbe)
  const before = (await readProbe()).played
  await expect.poll(async () => (await readProbe()).played).toBeGreaterThan(before + 15)
  await expect(listener.getByLabel('Server', { exact: true })).toHaveAttribute('readonly', '')
  await listener.getByRole('button', { name: 'Ping', exact: true }).click()
  await expect(listener.getByRole('status', { name: 'Ping result' })).toContainText(/Ping: \d+ ms/)
  const select = listener.getByLabel('Microphone', { exact: true })
  const device = await select.locator('option').nth(1).getAttribute('value')
  expect(device).toBeTruthy()
  await select.selectOption(device!)
  await expect(select).toBeEnabled()
  await expect(select).toHaveValue(device!)
  expect(await readProbe()).toMatchObject({ joins: 1, contexts: 1 })
  const download = listener.waitForEvent('download')
  await listener.getByRole('button', { name: 'Download diagnostic report' }).click()
  const report = await readFile((await (await download).path())!, 'utf8')
  expect(report.length).toBeLessThan(65536)
  expect(report).not.toContain(room)
  expect(report).not.toContain(device!)
  expect(report).not.toContain('resume_token')
  await listener.screenshot({ path: '../.cache/web-stage4/settings.png', fullPage: true })
  await listener.getByRole('button', { name: 'Home', exact: true }).click()
  await expect(listener.getByRole('status', { name: 'Participants' })).toHaveText('2')
  await expect(listener.locator('.halo')).toHaveAttribute('data-icon', 'volume')
  await listener.screenshot({ path: '../.cache/web-stage4/main.png', fullPage: true })
  await sender.keyboard.up('Space')
  await sender.getByRole('button', { name: 'Disconnect' }).click()
  await listener.getByRole('button', { name: 'Disconnect' }).click()
  await listener.reload()
  await expect(listener.getByLabel('Channel', { exact: true })).toHaveValue(room)
  await listener.getByRole('button', { name: 'Channel history', exact: true }).click()
  await expect(listener.locator('.history button')).toHaveText([room, 'ECHO'])
  await listener.getByRole('button', { name: 'Settings', exact: true }).click()
  await expect(listener.getByLabel('Microphone', { exact: true })).toHaveValue(device!)
})

test('large text and narrow layouts retain controls; invalid history can be dismissed', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 800 })
  await page.goto('/web/')
  // CSS zoom exercises reflow at a desktop browser's 200% page scale.
  await page.evaluate(() => { document.documentElement.style.zoom = '2' })
  await page.getByLabel('Channel', { exact: true }).fill('INVALID..CHANNEL')
  await page.getByLabel('Channel', { exact: true }).press('Enter')
  await expect(page.getByRole('alert')).toContainText('Use letters')
  await page.getByLabel('Channel', { exact: true }).press('Escape')
  await expect(page.locator('.history')).toHaveCount(0)
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page.getByRole('button', { name: 'Ping', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Ping result' })).toContainText(/Ping: \d+ ms/)
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.screenshot({ path: '../.cache/web-stage4/settings-zoom.png', fullPage: true })
})

test('screen reader activation toggles PTT and Settings always releases the latch', async ({ page }) => {
  await page.goto('/web/')
  await page.getByLabel('Channel', { exact: true }).fill(`LATCH.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  const ptt = page.getByRole('button', { name: 'Push to talk' })
  await expect(ptt).toBeEnabled()
  await ptt.focus()
  await page.keyboard.press('Enter')
  await expect(ptt).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page.getByLabel('Server', { exact: true }).focus()
  await page.keyboard.press('Space')
  await page.getByRole('button', { name: 'Home', exact: true }).click()
  await expect(ptt).toHaveAttribute('aria-pressed', 'false')
  await page.getByRole('button', { name: 'Disconnect' }).click()
})

test('Space cannot activate navigation after returning from Settings', async ({ page }) => {
  await page.goto('/web/')
  await page.getByLabel('Channel', { exact: true }).fill(`FOCUS.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  const ptt = page.getByRole('button', { name: 'Push to talk' })
  await expect(ptt).toBeEnabled()
  for (let i = 0; i < 2; i++) {
    await page.getByRole('button', { name: 'Settings', exact: true }).click()
    const home = page.getByRole('button', { name: 'Home', exact: true })
    await expect(home).toBeFocused()
    await page.keyboard.press('Space')
    await expect(home).toBeVisible()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeFocused()
    await page.keyboard.down('Space')
    await page.keyboard.down('Space')
    await expect(ptt).toHaveAttribute('aria-pressed', 'true')
    await page.keyboard.up('Space')
    await expect(ptt).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible()
  }
  await page.getByLabel('Channel', { exact: true }).focus()
  await page.keyboard.press('Space')
  await expect(ptt).toHaveAttribute('aria-pressed', 'false')
  await page.getByRole('button', { name: 'Disconnect' }).click()
})

test('both screens stay vertical with grouped audio, tooltips, and a build footer', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/web/')
  const channel = (await page.getByLabel('Channel', { exact: true }).boundingBox())!
  const halo = page.getByRole('button', { name: 'Push to talk' })
  const haloBox = (await halo.boundingBox())!
  expect(haloBox.y).toBeGreaterThan(channel.y + channel.height)
  await expect(halo).toHaveAttribute('title', 'Hold the Halo or Space to talk. Release to listen.')
  await expect(page.locator('.topbar button')).toHaveCount(2)
  const settings = page.getByRole('button', { name: 'Settings', exact: true })
  for (const element of [settings, page.getByRole('button', { name: 'Connect', exact: true }), page.getByLabel('Channel', { exact: true })]) {
    await expect(element).toHaveAttribute('title', /.+/)
  }
  await settings.click()
  const sections = page.locator('.settings-row')
  await expect(sections).toHaveCount(3)
  const boxes = await sections.evaluateAll(elements => elements.map(element => {
    const { x, y, width, bottom } = element.getBoundingClientRect(); return { x, y, width, bottom }
  }))
  for (let i = 1; i < boxes.length; i++) {
    expect(boxes[i]!.x).toBe(boxes[0]!.x)
    expect(boxes[i]!.width).toBe(boxes[0]!.width)
    expect(boxes[i]!.y).toBeGreaterThanOrEqual(boxes[i - 1]!.bottom)
  }
  const audioSection = page.getByRole('region', { name: 'Audio', exact: true })
  await expect(audioSection.getByLabel('Microphone', { exact: true })).toBeVisible()
  await expect(audioSection.getByRole('heading', { name: 'Audio output', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Controls' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Web build' })).toHaveCount(0)
  await expect(page.locator('footer')).toContainText('Web build')
  expect(await page.locator('footer').evaluate(element => getComputedStyle(element).fontSize)).toBe('12px')
  expect((await page.locator('footer').boundingBox())!.y).toBeGreaterThanOrEqual(boxes.at(-1)!.bottom)
})
