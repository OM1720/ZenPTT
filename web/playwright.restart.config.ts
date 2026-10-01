import { defineConfig } from '@playwright/test'
import config from './playwright.config'

if (process.env.ZENPTT_WEB_TEST_PROJECT !== 'zenptt-web-gate'
  || process.env.ZENPTT_WEB_URL !== 'http://127.0.0.1:18081') {
  throw new Error('Run restart checks through scripts/test-web.ps1 on the isolated production test stack.')
}

export default defineConfig({
  ...config,
  testDir: './tests/restart',
  projects: [{ name: 'restart' }],
  timeout: 50000,
  use: config.use,
})
