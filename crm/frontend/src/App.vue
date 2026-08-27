<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Bell, ChatDotRound, Collection, DataAnalysis, Discount, Fold, Plus,
  QuestionFilled, Setting, SwitchButton, User,
} from '@element-plus/icons-vue'
import { useAuth } from './app/auth'
import LoginPage from './features/auth/LoginPage.vue'
import ConversationWorkspace from './features/conversation/ConversationWorkspace.vue'
import InsightPanel from './features/marketing/InsightPanel.vue'

const { user, restoring, authenticated, restore, logout } = useAuth()
const sidebarOpen = ref(false)
const activeNav = ref('智能问数')
const conversationKey = ref(0)
const primaryNav = [
  { label: '智能问数', icon: ChatDotRound },
  { label: '客户洞察', icon: DataAnalysis },
  { label: '营销活动', icon: Discount },
  { label: '客户群管理', icon: User },
  { label: '指标中心', icon: Collection },
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

onMounted(restore)
</script>

<template>
  <div v-if="restoring" class="app-loading"><span class="brand-seal">知</span><p>正在恢复工作台…</p></div>
  <LoginPage v-else-if="!authenticated" />
  <div v-else class="app-shell">
    <div v-if="sidebarOpen" class="mobile-mask" @click="sidebarOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }">
      <div class="brand"><span class="brand-seal">知</span><div><strong>知客</strong><small>个金营销智能平台</small></div></div>
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
        <button class="nav-item" type="button"><Setting /><span>系统设置</span></button>
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
          <span class="service-status"><i /> 服务状态正常</span>
          <el-tooltip content="使用帮助"><button class="icon-button" type="button" aria-label="使用帮助"><QuestionFilled /></button></el-tooltip>
          <el-badge is-dot><button class="icon-button" type="button" aria-label="通知"><Bell /></button></el-badge>
        </div>
      </header>

      <div v-if="activeNav === '智能问数'" class="workspace-grid">
        <ConversationWorkspace :key="conversationKey" />
        <InsightPanel />
      </div>
      <section v-else class="placeholder-page">
        <span class="placeholder-icon"><component :is="primaryNav.find(item => item.label === activeNav)?.icon" /></span>
        <p class="section-kicker">MVP 后续模块</p>
        <h2>{{ activeNav }}</h2>
        <p>当前版本优先验证 NL2SQL 主链路，此入口将在后续版本接入完整业务功能。</p>
        <el-button type="primary" @click="navigate('智能问数')">返回智能问数</el-button>
      </section>
    </main>
  </div>
</template>
