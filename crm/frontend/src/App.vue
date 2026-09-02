<script setup lang="ts">
/**
 * 应用外壳只管理当前身份、可访问页面和侧栏生命周期。
 * 角色可见性来自 JWT 中后端签发的 available_roles，切换时仍需后端复核并重签令牌。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowDown, Close, Fold, Plus, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuth } from './app/auth'
import type { RoleCode } from './app/types'
import type { QualityModule } from './features/quality/types'
import LoginPage from './features/auth/LoginPage.vue'
import ConversationWorkspace from './features/conversation/ConversationWorkspace.vue'
import ConversationSidebar from './features/conversation/ConversationSidebar.vue'
import QualityAdminNav from './features/quality/QualityAdminNav.vue'
import QualityAdminPage from './features/quality/QualityAdminPage.vue'
import PermissionManagementPage from './features/permission/PermissionManagementPage.vue'

type WorkspaceView = 'query' | 'quality' | 'permissions'

const { user, restoring, authenticated, restore, logout, switchIdentity } = useAuth()
const sidebarOpen = ref(false)
const narrowScreen = ref(window.matchMedia('(max-width: 820px)').matches)
const sidebarMedia = window.matchMedia('(max-width: 820px)')
const menuButton = ref<HTMLButtonElement>()
const newSessionButton = ref<HTMLButtonElement>()
const workspace = ref<InstanceType<typeof ConversationWorkspace>>()
const sessionRevision = ref(0)
const serviceHealthy = ref<boolean | null>(null)
const currentView = ref<WorkspaceView>('query')
const identitySwitching = ref(false)
const qualityModule = ref<QualityModule>('overview')

function resizeSidebar(event: MediaQueryListEvent) { narrowScreen.value = event.matches; if (!event.matches) sidebarOpen.value = false }
function openSidebar() { sidebarOpen.value = true; void nextTick(() => newSessionButton.value?.focus()) }
function closeSidebar() { sidebarOpen.value = false; void nextTick(() => menuButton.value?.focus()) }

/**
 * 客户经理和质量审计员都可以进入智能问数，但后端会为两者应用不同的数据范围。
 * 权限管理员不进入查询工作区，避免仅靠隐藏按钮形成“看似可用”的越权入口。
 */
const isQueryIdentity = computed(() => user.value?.role === 'CUSTOMER_MANAGER' || user.value?.role === 'QUALITY_AUDITOR')
const canSwitchIdentity = computed(() => (user.value?.available_roles.length || 0) > 1)
const roleLabel = computed(() => {
  if (user.value?.role === 'QUALITY_AUDITOR') return '质量审计员'
  if (user.value?.role === 'PERMISSION_ADMIN') return '权限管理员'
  return ({ CUSTOMER_MANAGER: '客户经理', TEAM_LEAD: '团队负责人', ORG_MANAGER: '机构负责人' }[
    user.value?.business_scope_level || 'CUSTOMER_MANAGER'])
})
/**
 * 工号属于登录账号而不是当前激活身份，因此客户经理、审计员和权限管理员必须显示同一个值。
 * 只展示后端返回的合法五位工号；历史异常账号缺少工号时明确提示未配置，不能拿区域或角色兜底。
 */
const employeeNoLabel = computed(() => {
  const employeeNo = user.value?.employee_no?.trim() || ''
  return /^[0-9]{5}$/.test(employeeNo) ? `工号 ${employeeNo}` : '工号 未配置'
})
const navigationOptions = computed(() => {
  // 审计身份在同一工作台内切换业务问数与审计后台，不触发浏览器页面跳转。
  if (user.value?.role === 'QUALITY_AUDITOR') return [
    { value: 'query' as const, label: '智能问数' },
    { value: 'quality' as const, label: '后台管理' },
  ]
  if (user.value?.role === 'PERMISSION_ADMIN') return [{ value: 'permissions' as const, label: '权限管理' }]
  return []
})
const pageTitle = computed(() => currentView.value === 'quality' ? '后台管理'
  : currentView.value === 'permissions' ? '权限管理' : '智能问数')

