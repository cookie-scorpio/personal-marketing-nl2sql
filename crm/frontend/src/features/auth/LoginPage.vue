<script setup lang="ts">
/** 登录与公开注册入口；客户端校验用于即时提示，最终账号策略仍由服务端执行。 */
import { computed, ref } from 'vue'
import { Lock, User } from '@element-plus/icons-vue'
import { ApiError } from '../../app/api'
import { useAuth } from '../../app/auth'

const { login, register } = useAuth()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')
const registrationMessage = ref('')

const accounts = [
  { username: 'manager01', label: '客户经理', scope: '本人负责客户' },
  { username: 'leader01', label: '团队负责人', scope: '所属网点客户' },
  { username: 'director01', label: '机构负责人', scope: '所属区域客户' },
]
const selectedAccount = ref('manager01')

const usernameValid = computed(() => /^[a-z]+[0-9]+$/.test(username.value) && username.value.length >= 4 && username.value.length <= 64)
const passwordChecks = computed(() => ({
  length: password.value.length >= 8 && new TextEncoder().encode(password.value).byteLength <= 72,
  digit: /\d/.test(password.value),
  lower: /[a-z]/.test(password.value),
  upper: /[A-Z]/.test(password.value),
  special: /[^A-Za-z0-9\s]/.test(password.value),
}))
const passwordStrong = computed(() => Object.values(passwordChecks.value).every(Boolean))
const registrationValid = computed(() => displayName.value.trim().length >= 2 && displayName.value.trim().length <= 64
  && usernameValid.value && passwordStrong.value && password.value === confirmPassword.value)

function chooseAccount(account: (typeof accounts)[number]) {
  selectedAccount.value = account.username
  error.value = ''
}

function normalizeUsername() {
  username.value = username.value.trim().toLowerCase()
}

function switchMode(next: 'login' | 'register') {
  mode.value = next
  error.value = ''
  registrationMessage.value = ''
  if (next === 'register') {
    username.value = ''
    password.value = ''
    confirmPassword.value = ''
  } else {
    username.value = ''
    password.value = ''
    selectedAccount.value = 'manager01'
  }
}

async function submitLogin() {
  normalizeUsername()
  if (!username.value || !password.value) return
  loading.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
  } catch (exception) {
    error.value = exception instanceof ApiError || exception instanceof Error ? exception.message : '登录失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function submitRegistration() {
  normalizeUsername()
  if (!registrationValid.value) {
    error.value = '请按提示完善姓名、用户名和强密码，并确认两次密码一致。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await register(displayName.value.trim(), username.value, password.value)
    registrationMessage.value = result.message
    password.value = ''
    confirmPassword.value = ''
  } catch (exception) {
    error.value = exception instanceof ApiError || exception instanceof Error ? exception.message : '注册提交失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-intro">
      <div class="login-brand">
        <span class="boc-logo" role="img" aria-label="中国银行标志">
          <svg viewBox="0 0 48 48" aria-hidden="true">
            <circle cx="24" cy="24" r="20" fill="currentColor" />
            <path d="M20 11h8v26h-8z" fill="#fff8f5" />
            <path d="M15 19h18v10H15z" fill="#fff8f5" />
            <path d="M20 19h8v10h-8z" fill="currentColor" />
          </svg>
        </span>
        <strong>中银智析</strong>
      </div>
      <div class="intro-copy">
        <h1>个金营销智能平台</h1>
        <p class="intro-statement">{{ mode === 'login' ? '让营销问题，直接得到数据答案' : '提交身份申请，等待授权开通' }}</p>
        <p class="intro-description" :class="{ 'registration-description': mode === 'register' }">{{ mode === 'login' ? '面向个人金融营销人员的自然语言查询工作台。系统会补全条件、澄清矛盾，并用表格、图表和分析结论解释查询结果。' : '注册只提交基础身份信息。审批通过并分配数据范围后，账号才可以登录使用智能问数。' }}</p>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-card">
        <div class="login-card-head">
          <h2 id="login-title">{{ mode === 'login' ? '登录工作台' : '申请注册账号' }}</h2>
          <p>{{ mode === 'login' ? '选择演示身份，查看不同的数据访问范围。' : '提交后需等待管理员分配角色和数据范围。' }}</p>
        </div>

        <template v-if="mode === 'login'">
          <div class="demo-accounts" aria-label="演示账号">
            <button v-for="account in accounts" :key="account.username" type="button"
                    :class="{ selected: selectedAccount === account.username }" @click="chooseAccount(account)">
              <span class="account-icon"><User /></span>
              <span><strong>{{ account.label }}</strong><small>{{ account.scope }}</small></span>
            </button>
          </div>
          <form @submit.prevent="submitLogin">
            <label>用户名<el-input v-model="username" size="large" autocomplete="username" autocapitalize="none" @blur="normalizeUsername" /></label>
            <label>密码<el-input v-model="password" type="password" size="large" show-password autocomplete="current-password" /></label>
            <div v-if="error" class="form-error" role="alert">{{ error }}</div>
            <el-button native-type="submit" type="primary" size="large" :loading="loading" :disabled="!username || !password">进入智能问数</el-button>
          </form>
          <button class="auth-text-action" type="button" @click="switchMode('register')">没有账号？申请注册</button>
        </template>

        <template v-else>
          <div v-if="registrationMessage" class="registration-success" role="status">
            <span class="account-icon"><Lock /></span>
            <div><strong>申请已提交</strong><p>{{ registrationMessage }}</p></div>
            <el-button type="primary" @click="switchMode('login')">返回登录</el-button>
          </div>
          <form v-else @submit.prevent="submitRegistration">
            <label>姓名<el-input v-model="displayName" size="large" maxlength="64" autocomplete="name" /></label>
            <label>用户名
              <el-input v-model="username" size="large" maxlength="64" autocapitalize="none" autocomplete="username" @blur="normalizeUsername" />
              <small class="field-hint" :class="{ invalid: username && !usernameValid }">小写英文字母开头、数字结尾，例如 manager01</small>
            </label>
            <label>密码<el-input v-model="password" type="password" size="large" maxlength="72" show-password autocomplete="new-password" /></label>
            <div class="password-rules" aria-label="密码规则">
              <span :class="{ met: passwordChecks.length }">至少8位，最长72字节</span>
              <span :class="{ met: passwordChecks.digit }">数字</span>
              <span :class="{ met: passwordChecks.lower }">小写字母</span>
              <span :class="{ met: passwordChecks.upper }">大写字母</span>
              <span :class="{ met: passwordChecks.special }">特殊符号</span>
            </div>
            <label>确认密码
              <el-input v-model="confirmPassword" type="password" size="large" maxlength="72" show-password autocomplete="new-password" />
              <small v-if="confirmPassword && password !== confirmPassword" class="field-hint invalid">两次输入的密码不一致</small>
            </label>
            <div v-if="error" class="form-error" role="alert">{{ error }}</div>
            <el-button native-type="submit" type="primary" size="large" :loading="loading">提交注册申请</el-button>
          </form>
          <button class="auth-text-action" type="button" @click="switchMode('login')">返回登录</button>
        </template>
      </div>
    </section>
  </main>
</template>
