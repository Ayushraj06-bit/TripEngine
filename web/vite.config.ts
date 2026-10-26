import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Where the dev server sends API and WebSocket traffic: the one-command demo's load balancer by
// default, or a backend started on its own.
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // Same-origin in the browser, so no CORS in development.
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/v3/api-docs': { target: backend, changeOrigin: true },
      '/ws': {
        target: backend.replace(/^http/, 'ws'),
        ws: true,
        changeOrigin: true,
        // The backend accepts WebSocket handshakes from its own origin only. The browser sends
        // the dev server's, so the proxy presents the backend's instead - no backend change.
        configure: (proxy) => {
          proxy.on('proxyReqWs', (request) => request.setHeader('origin', backend));
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