function defaultView(): WorkspaceView {
  if (user.value?.role === 'PERMISSION_ADMIN') return 'permissions'
  // 审计管理员以后台管理为主工作区，问数入口仍可通过顶栏下拉进入。
  if (user.value?.role === 'QUALITY_AUDITOR') return 'quality'
  return 'query'
}
function syncIdentityView() {
  currentView.value = defaultView()
  sidebarOpen.value = false
}
function currentViewAvailable() {
  if (currentView.value === 'query') return isQueryIdentity.value
  if (currentView.value === 'quality') return user.value?.role === 'QUALITY_AUDITOR'
  return user.value?.role === 'PERMISSION_ADMIN'
}
function deletedSession(id: string) {
  if (workspace.value?.sessionId === id) workspace.value.newConversation()
  sessionRevision.value++
}
async function newQuery() {
  // 审计员可能正停留在后台管理页；先回到问数工作区，再操作刚挂载的会话组件。
  if (currentView.value !== 'query') {
    currentView.value = 'query'
    await nextTick()
  }
  if (workspace.value?.newConversation()) sidebarOpen.value = false
}
async function selectSession(id: string) {
  if (await workspace.value?.openSession(id)) sidebarOpen.value = false
}
function selectWorkspace(value: WorkspaceView) {
  // 下拉选项本身已由当前身份生成；这里仍二次判断，避免 DOM 被篡改后打开越权页面。
  if (!navigationOptions.value.some(item => item.value === value)) return
  currentView.value = value
}
async function changeIdentity(role: RoleCode) {
  if (!user.value || role === user.value.role || identitySwitching.value) return
  identitySwitching.value = true
  try {
    await switchIdentity(role)
    /**
     * user.role 是会话组件 key 的一部分，令牌更新后 Vue 会销毁旧组件，从而终止旧身份的
     * SSE、草稿和请求，再挂载新身份工作区。能访问当前工作区时保持原页面；仅当目标身份
     * 无权访问当前工作区时才回到该身份的默认页，避免整页刷新造成视觉跳转。
     */
    if (!currentViewAvailable()) currentView.value = defaultView()
    sidebarOpen.value = false
    sessionRevision.value++
    await nextTick()
    identitySwitching.value = false
    ElMessage.success(`已切换为${roleLabel.value}`)
  } catch (exception) {
    ElMessage.error(exception instanceof Error ? exception.message : '身份切换失败，请稍后重试')
    identitySwitching.value = false
  }
}

/**
 * 同标签页主动切换由 changeIdentity 负责保持当前页面；其他标签页替换令牌时不会经过该函数，
 * 因而只在非主动切换场景校正无权访问的旧工作区，避免跨标签页留下上一身份页面。
 */
watch(() => user.value?.role, role => {
  if (!role || identitySwitching.value) return
  if (!currentViewAvailable()) currentView.value = defaultView()
  sidebarOpen.value = false
  sessionRevision.value++
})

onMounted(async () => {
  sidebarMedia.addEventListener('change', resizeSidebar)
  await restore()
  syncIdentityView()
  try {
    const response = await fetch('/actuator/health')
    const health = await response.json()
    serviceHealthy.value = response.ok && health.status === 'UP'
  } catch {
    serviceHealthy.value = false
  }
})
onUnmounted(() => sidebarMedia.removeEventListener('change', resizeSidebar))
</script>

