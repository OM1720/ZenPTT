import { expect, test } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] } })

test('two Chrome tabs connect, leave, rejoin, and switch channels', async ({ context }) => {
  const first = await context.newPage()
  const second = await context.newPage()
  const room = `BROWSER.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`
  const pageErrors: string[] = []
  for (const page of [first, second]) {
    page.on('pageerror', error => pageErrors.push(error.message))
    await page.goto('/web/')
    await expect(page.getByRole('button', { name: 'Push to talk' })).toBeDisabled()
    await page.getByLabel('Channel', { exact: true }).fill(room.toLowerCase())
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  }
  for (const page of [first, second]) await expect(page.getByRole('status', { name: 'Participants' })).toContainText('2')
  await second.getByRole('button', { name: 'Disconnect' }).click()
  await expect(first.getByRole('status', { name: 'Participants' })).toContainText('1')
  await second.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(first.getByRole('status', { name: 'Participants' })).toContainText('2')
  const input = second.getByLabel('Channel', { exact: true })
  await input.press('Enter')
  await expect(second.getByRole('status', { name: 'Participants' })).toContainText('2')
  await input.fill('INVALID..CHANNEL')
  await input.press('Enter')
  await expect(second.getByRole('alert')).toContainText('Use letters')
  await expect(first.getByRole('status', { name: 'Participants' })).toContainText('2')
  await input.fill(`${room}.OTHER`)
  await input.press('Enter')
  for (const page of [first, second]) await expect(page.getByRole('status', { name: 'Participants' })).toContainText('1')
  await first.getByRole('button', { name: 'Disconnect' }).click()
  await second.getByRole('button', { name: 'Disconnect' }).click()
  expect(pageErrors).toEqual([])
})

test('channel controls remain usable in a narrow desktop window', async ({ page }) => {
  await page.setViewportSize({ width: 480, height: 800 })
  await page.goto('/web/')
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Use letters')
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Offline')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
