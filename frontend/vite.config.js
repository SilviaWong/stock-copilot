import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    plugins: [vue()],
    server: {
        port: 5173,
        open: true, // 启动时自动打开浏览器
        proxy: {
            // 凡是 /api 开头的请求，代理到 Spring Boot 后端
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
                // rewrite: (path) => path.replace(/^\/api/, '') // 如果你的后端接口本身没有 /api 前缀，可以开启这行
            }
        }
    }
})