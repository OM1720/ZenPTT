import { expect, test } from '@playwright/test'

test('Main shows a centered name and right footer download with the hosted APK version', async ({ page }) => {
  await page.route('**/app/latest', route => route.fulfill({ json: { version_name: '0.14.1', version_code: 73 } }))
  await page.goto('/web/')
  const heading = page.getByRole('heading', { name: 'ZenPTT', exact: true })
  await expect(heading).toBeVisible()
  const left = (await page.getByRole('button', { name: 'Connect', exact: true }).boundingBox())!
  const right = (await page.getByRole('button', { name: 'Settings', exact: true }).boundingBox())!
  const title = (await heading.boundingBox())!
  expect(title.x).toBeGreaterThan(left.x + left.width)
  expect(title.x + title.width).toBeLessThan(right.x)
  expect(Math.abs(title.x + title.width / 2 - (left.x + right.x + right.width) / 2)).toBeLessThan(1)
  const link = page.getByRole('link', { name: 'Android app · 0.14.1' })
  await expect(link).toHaveAttribute('title', 'Android app · 0.14.1')
  await expect(link).toHaveAttribute('href', '/app/releases/73/download')
  await expect(link.locator('svg')).toHaveCount(1)
  const footer = (await page.locator('.main-footer').boundingBox())!, icon = (await link.boundingBox())!
  expect(icon.x + icon.width).toBe(footer.x + footer.width)
  expect(footer.y).toBeGreaterThan((await page.locator('.ptt').boundingBox())!.y)
  await page.screenshot({ path: '../.cache/web-home/main.png', fullPage: true })
})

test('missing or invalid release metadata leaves a usable download without a false version', async ({ page }) => {
  for (const response of [{ status: 503, body: 'Unavailable' }, { json: { version_name: '<bad>', version_code: -1 } }]) {
    await page.route('**/app/latest', route => route.fulfill(response))
    await page.goto('/web/')
    const link = page.getByRole('link', { name: 'Android app · version unavailable' })
    await expect(link).toHaveAttribute('href', '/app/download')
    await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeEnabled()
    await expect(page.getByRole('alert')).toHaveCount(0)
    await page.unroute('**/app/latest')
  }
})
