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
    // 显式双栈监听（2026-10-09 实测修）：
    // 只写 port 时 Vite/Node 会把 dev server 绑在 [::1] 上，而本机 hosts 里有
    // "127.0.0.1 localhost"，浏览器与 curl 会优先用 IPv4 解析 localhost →
    // http://localhost:5173 与 http://127.0.0.1:5173 全部连接被拒
    // （同期后端 Spring 绑的是 0.0.0.0 + [::]，所以只有前端打不开，极易误判成"前端没起来"）。
    // '::' = IPv6 通配，Windows 下默认允许 IPv4 映射，两种写法都能连；副作用是同网段可访问，
    // 与本项目后端（0.0.0.0:10087）一致，演示时反而方便老师用手机/另一台机器打开。
    host: '::',
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
