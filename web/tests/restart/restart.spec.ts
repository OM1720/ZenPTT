import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] } })

test('server restart discards the old session and cannot restart a held PTT', async ({ page }) => {
  const members: string[] = []
  page.on('websocket', socket => socket.on('framereceived', ({ payload }) => {
    if (typeof payload !== 'string') return
    const message = JSON.parse(payload) as { type: string; member_id?: string }
    if (message.type === 'snapshot') members.push(message.member_id!)
  }))
  await page.goto('/web/')
  await page.getByLabel('Channel', { exact: true }).fill(`RESTART.${crypto.randomUUID().replaceAll('-', '').toUpperCase()}`)
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled()
  await page.getByRole('heading').focus()
  await page.keyboard.down('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Transmitting')
  await promisify(execFile)('docker', [
    'compose', '-p', process.env.ZENPTT_WEB_TEST_PROJECT!, '--env-file', 'server.local.env',
    '-f', 'compose.yaml', '-f', 'web/compose.test.yaml', 'restart', '--timeout', '1', 'zenptt-server',
  ], { cwd: fileURLToPath(new URL('../../../', import.meta.url)), timeout: 20000 })
  await expect(page.getByRole('alert')).toContainText('Previous session is unavailable.', { timeout: 20000 })
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
  expect(members).toHaveLength(2)
  expect(members[1]).not.toBe(members[0])
  await page.keyboard.up('Space')
  await page.keyboard.down('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Transmitting')
  await page.keyboard.up('Space')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toHaveText('Push to talk')
  await page.getByRole('button', { name: 'Disconnect' }).click()
})
