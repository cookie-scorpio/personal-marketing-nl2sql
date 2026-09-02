/** 统一处理 API 地址、认证头、错误信封和幂等操作键。 */
const API_BASE = import.meta.env.VITE_API_BASE_URL || ''
export const apiUrl = (path: string) => `${API_BASE}${path}`
export const TOKEN_KEY = 'nl2sql_access_token'

interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  request_id: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: number,
    public readonly requestId?: string,
  ) {
    super(message)
  }
}

/** crypto.randomUUID 只在安全上下文可用；内网常以 http://<IP> 访问，必须提供兜底。 */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 同一业务动作的重试必须复用同一幂等键，避免澄清/取消/确认在网络重试时重复提交。 */
export function operationKey(...parts: (string | number | undefined)[]): string {
  return parts.map(part => String(part ?? '')).join('-').replace(/[^a-zA-Z0-9._:-]/g, '_').slice(0, 120)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 统一注入 JWT、幂等键和请求标识，避免各页面形成不同的接口调用规则。 */
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken()
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  headers.set('X-Request-ID', uuid())
  if (init.method && init.method !== 'GET' && !headers.has('Idempotency-Key')) headers.set('Idempotency-Key', uuid())
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 Spring Boot 已启动。', 0)
  }
  const envelope = (await response.json().catch(() => null)) as ApiEnvelope<T> | null
  // 其他标签页/账号切换后，旧身份请求不得把结果或401副作用带入新账号。
  if (token !== getToken()) throw new ApiError('账号已切换，已丢弃旧账号请求', 409009)
  if (!response.ok || !envelope || envelope.code !== 0) {
    if (response.status === 401) {
      clearToken()
      // 令牌失效后必须回到登录页，不能让界面停留在“已登录”的假死状态。
      window.dispatchEvent(new CustomEvent('nl2sql:unauthorized'))
    }
    throw new ApiError(envelope?.message || '请求处理失败', envelope?.code || response.status, envelope?.request_id)
  }
  return envelope.data
}
