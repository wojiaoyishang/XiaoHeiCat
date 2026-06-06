import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: resolve(__dirname, '../app/src/main/assets/webide'),
    emptyOutDir: true,
    sourcemap: false,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        // Vite 8 / Rolldown no longer accepts the old object form here.
        // Keep it as a function so both Vite 5/6/7 and Vite 8 can build.
        manualChunks(id: string) {
          if (id.includes('node_modules/monaco-editor')) {
            return 'monaco'
          }
          if (id.includes('node_modules')) {
            return 'vendor'
          }
          return undefined
        }
      }
    }
  }
})
