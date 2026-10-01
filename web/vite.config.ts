import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '/web/',
  plugins: [react()],
  define: { 'import.meta.env.VITE_WEB_BUILD': JSON.stringify(`0.1.0-${new Date().toISOString()}`) },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/app/': { target: process.env.ZENPTT_DEV_PROXY ?? 'http://127.0.0.1:8080' },
      '/ws': { target: process.env.ZENPTT_DEV_PROXY ?? 'http://127.0.0.1:8080', ws: true },
      '/health': { target: process.env.ZENPTT_DEV_PROXY ?? 'http://127.0.0.1:8080' },
    },
  },
})
