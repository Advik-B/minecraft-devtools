import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    tailwindcss(),
    svelte()
  ],
  server: {
    port: 5173,
    proxy: {
      '/v1': {
        target: 'http://127.0.0.1:25580',
        changeOrigin: true,
        ws: true
      },
      '/mcp': {
        target: 'http://127.0.0.1:25580',
        changeOrigin: true
      },
      '/docs': {
        target: 'http://127.0.0.1:25580',
        changeOrigin: true
      },
      '/openapi.json': {
        target: 'http://127.0.0.1:25580',
        changeOrigin: true
      }
    }
  }
})
