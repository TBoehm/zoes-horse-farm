import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  base: './',
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 1500,
  },
  plugins: [
    VitePWA({
      // Kein skipWaiting/clientsClaim: eine neue Version wartet, bis alle Tabs geschlossen sind
      // (Regel 5). Es gibt bewusst keinen Update-Hinweis.
      registerType: 'prompt',
      injectRegister: 'script',
      includeAssets: ['icon.svg', 'generated/*.png'],
      manifest: {
        name: "Zoe's Horse Farm",
        short_name: 'Horse Farm',
        description: 'Springreiten üben im Browser',
        lang: 'de',
        start_url: './',
        scope: './',
        display: 'standalone',
        orientation: 'landscape',
        background_color: '#2f5f2b',
        theme_color: '#3f7d3a',
        icons: [
          { src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: 'generated/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'generated/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          {
            src: 'generated/icon-maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,webmanifest}'],
        skipWaiting: false,
        clientsClaim: false,
        cleanupOutdatedCaches: true,
        navigateFallback: 'index.html',
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
      },
    }),
  ],
});
