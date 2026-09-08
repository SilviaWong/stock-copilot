import { createApp } from 'vue'
import App from './App.vue'

// 引入 Element Plus 及其样式
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 引入 Element Plus 中文语言包（让日期选择器等显示中文）
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 引入所有图标
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const app = createApp(App)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}

app.use(ElementPlus, {
    locale: zhCn,
})

app.mount('#app')