<template>
  <div v-if="restoring" class="app-loading"><span class="brand-seal">中</span><p>正在恢复工作台…</p></div>
  <LoginPage v-else-if="!authenticated" />
  <div v-else class="app-shell">
    <div v-if="sidebarOpen" class="mobile-mask" @click="closeSidebar" />
    <aside id="conversation-sidebar" class="sidebar" :class="{ 'is-open': sidebarOpen }" :inert="narrowScreen && !sidebarOpen" @keydown.esc="closeSidebar">
      <div class="brand">
        <span class="brand-boc-logo" role="img" aria-label="中国银行标志">
          <svg viewBox="0 0 48 48" aria-hidden="true"><circle cx="24" cy="24" r="20" fill="currentColor" /><path d="M20 11h8v26h-8z" fill="#fff8f5" /><path d="M15 19h18v10H15z" fill="#fff8f5" /><path d="M20 19h8v10h-8z" fill="currentColor" /></svg>
        </span>
        <div><strong>中银智析</strong><small>个金营销智能平台</small></div>
        <button class="sidebar-close mobile-only" type="button" aria-label="关闭会话侧栏" @click="closeSidebar"><Close /></button>
      </div>
      <template v-if="currentView === 'quality' && user?.role === 'QUALITY_AUDITOR'">
        <QualityAdminNav :module="qualityModule" @navigate="key => (qualityModule = key)" />
      </template>
      <template v-else-if="isQueryIdentity">
        <button ref="newSessionButton" class="new-query-button" type="button" :disabled="workspace?.navigationBusy" @click="newQuery"><Plus /> 新建会话</button>
        <ConversationSidebar :key="`${user?.user_id}-${user?.role}`" :active-session-id="workspace?.sessionId" :disabled="!!workspace?.navigationBusy" :refresh-version="sessionRevision" @select="selectSession" @deleted="deletedSession" />
      </template>
      <div v-else class="sidebar-role-note"><strong>{{ roleLabel }}</strong><span>请从页面顶部进入权限管理。</span></div>
      <div class="sidebar-note"><span>当前登录用户</span><strong>{{ employeeNoLabel }}</strong></div>
      <div class="sidebar-footer">
        <el-dropdown v-if="canSwitchIdentity" popper-class="identity-menu" trigger="click" placement="top-start" @command="changeIdentity">
          <button class="user-card identity-switcher" :class="{ 'is-switching': identitySwitching }" type="button" :disabled="identitySwitching" aria-label="切换当前身份">
            <el-avatar :size="36">{{ user?.display_name?.slice(0, 1) }}</el-avatar>
            <div><strong>{{ user?.display_name }}</strong><small>{{ roleLabel }} · 切换身份</small></div><ArrowDown />
          </button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="role in user?.available_roles.filter(item => item !== user?.role)" :key="role" :command="role">切换为{{ role === 'CUSTOMER_MANAGER' ? '客户经理' : role === 'QUALITY_AUDITOR' ? '质量审计员' : '权限管理员' }}</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
        <div v-else class="user-card"><el-avatar :size="36">{{ user?.display_name?.slice(0, 1) }}</el-avatar><div><strong>{{ user?.display_name }}</strong><small>{{ roleLabel }}</small></div></div>
        <el-tooltip content="退出登录"><button class="logout-button" type="button" aria-label="退出登录" @click="logout"><SwitchButton /></button></el-tooltip>
      </div>
    </aside>

    <main class="main-area" :inert="narrowScreen && sidebarOpen">
      <header class="topbar">
        <div class="topbar-title">
          <button ref="menuButton" class="icon-button mobile-only" type="button" aria-label="打开会话侧栏" aria-controls="conversation-sidebar" :aria-expanded="sidebarOpen" @click="openSidebar"><Fold /></button>
          <el-dropdown v-if="navigationOptions.length" trigger="click" @command="selectWorkspace">
            <button class="workspace-menu-button" type="button" :aria-label="`切换工作区，当前为${pageTitle}`"><h1>{{ pageTitle }}</h1><ArrowDown /></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="item in navigationOptions" :key="item.value" :command="item.value" :disabled="currentView === item.value">{{ item.label }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
          <div v-else><h1>{{ pageTitle }}</h1></div>
        </div>
        <div class="topbar-actions"><span class="service-status" :class="{ unavailable: serviceHealthy === false }"><i />{{ serviceHealthy === null ? '正在连接服务' : serviceHealthy ? '服务状态正常' : '服务暂不可用' }}</span></div>
      </header>

      <div v-if="currentView === 'query' && isQueryIdentity" class="workspace-grid conversation-layout"><ConversationWorkspace ref="workspace" :key="`${user?.user_id}-${user?.role}`" @sessions-changed="sessionRevision++" /></div>
      <div v-else class="workspace-grid role-workspace"><QualityAdminPage v-if="currentView === 'quality'" :module="qualityModule" @navigate="key => (qualityModule = key)" /><PermissionManagementPage v-else-if="currentView === 'permissions'" /></div>
    </main>
  </div>
</template>
