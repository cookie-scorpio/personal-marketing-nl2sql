/** 创建前端根实例并安装全局 UI 组件。 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './app/styles.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')
