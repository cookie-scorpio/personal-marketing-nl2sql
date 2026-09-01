import { apiRequest } from './api'
import type { EncryptedPassword, PasswordPublicKey } from './types'

let cachedKey: Promise<PasswordPublicKey> | null = null

function requireWebCrypto(): SubtleCrypto {
  if (!window.isSecureContext || !window.crypto?.subtle) {
    throw new Error('当前浏览器环境不支持密码加密。本机请通过 localhost 访问；其他地址请先配置 HTTPS。')
  }
  return window.crypto.subtle
}

function base64ToBytes(value: string): ArrayBuffer {
  const binary = window.atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
  return bytes.buffer
}

function bytesToBase64(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return window.btoa(binary)
}

async function loadPublicKey(): Promise<PasswordPublicKey> {
  if (!cachedKey) {
    cachedKey = apiRequest<PasswordPublicKey>('/api/v1/auth/public-key').catch(error => {
      cachedKey = null
      throw error
    })
  }
  return cachedKey
}

/** 密码只在本函数内以明文存在；发送给 API 前已转为 RSA-OAEP-256 密文。 */
export async function encryptPassword(password: string): Promise<EncryptedPassword> {
  const subtle = requireWebCrypto()
  const keyInfo = await loadPublicKey()
  if (keyInfo.algorithm !== 'RSA-OAEP-256') throw new Error('服务端密码加密算法不受当前客户端支持。')
  const publicKey = await subtle.importKey(
    'spki',
    base64ToBytes(keyInfo.public_key),
    { name: 'RSA-OAEP', hash: 'SHA-256' },
    false,
    ['encrypt'],
  )
  const ciphertext = await subtle.encrypt({ name: 'RSA-OAEP' }, publicKey, new TextEncoder().encode(password))
  return { key_id: keyInfo.key_id, encrypted_password: bytesToBase64(ciphertext) }
}

export function clearPasswordPublicKey(): void {
  cachedKey = null
}
