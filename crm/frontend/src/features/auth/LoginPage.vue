<script setup lang="ts">
import { ref } from 'vue'
import { DataAnalysis, Lock, User } from '@element-plus/icons-vue'
import { ApiError } from '../../app/api'
import { useAuth } from '../../app/auth'

const { login } = useAuth()
const username = ref('manager01')
const password = ref('Demo@123')
const loading = ref(false)
const error = ref('')

const accounts = [
  { username: 'manager01', label: '客户经理', scope: '本人负责客户' },
  { username: 'leader01', label: '团队负责人', scope: '所属网点客户' },
  { username: 'director01', label: '机构负责人', scope: '所属区域客户' },
]

function chooseAccount(account: (typeof accounts)[number]) {
  username.value = account.username
  password.value = 'Demo@123'
  error.value = ''
}

async function submit() {
  if (!username.value || !password.value) return
  loading.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
  } catch (exception) {
    error.value = exception instanceof ApiError ? exception.message : '登录失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="login-brand"><span>知</span><strong>知客</strong></div>
      <div class="intro-copy">
        <p class="section-kicker">个金营销智能平台</p>
        <h1>把业务问题，变成可信的数据答案</h1>
        <p>面向客户经理的自然语言查询工作台。系统会补全条件、澄清矛盾、校验权限，并解释每一份结果的数据口径。</p>
      </div>
      <div class="trust-list">
        <span><DataAnalysis /> 受控 SQL 与数据范围校验</span>
        <span><Lock /> 敏感字段脱敏与操作审计</span>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-card">
        <div class="login-card-head">
          <p class="section-kicker">内部演示环境</p>
          <h2 id="login-title">登录工作台</h2>
          <p>选择演示身份，查看不同的数据访问范围。</p>
        </div>
        <div class="demo-accounts" aria-label="演示账号">
          <button v-for="account in accounts" :key="account.username" type="button"
                  :class="{ selected: username === account.username }" @click="chooseAccount(account)">
            <span class="account-icon"><User /></span>
            <span><strong>{{ account.label }}</strong><small>{{ account.scope }}</small></span>
          </button>
        </div>
        <form @submit.prevent="submit">
          <label>用户名<el-input v-model="username" size="large" autocomplete="username" /></label>
          <label>密码<el-input v-model="password" type="password" size="large" show-password autocomplete="current-password" /></label>
          <div v-if="error" class="form-error" role="alert">{{ error }}</div>
          <el-button native-type="submit" type="primary" size="large" :loading="loading" :disabled="!username || !password">进入智能问数</el-button>
        </form>
        <p class="login-note">演示密码统一为 <code>Demo@123</code>，数据库仅保存 BCrypt 哈希。</p>
      </div>
    </section>
  </main>
</template>
