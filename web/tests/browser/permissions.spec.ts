import { expect, test } from '@playwright/test'

test.use({ launchOptions: { args: ['--use-fake-device-for-media-stream'] } })

test('Chrome microphone denial leaves PTT disabled and the session usable', async ({ page, context }) => {
  await page.goto('/web/')
  await context.grantPermissions([], { origin: new URL(page.url()).origin })
  await page.getByLabel('Channel', { exact: true }).fill('DENIED.TEST')
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('status', { name: 'Connection status' })).toContainText('Connected')
  await expect(page.getByRole('alert')).toContainText('Microphone permission denied')
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeDisabled()
  await context.grantPermissions(['microphone'], { origin: new URL(page.url()).origin })
  await page.getByRole('button', { name: 'Retry audio' }).click()
  await expect(page.getByRole('button', { name: 'Push to talk' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Retry audio' })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('status', { name: 'Participants' })).toHaveText('1')
  await page.getByRole('button', { name: 'Disconnect' }).click()
})
