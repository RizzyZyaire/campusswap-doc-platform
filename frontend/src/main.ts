import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import { useUserStore } from './stores/user'

import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 主题：进入应用先按 localStorage 应用一次（theme.css 的变量挂在 html[data-theme] 上）
useThemeStore(pinia).apply()

// 401 统一出口：request.ts 检测到未登录会广播该事件，这里跳登录页并带上来路
window.addEventListener('app:unauthorized', () => {
  const user = useUserStore(pinia)
  user.clear()
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

app.mount('#app')
