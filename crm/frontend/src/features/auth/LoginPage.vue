<script setup lang="ts">
/** 登录与公开注册入口；客户端校验用于即时提示，最终账号策略仍由服务端执行。 */
import { computed, ref } from 'vue'
import { Lock } from '@element-plus/icons-vue'
import { ApiError } from '../../app/api'
import { useAuth } from '../../app/auth'

const { login, register } = useAuth()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const employeeNo = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')
const registrationMessage = ref('')

const usernameValid = computed(() => /^[a-z]+[0-9]+$/.test(username.value) && username.value.length >= 4 && username.value.length <= 64)
const employeeNoValid = computed(() => /^[0-9]{5}$/.test(employeeNo.value))
const passwordChecks = computed(() => ({
  length: password.value.length >= 8 && new TextEncoder().encode(password.value).byteLength <= 72,
  digit: /\d/.test(password.value),
  lower: /[a-z]/.test(password.value),
  upper: /[A-Z]/.test(password.value),
  special: /[^A-Za-z0-9\s]/.test(password.value),
}))
const passwordStrong = computed(() => Object.values(passwordChecks.value).every(Boolean))
const registrationValid = computed(() => employeeNoValid.value && usernameValid.value && passwordStrong.value && password.value === confirmPassword.value)

function normalizeUsername() {
  username.value = username.value.trim().toLowerCase()
}

function switchMode(next: 'login' | 'register') {
  mode.value = next
  error.value = ''
  registrationMessage.value = ''
  if (next === 'register') {
    username.value = ''
    employeeNo.value = ''
    password.value = ''
    confirmPassword.value = ''
  } else {
    username.value = ''
    password.value = ''
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
    error.value = '请按提示完善工号、用户名和强密码，并确认两次密码一致。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await register(employeeNo.value, username.value, password.value)
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
          <p>{{ mode === 'login' ? '登录后由后端按已授予的身份和数据范围进入工作台。' : '提交后需等待权限管理员分配身份和数据范围。' }}</p>
        </div>

        <template v-if="mode === 'login'">
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
            <label>工号
              <el-input v-model="employeeNo" size="large" maxlength="5" inputmode="numeric" autocomplete="off" />
              <small class="field-hint" :class="{ invalid: employeeNo && !employeeNoValid }">请输入唯一的5位阿拉伯数字工号</small>
            </label>
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
