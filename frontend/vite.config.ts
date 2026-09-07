import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Dev: `npm run dev` proxies the API to the Spring app (`SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`).
// Build: `dist/` is copied into the jar under `static/` by Gradle (see build.gradle).
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
  build: { outDir: 'dist', emptyOutDir: true, sourcemap: false },
})
