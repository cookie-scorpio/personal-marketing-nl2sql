<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  Close, Fold, Plus, SwitchButton,
} from '@element-plus/icons-vue'
import { useAuth } from './app/auth'
import LoginPage from './features/auth/LoginPage.vue'
import ConversationWorkspace from './features/conversation/ConversationWorkspace.vue'
import ConversationSidebar from './features/conversation/ConversationSidebar.vue'

const { user, restoring, authenticated, restore, logout } = useAuth()
const sidebarOpen = ref(false)
const narrowScreen = ref(window.matchMedia('(max-width: 820px)').matches)
const sidebarMedia = window.matchMedia('(max-width: 820px)')
const menuButton = ref<HTMLButtonElement>()
const newSessionButton = ref<HTMLButtonElement>()
function resizeSidebar(event: MediaQueryListEvent) { narrowScreen.value = event.matches; if (!event.matches) sidebarOpen.value = false }
function openSidebar() { sidebarOpen.value = true; void nextTick(() => newSessionButton.value?.focus()) }
function closeSidebar() { sidebarOpen.value = false; void nextTick(() => menuButton.value?.focus()) }
const workspace = ref<InstanceType<typeof ConversationWorkspace>>()
const sessionRevision = ref(0)
const serviceHealthy = ref<boolean | null>(null)

const roleLabel = computed(() => ({
  CUSTOMER_MANAGER: '客户经理', TEAM_LEAD: '团队负责人', ORG_MANAGER: '机构负责人',
}[user.value?.role || 'CUSTOMER_MANAGER']))

const scopeLabel = computed(() => {
  if (!user.value) return ''
  if (user.value.role === 'CUSTOMER_MANAGER') return `经理 ${user.value.manager_id}`
  if (user.value.role === 'TEAM_LEAD') return `网点 ${user.value.branch_id}`
  return `区域 ${user.value.region_code}`
})

function newQuery() {
  if (workspace.value?.newConversation()) sidebarOpen.value = false
}

async function selectSession(id: string) {
  if (await workspace.value?.openSession(id)) sidebarOpen.value = false
}

onMounted(async () => {
  sidebarMedia.addEventListener('change', resizeSidebar)
  await restore()
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
      <div class="brand"><span class="brand-seal">中</span><div><strong>中银智析</strong><small>个金营销智能问数</small></div><button class="sidebar-close mobile-only" type="button" aria-label="关闭会话侧栏" @click="closeSidebar"><Close /></button></div>
      <button ref="newSessionButton" class="new-query-button" type="button" :disabled="workspace?.navigationBusy" @click="newQuery"><Plus /> 新建会话</button>
      <ConversationSidebar :key="user?.user_id" :active-session-id="workspace?.sessionId"
                           :disabled="!!workspace?.navigationBusy" :refresh-version="sessionRevision" @select="selectSession" />
      <div class="sidebar-note">
        <span>当前数据范围</span><strong>{{ scopeLabel }}</strong>
        <p>查询范围由登录身份决定，前端无法修改。</p>
      </div>
      <div class="sidebar-footer">
        <div class="user-card">
          <el-avatar :size="36">{{ user?.display_name.slice(0, 1) }}</el-avatar>
          <div><strong>{{ user?.display_name }}</strong><small>{{ roleLabel }}</small></div>
          <el-tooltip content="退出登录"><button class="logout-button" type="button" aria-label="退出登录" @click="logout"><SwitchButton /></button></el-tooltip>
        </div>
      </div>
    </aside>

    <main class="main-area" :inert="narrowScreen && sidebarOpen">
      <header class="topbar">
        <div class="topbar-title">
          <button ref="menuButton" class="icon-button mobile-only" type="button" aria-label="打开会话侧栏" aria-controls="conversation-sidebar" :aria-expanded="sidebarOpen" @click="openSidebar"><Fold /></button>
          <div><h1>智能问数</h1><p>用业务语言发现客户机会 · 数据范围：{{ scopeLabel }}</p></div>
        </div>
        <div class="topbar-actions">
          <span class="service-status" :class="{ unavailable: serviceHealthy === false }"><i />{{ serviceHealthy === null ? '正在连接服务' : serviceHealthy ? '服务状态正常' : '服务暂不可用' }}</span>
        </div>
      </header>

      <div class="workspace-grid conversation-layout">
        <ConversationWorkspace ref="workspace" :key="user?.user_id" @sessions-changed="sessionRevision++" />
      </div>
    </main>
  </div>
</template>
