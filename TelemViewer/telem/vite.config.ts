import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { telemetryPlugin } from './server/telemetryPlugin'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), telemetryPlugin()],
  server: {
    open: true,
  }
})
