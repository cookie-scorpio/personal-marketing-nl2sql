import { computed, ref } from 'vue'
import { apiRequest, clearToken, getToken, setToken, TOKEN_KEY } from './api'
import { encryptPassword } from './passwordCrypto'
import type { CurrentUser, LoginResponse, RegistrationResponse } from './types'

const user = ref<CurrentUser | null>(null)
const restoring = ref(true)
let revision = 0

async function login(username: string, password: string): Promise<void> {
  const epoch = ++revision
  const encryptedPassword = await encryptPassword(password)
  const result = await apiRequest<LoginResponse>('/api/v1/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password: encryptedPassword }),
  })
  if (epoch !== revision) return
  setToken(result.access_token); user.value = result.user
}
async function register(displayName: string, username: string, password: string): Promise<RegistrationResponse> {
  const encryptedPassword = await encryptPassword(password)
  return apiRequest<RegistrationResponse>('/api/v1/auth/register', {
    method: 'POST', body: JSON.stringify({ display_name: displayName, username, password: encryptedPassword }),
  })
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
// 令牌过期（后端401）时与账号切换走同一条恢复路径：回到登录页，而不是停留在假死界面。
window.addEventListener('nl2sql:unauthorized', () => {
  if (user.value) { user.value = null; restoring.value = true; void restore() }
})
export function useAuth() {
  return { user, restoring, authenticated: computed(() => Boolean(user.value)), login, register, restore, logout }
}
