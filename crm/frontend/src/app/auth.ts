import { computed, ref } from 'vue'
import { apiRequest, clearToken, getToken, setToken, TOKEN_KEY } from './api'
import type { CurrentUser, LoginResponse } from './types'

const user = ref<CurrentUser | null>(null)
const restoring = ref(true)
let revision = 0

async function login(username: string, password: string): Promise<void> {
  const epoch = ++revision
  const result = await apiRequest<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
  if (epoch !== revision) return
  setToken(result.access_token); user.value = result.user
}
async function restore(): Promise<void> {
  const epoch = ++revision
  user.value = null
  try {
    if (!getToken()) return
    const restored = await apiRequest<CurrentUser>('/api/v1/auth/me')
    if (epoch === revision) user.value = restored
  } catch { if (epoch === revision) clearToken() }
  finally { if (epoch === revision) restoring.value = false }
}
function logout(): void { revision++; clearToken(); user.value = null; restoring.value = false }
// 同源标签页共享令牌，但不能共享旧账号的可见消息与待处理请求。
window.addEventListener('storage', event => {
  if (event.key === TOKEN_KEY || event.key === null) { user.value = null; restoring.value = true; void restore() }
})
export function useAuth() {
  return { user, restoring, authenticated: computed(() => Boolean(user.value)), login, restore, logout }
}
