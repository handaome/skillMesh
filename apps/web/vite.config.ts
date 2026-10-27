/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // loadEnv 也读 process.env 里同前缀的变量，但类型上不依赖 node——
  // 这个项目没装 @types/node，直接写 process.env 过不了 vue-tsc。
  const env = loadEnv(mode, '.', '')

  return {
    plugins: [vue()],
    server: {
      // 5173 已被本机其他服务占用（详细设计 §8 目录规则以实测为准）
      host: '127.0.0.1',
      port: 5174,
      proxy: {
        // 目标可用环境变量换掉：要在另一套后端上验证前端时，
        // 不用改配置文件——SKILLMESH_API=http://127.0.0.1:8082 pnpm dev
        '/api': {
          target: env.SKILLMESH_API || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
    },
  }
})
