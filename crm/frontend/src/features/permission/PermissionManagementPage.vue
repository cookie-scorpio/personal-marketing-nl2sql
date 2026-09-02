<script setup lang="ts">
/** 权限管理员的最小授权工作台：查看注册账号，并一次性授予身份和业务数据范围。 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiRequest } from '../../app/api'
import type { BusinessScopeLevel, PermissionAdminAccount, RoleCode } from '../../app/types'

const accounts = ref<PermissionAdminAccount[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const editing = ref<PermissionAdminAccount | null>(null)

const form = reactive<{
  roles: RoleCode[]
  businessScopeLevel?: BusinessScopeLevel
  regionCode: string
  branchId: string
  managerId: string
}>({ roles: [], businessScopeLevel: undefined, regionCode: '', branchId: '', managerId: '' })

const businessGranted = computed(() => form.roles.includes('CUSTOMER_MANAGER'))
const needsBranch = computed(() => form.businessScopeLevel === 'CUSTOMER_MANAGER' || form.businessScopeLevel === 'TEAM_LEAD')
const needsManager = computed(() => form.businessScopeLevel === 'CUSTOMER_MANAGER')

const roleLabels: Record<RoleCode, string> = {
  CUSTOMER_MANAGER: '客户经理类',
  QUALITY_AUDITOR: '质量审计员',
  PERMISSION_ADMIN: '权限管理员',
}
const scopeLabels: Record<BusinessScopeLevel, string> = {
  CUSTOMER_MANAGER: '客户经理（本人客户）',
  TEAM_LEAD: '团队负责人（本网点）',
  ORG_MANAGER: '机构负责人（本区域）',
}

function roleText(row: PermissionAdminAccount) {
  if (!row.roles.length) return '未授权'
  return row.roles.map(item => item.role === 'CUSTOMER_MANAGER' && item.business_scope_level
    ? scopeLabels[item.business_scope_level] : roleLabels[item.role]).join('、')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    accounts.value = await apiRequest<PermissionAdminAccount[]>('/api/v1/permission-admin/accounts')
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '账号列表加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function beginEdit(account: PermissionAdminAccount) {
  editing.value = account
  form.roles = account.roles.map(item => item.role)
  const business = account.roles.find(item => item.role === 'CUSTOMER_MANAGER')
  form.businessScopeLevel = business?.business_scope_level
  form.regionCode = account.region_code || ''
  form.branchId = account.branch_id || ''
  form.managerId = account.manager_id || ''
}

function closeDialog() {
  if (saving.value) return
  editing.value = null
}

async function save() {
  if (!editing.value || !form.roles.length) {
    ElMessage.warning('请至少选择一种身份')
    return
  }
  if (businessGranted.value && !form.businessScopeLevel) {
    ElMessage.warning('客户经理类必须选择业务数据范围等级')
    return
  }
  saving.value = true
  try {
    const updated = await apiRequest<PermissionAdminAccount>(
      `/api/v1/permission-admin/accounts/${editing.value.user_id}/permissions`, {
        method: 'PUT',
        body: JSON.stringify({
          roles: form.roles,
          business_scope_level: businessGranted.value ? form.businessScopeLevel : null,
          region_code: businessGranted.value ? form.regionCode : null,
          branch_id: businessGranted.value && needsBranch.value ? form.branchId : null,
          manager_id: businessGranted.value && needsManager.value ? form.managerId : null,
        }),
      },
    )
    accounts.value = accounts.value.map(row => row.user_id === updated.user_id ? updated : row)
    editing.value = null
    ElMessage.success('权限已更新，账号可在下次登录或切换身份时生效')
  } catch (exception) {
    ElMessage.error(exception instanceof Error ? exception.message : '权限更新失败，请重试')
  } finally {
    saving.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="role-page permission-page" aria-labelledby="permission-page-title">
    <header class="role-page-head permission-page-head">
      <div>
        <p class="section-kicker">权限管理员</p>
        <h2 id="permission-page-title">权限管理</h2>
        <p>为注册账号授予可用身份；客户经理类还需配置对应的数据范围。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新列表</el-button>
    </header>

    <div v-if="error" class="role-page-error" role="alert">
      <span>{{ error }}</span><el-button text type="primary" @click="load">重新加载</el-button>
    </div>
    <div v-else class="permission-table-wrap" :aria-busy="loading">
      <el-table :data="accounts" v-loading="loading" empty-text="暂无注册账号">
        <el-table-column label="工号" min-width="100"><template #default="{ row }">{{ row.employee_no || '—' }}</template></el-table-column>
        <el-table-column prop="username" label="用户名" min-width="130" />
        <el-table-column prop="display_name" label="展示名称" min-width="130" />
        <el-table-column label="账号状态" min-width="100"><template #default="{ row }"><span class="account-status" :class="row.account_status === 'ACTIVE' ? 'active' : 'pending'">{{ row.account_status === 'ACTIVE' ? '已启用' : '待授权' }}</span></template></el-table-column>
        <el-table-column label="已授予身份" min-width="245"><template #default="{ row }"><span class="role-summary">{{ roleText(row) }}</span></template></el-table-column>
        <el-table-column label="操作" width="108" fixed="right"><template #default="{ row }"><el-button text type="primary" @click="beginEdit(row)">配置权限</el-button></template></el-table-column>
      </el-table>
    </div>

    <el-dialog :model-value="!!editing" :title="editing ? `配置权限 · ${editing.employee_no || editing.username}` : '配置权限'" width="min(560px, calc(100vw - 32px))" :close-on-click-modal="false" @close="closeDialog">
      <form class="permission-form" @submit.prevent="save">
        <label>身份类别
          <el-checkbox-group v-model="form.roles">
            <el-checkbox label="CUSTOMER_MANAGER">客户经理类</el-checkbox>
            <el-checkbox label="QUALITY_AUDITOR">质量审计员</el-checkbox>
            <el-checkbox label="PERMISSION_ADMIN">权限管理员</el-checkbox>
          </el-checkbox-group>
        </label>
        <template v-if="businessGranted">
          <label>业务数据范围等级
            <el-radio-group v-model="form.businessScopeLevel">
              <el-radio label="CUSTOMER_MANAGER">客户经理</el-radio>
              <el-radio label="TEAM_LEAD">团队负责人</el-radio>
              <el-radio label="ORG_MANAGER">机构负责人</el-radio>
            </el-radio-group>
          </label>
          <label>区域编码<el-input v-model="form.regionCode" maxlength="32" placeholder="例如 EAST" /></label>
          <label v-if="needsBranch">网点编码<el-input v-model="form.branchId" maxlength="32" placeholder="例如 B001" /></label>
          <label v-if="needsManager">客户经理编号<el-input v-model="form.managerId" maxlength="32" placeholder="例如 M0001" /></label>
          <p class="field-hint">客户经理、团队负责人和机构负责人分别查看本人、本网点和本区域客户。</p>
        </template>
      </form>
      <template #footer><el-button :disabled="saving" @click="closeDialog">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存权限</el-button></template>
    </el-dialog>
  </section>
</template>
