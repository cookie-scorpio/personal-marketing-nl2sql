<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ChatDotRound, Fold, Plus, SwitchButton,
} from '@element-plus/icons-vue'
import { useAuth } from './app/auth'
import LoginPage from './features/auth/LoginPage.vue'
import ConversationWorkspace from './features/conversation/ConversationWorkspace.vue'
import InsightPanel from './features/marketing/InsightPanel.vue'

const { user, restoring, authenticated, restore, logout } = useAuth()
const sidebarOpen = ref(false)
const activeNav = ref('智能问数')
const conversationKey = ref(0)
const serviceHealthy = ref<boolean | null>(null)
const primaryNav = [
  { label: '智能问数', icon: ChatDotRound },
]

const roleLabel = computed(() => ({
  CUSTOMER_MANAGER: '客户经理', TEAM_LEAD: '团队负责人', ORG_MANAGER: '机构负责人',
}[user.value?.role || 'CUSTOMER_MANAGER']))

const scopeLabel = computed(() => {
  if (!user.value) return ''
  if (user.value.role === 'CUSTOMER_MANAGER') return `经理 ${user.value.manager_id}`
  if (user.value.role === 'TEAM_LEAD') return `网点 ${user.value.branch_id}`
  return `区域 ${user.value.region_code}`
})

function navigate(label: string) {
  activeNav.value = label
  sidebarOpen.value = false
}

function newQuery() {
  activeNav.value = '智能问数'
  conversationKey.value += 1
  sidebarOpen.value = false
}

onMounted(async () => {
  await restore()
  try {
    const response = await fetch('/actuator/health')
    const health = await response.json()
    serviceHealthy.value = response.ok && health.status === 'UP'
  } catch {
    serviceHealthy.value = false
  }
})
</script>

<template>
  <div v-if="restoring" class="app-loading"><span class="brand-seal">中</span><p>正在恢复工作台…</p></div>
  <LoginPage v-else-if="!authenticated" />
  <div v-else class="app-shell">
    <div v-if="sidebarOpen" class="mobile-mask" @click="sidebarOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }">
      <div class="brand"><span class="brand-seal">中</span><div><strong>中银智析</strong><small>个金营销智能问数</small></div></div>
      <button class="new-query-button" type="button" @click="newQuery"><Plus /> 发起新查询</button>
      <nav class="nav-list" aria-label="主导航">
        <button v-for="item in primaryNav" :key="item.label" type="button" class="nav-item"
                :class="{ active: activeNav === item.label }" @click="navigate(item.label)">
          <component :is="item.icon" /><span>{{ item.label }}</span>
        </button>
      </nav>
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

    <main class="main-area">
      <header class="topbar">
        <div class="topbar-title">
          <button class="icon-button mobile-only" type="button" aria-label="打开菜单" @click="sidebarOpen = true"><Fold /></button>
          <div><h1>{{ activeNav }}</h1><p>用业务语言发现客户机会 · 数据范围：{{ scopeLabel }}</p></div>
        </div>
        <div class="topbar-actions">
          <span class="service-status" :class="{ unavailable: serviceHealthy === false }"><i />{{ serviceHealthy === null ? '正在连接服务' : serviceHealthy ? '服务状态正常' : '服务暂不可用' }}</span>
        </div>
      </header>

      <div v-if="activeNav === '智能问数'" class="workspace-grid">
        <ConversationWorkspace :key="conversationKey" />
        <InsightPanel />
      </div>
    </main>
  </div>
</template>
