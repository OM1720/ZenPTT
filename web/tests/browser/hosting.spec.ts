import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'

test('production Caddy serves the web build with scoped CSP, MIME types and caching', async ({ page, request }) => {
  test.skip(!process.env.ZENPTT_WEB_HOSTING, 'Requires production files through Caddy')
  const home = await request.get('/')
  expect(home.status()).toBe(200)
  expect(home.headers()['content-security-policy']).toContain('wasm-unsafe-eval')
  expect(home.headers()['cache-control']).toBe('no-cache')
  const redirect = await request.get('/web', { maxRedirects: 0 })
  expect(redirect.status()).toBe(308)
  expect(redirect.headers().location).toBe('/web/')
  const html = await request.get('/web/')
  expect(html.status()).toBe(200)
  const htmlText = await html.text()
  expect(await home.text()).toBe(htmlText)
  expect(htmlText).toContain('<link rel="manifest" href="/web/manifest.webmanifest"')
  expect(htmlText).toContain('<link rel="apple-touch-icon" href="/web/icons/apple-touch-icon.png?v=2"')
  expect(html.headers()['cache-control']).toBe('no-cache')
  const policy = html.headers()['content-security-policy']!
  expect(policy).toContain("script-src 'self' 'wasm-unsafe-eval'")
  expect(policy).toContain("connect-src 'self'")
  expect(policy).toContain("style-src 'self'")
  expect(policy).toContain("manifest-src 'self'")
  expect(policy).toContain("img-src 'self'")
  expect(policy).not.toContain("'unsafe-inline'")
  expect(policy).not.toContain("'unsafe-eval'")
  const manifestResponse = await request.get('/web/manifest.webmanifest')
  expect(manifestResponse.status()).toBe(200)
  expect(manifestResponse.headers()['content-type']).toContain('application/manifest+json')
  expect(manifestResponse.headers()['cache-control']).toBe('no-cache')
  expect(await manifestResponse.json()).toEqual({
    id: '/web/', name: 'ZenPTT', short_name: 'ZenPTT', start_url: '/web/', scope: '/', display: 'standalone',
    background_color: '#ffffff', theme_color: '#ffffff',
    icons: [
      { src: '/web/icons/zenptt-192.png?v=2', sizes: '192x192', type: 'image/png', purpose: 'any maskable' },
      { src: '/web/icons/zenptt-512.png?v=2', sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
    ],
  })
  for (const [path, size] of [
    ['/web/icons/apple-touch-icon.png', 180],
    ['/web/icons/zenptt-192.png', 192],
    ['/web/icons/zenptt-512.png', 512],
  ] as const) {
    const response = await request.get(path)
    const body = await response.body()
    expect(response.status(), path).toBe(200)
    expect(response.headers()['content-type']).toBe('image/png')
    expect(response.headers()['cache-control']).toBe('no-cache')
    expect(body.subarray(1, 4).toString()).toBe('PNG')
    expect(body.readUInt32BE(16)).toBe(size)
    expect(body.readUInt32BE(20)).toBe(size)
  }
  const violations: string[] = [], failures: string[] = []
  page.on('console', message => { if (message.type() === 'error' && message.text().includes('Content Security Policy')) violations.push(message.text()) })
  page.on('pageerror', error => failures.push(error.message))
  const assets = [...htmlText.matchAll(/(?:src|href)="(\/web\/assets\/[^" ]+)"/g)].map(match => match[1]!)
  expect(assets.length).toBeGreaterThanOrEqual(2)
  // The entry module references both the worklet and the WASM codec.
  const entry = await request.get(assets.find(path => path.endsWith('.js'))!)
  const bundled = [...(await entry.text()).matchAll(/\/web\/assets\/[A-Za-z0-9_-]+\.(?:js|wasm)/g)].map(match => match[0])
  expect(bundled.some(path => path.endsWith('.wasm'))).toBe(true)
  expect(bundled.some(path => path.includes('processor-'))).toBe(true)
  for (const asset of new Set([...assets, ...bundled])) {
    const response = await request.get(asset)
    expect(response.status(), asset).toBe(200)
    expect(response.headers()['cache-control']).toBe('public, max-age=31536000, immutable')
    const mime = asset.endsWith('.wasm') ? 'application/wasm' : asset.endsWith('.css') ? 'text/css' : /(?:text|application)\/javascript/
    expect(response.headers()['content-type']).toMatch(mime)
    expect(response.headers()['x-content-type-options']).toBe('nosniff')
    const cached = await request.get(asset, { headers: { 'If-None-Match': response.headers().etag! } })
    expect(cached.status()).toBe(304)
  }
  for (const license of ['OPUS-LICENSE.txt', 'ICONS-LICENSE.txt', 'THIRD-PARTY-NOTICES.txt']) {
    const response = await request.get(`/web/licenses/${license}`)
    expect(response.status()).toBe(200)
    expect((await response.body()).length).toBeGreaterThan(100)
  }
  const missing = await request.get('/web/assets/missing-12345678.js')
  expect(missing.status()).toBe(404)
  expect(missing.headers()['cache-control'] ?? '').not.toContain('immutable')
  await page.goto('/')
  await expect(page).toHaveURL(new URL('/', page.url()).href)
  await expect(page.getByRole('heading', { name: 'ZenPTT' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible()
  const metadata = await request.get('/app/latest')
  if (metadata.ok()) {
    const release = await metadata.json() as { version_name: string; version_code: number; sha256: string }
    const android = page.getByRole('link', { name: `Android app · ${release.version_name}` })
    await expect(android).toHaveAttribute('href', `/app/releases/${release.version_code}/download`)
    const apkDownload = page.waitForEvent('download')
    await android.click()
    const stream = await (await apkDownload).createReadStream()
    const hash = createHash('sha256')
    for await (const chunk of stream) hash.update(chunk)
    expect(hash.digest('hex')).toBe(release.sha256)
    await expect(page.getByRole('heading', { name: 'ZenPTT' })).toBeVisible()
  }
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download diagnostic report' }).click()
  expect((await download).suggestedFilename()).toBe('zenptt-web-diagnostics.json')
  expect(violations).toEqual([])
  expect(failures).toEqual([])
})
