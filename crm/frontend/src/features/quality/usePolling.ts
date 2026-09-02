/** 后台面板共用的轮询逻辑：可开关、页面隐藏时暂停，卸载时清理定时器。 */
import { onBeforeUnmount, ref, unref, type Ref } from 'vue'

/**
 * 进入面板立即加载一次，之后按固定间隔刷新。
 * enabled 只控制周期刷新：停用期间首次加载和 refresh(true) 手动刷新仍会请求。
 * 刷新失败写入 error 供面板内联展示，不弹全局提示，避免轮询失败刷屏。
 */
export function usePolling<T>(loader: () => Promise<T>, intervalMs: number, enabled: Ref<boolean> | boolean = true): {
  data: Ref<T | undefined>
  error: Ref<string>
  loading: Ref<boolean>
  refresh: (force?: boolean) => Promise<void>
} {
  const data = ref<T>()
  const error = ref('')
  const loading = ref(false)
  let refreshing = false

  async function refresh(force = false): Promise<void> {
    // 后台标签页不可见时跳过；停用期间只有显式 force（首次加载或手动刷新）会请求。
    if (refreshing) return
    if (document.visibilityState === 'hidden') return
    if (!force && !unref(enabled)) return
    refreshing = true
    loading.value = true
    try {
      data.value = await loader()
      error.value = ''
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '数据加载失败'
    } finally {
      loading.value = false
      refreshing = false
    }
  }

  // 首次加载视为显式请求，不受 enabled 开关约束。
  void refresh(true)
  const timer = window.setInterval(() => void refresh(), intervalMs)
  onBeforeUnmount(() => window.clearInterval(timer))
  return { data, error, loading, refresh }
}
