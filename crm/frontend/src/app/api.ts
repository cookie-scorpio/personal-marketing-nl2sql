const API_BASE = import.meta.env.VITE_API_BASE_URL || ''
export const apiUrl = (path: string) => `${API_BASE}${path}`
const TOKEN_KEY = 'nl2sql_access_token'

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
  headers.set('X-Request-ID', crypto.randomUUID())
  if (init.method && init.method !== 'GET' && !headers.has('Idempotency-Key')) headers.set('Idempotency-Key', crypto.randomUUID())
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 Spring Boot 已启动。', 0)
  }
  const envelope = (await response.json().catch(() => null)) as ApiEnvelope<T> | null
  if (!response.ok || !envelope || envelope.code !== 0) {
    if (response.status === 401) clearToken()
    throw new ApiError(envelope?.message || '请求处理失败', envelope?.code || response.status, envelope?.request_id)
  }
  return envelope.data
}
