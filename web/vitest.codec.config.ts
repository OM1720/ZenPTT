import { defineConfig } from 'vitest/config'

export default defineConfig({ test: { include: ['tests/codec.test.ts'], testTimeout: 15000 } })
