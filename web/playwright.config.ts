import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  projects: [
    { name: 'browser', testIgnore: '**/audio.spec.ts' },
    { name: 'audio', testMatch: '**/audio.spec.ts' },
  ],
  use: {
    channel: 'chrome',
    baseURL: process.env.ZENPTT_WEB_URL ?? 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
})
