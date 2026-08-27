import { computed, ref } from 'vue'
import { apiRequest, clearToken, getToken, setToken } from './api'
import type { CurrentUser, LoginResponse } from './types'

const user = ref<CurrentUser | null>(null)
const restoring = ref(true)

export function useAuth() {
  async function login(username: string, password: string): Promise<void> {
    const result = await apiRequest<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    setToken(result.access_token)
    user.value = result.user
  }

  async function restore(): Promise<void> {
    if (!getToken()) {
      restoring.value = false
      return
    }
    try {
      user.value = await apiRequest<CurrentUser>('/api/v1/auth/me')
    } catch {
      clearToken()
    } finally {
      restoring.value = false
    }
  }

  function logout(): void {
    clearToken()
    user.value = null
  }

  return {
    user,
    restoring,
    authenticated: computed(() => Boolean(user.value)),
    login,
    restore,
    logout,
  }
}
