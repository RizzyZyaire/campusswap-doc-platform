import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发端口固定 5173（UI_UX_SPECIFICATION §6.1 与 CORS 白名单都按它写死）
// /api 走 dev 代理转发到后端 10087：开发期同源、无预检，后端 CorsConfig 仍保留作为直连兜底。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    strictPort: true,
    // 忽略编辑器/工具写入时的临时文件：DSH 的文件写入会在目标旁建 `.xxx.tmpdir/`，
    // 而 chokidar 去 watch 它时可能撞上 EBUSY 并把 dev server 直接崩掉（2026-09-23 实测）。
    watch: {
      ignored: ['**/.*.tmpdir/**', '**/*.tmp', '**/*.tmpdir/**']
    },
    proxy: {
      '/api': {
        target: 'http://localhost:10087',
        changeOrigin: true
      },
      '/uploads': {
        target: 'http://localhost:10087',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900
  }
})
