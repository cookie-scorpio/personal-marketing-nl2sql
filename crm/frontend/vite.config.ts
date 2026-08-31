import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const fileEnv = loadEnv(mode, '.', 'API_')
  // 启动命令显式传入的代理目标优先，便于隔离验收或并行运行不同后端实例。
  const runtimeEnv = (globalThis as unknown as { process?: { env?: Record<string, string | undefined> } }).process?.env
  const apiTarget = runtimeEnv?.API_PROXY_TARGET || fileEnv.API_PROXY_TARGET || 'http://127.0.0.1:8080'
  return {
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // 图表与组件库独立缓存，避免每次业务代码变化都让浏览器重新下载全部依赖。
        manualChunks: {
          'vendor-vue': ['vue'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-echarts': ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
        },
      },
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/actuator': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  }
})
