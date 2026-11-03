<template>
  <div class="h-full">
    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2 font-medium flex-wrap">
            <el-icon><Setting /></el-icon>
            <span>模型配置</span>
            <el-tag v-if="activeConfig" type="success" size="small" effect="plain">
              全局默认: {{ activeConfig.name }} · {{ activeConfig.modelName }}
            </el-tag>
            <el-tag v-else type="info" size="small" effect="plain">
              未设全局默认, 回退 application.yml
            </el-tag>
          </div>
          <el-button type="primary" @click="openCreate">新增模型</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        class="mb-4"
        title="全局默认只决定两件事: RAG 问答用哪个模型, 以及未绑定角色的兜底模型。在「角色-模型绑定」里绑定到某个配置的角色会直接使用该配置, 不受全局默认影响。未设全局默认时回退 application.yml。"
      />

      <div class="overflow-x-auto">
        <el-table v-loading="loading" :data="configs" stripe>
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <span class="font-medium">{{ row.name }}</span>
                <el-tag v-if="row.active" type="success" size="small">全局默认</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="模型" prop="modelName" min-width="150" />
          <el-table-column label="接入地址" prop="baseUrl" min-width="240" show-overflow-tooltip />
          <el-table-column label="API Key" min-width="140">
            <template #default="{ row }">
              <span class="text-xs font-mono text-gray-400">{{ maskKey(row.apiKey) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="温度 / 超时" width="120">
            <template #default="{ row }">
              <span class="text-xs text-gray-500">{{ row.temperature ?? 0.7 }} / {{ row.timeoutSeconds ?? 120 }}s</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :loading="testingId === row.id" @click="testRow(row)">测试</el-button>
              <el-button link type="success" :disabled="row.active" @click="activate(row)">设为默认</el-button>
              <el-button link @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有模型配置, 点击右上角新增" :image-size="70" />
          </template>
        </el-table>
      </div>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑模型配置' : '新增模型配置'"
      width="min(580px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="配置名称">
          <el-input v-model="form.name" placeholder="例如: 本地 Gemini 网关 / 千问 MaaS" maxlength="100" />
        </el-form-item>
        <el-form-item label="模型地址 (OpenAI 兼容 base url)">
          <el-input v-model="form.baseUrl" placeholder="https://maas.qianwenaiapi.com/compatible-mode/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>
        <el-form-item label="模型名称">
          <el-input v-model="form.modelName" placeholder="例如: qwen3.8-flash / gemini-3-flash" />
        </el-form-item>

        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <el-form-item label="温度">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" class="w-full" />
          </el-form-item>
          <el-form-item label="超时 (秒)">
            <el-input-number v-model="form.timeoutSeconds" :min="10" :max="600" :step="10" class="w-full" />
          </el-form-item>
          <el-form-item label="输出上限 (0=不传)">
            <el-input-number v-model="form.maxTokens" :min="0" :max="32000" :step="500" class="w-full" />
          </el-form-item>
        </div>

        <el-alert
          v-if="testResult && testResult.success"
          type="success"
          :closable="false"
          show-icon
          :title="`连通成功, 耗时 ${testResult.elapsedMs}ms`"
        >
          <div class="text-xs whitespace-pre-wrap break-all">
            {{ testResult.reply?.trim() || '(模型返回空内容)' }}
          </div>
        </el-alert>
        <el-alert
          v-else-if="testResult"
          type="error"
          :closable="false"
          show-icon
          :title="`连通失败, 耗时 ${testResult.elapsedMs}ms`"
        >
          <div class="text-xs break-all">{{ testResult.error }}</div>
        </el-alert>
      </el-form>

      <template #footer>
        <el-button :loading="testing" @click="testForm">测试连通性</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { modelConfigApi } from '@/api/modelConfig'
import type { ModelConfig, ModelTestResult } from '@/types'

const configs = ref<ModelConfig[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const testing = ref(false)
const testingId = ref('')
const testResult = ref<ModelTestResult | null>(null)
const form = ref<ModelConfig>(emptyForm())

const activeConfig = computed(() => configs.value.find((item) => item.active))

function emptyForm(): ModelConfig {
  return {
    name: '',
    baseUrl: '',
    apiKey: '',
    modelName: '',
    temperature: 0.7,
    timeoutSeconds: 120,
    maxTokens: null,
  }
}

async function load() {
  loading.value = true
  try {
    configs.value = await modelConfigApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = emptyForm()
  testResult.value = null
  dialogVisible.value = true
}

function openEdit(row: ModelConfig) {
  form.value = { ...row }
  testResult.value = null
  dialogVisible.value = true
}

function validate(): string | null {
  if (!form.value.name?.trim()) return '请输入配置名称'
  if (!form.value.baseUrl?.trim()) return '请输入模型地址'
  if (!/^https?:\/\//i.test(form.value.baseUrl.trim())) return '模型地址需以 http:// 或 https:// 开头'
  if (!form.value.apiKey?.trim()) return '请输入 API Key'
  if (!form.value.modelName?.trim()) return '请输入模型名称'
  return null
}

async function save() {
  const invalid = validate()
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  saving.value = true
  const payload: ModelConfig = {
    ...form.value,
    name: form.value.name.trim(),
    baseUrl: form.value.baseUrl.trim(),
    apiKey: form.value.apiKey.trim(),
    modelName: form.value.modelName.trim(),
    maxTokens: form.value.maxTokens && form.value.maxTokens > 0 ? form.value.maxTokens : null,
  }
  try {
    if (payload.id) {
      await modelConfigApi.update(payload.id, payload)
    } else {
      await modelConfigApi.create(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    // 响应拦截器已提示错误
  } finally {
    saving.value = false
  }
}

async function testForm() {
  const invalid = validate()
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await modelConfigApi.test({ ...form.value })
  } catch {
    // 响应拦截器已提示错误
  } finally {
    testing.value = false
  }
}

async function testRow(row: ModelConfig) {
  testingId.value = row.id || ''
  try {
    notifyTest(await modelConfigApi.test({ id: row.id }))
  } catch {
    // 响应拦截器已提示错误
  } finally {
    testingId.value = ''
  }
}

function notifyTest(result: ModelTestResult) {
  if (result.success) {
    ElNotification({
      type: 'success',
      title: `连通成功 · ${result.elapsedMs}ms`,
      message: result.reply?.trim() || '(模型返回空内容)',
      duration: 6000,
    })
  } else {
    ElNotification({
      type: 'error',
      title: '连通失败',
      message: result.error || '未知错误',
      duration: 10000,
    })
  }
}

async function activate(row: ModelConfig) {
  if (!row.id) return
  try {
    await modelConfigApi.activate(row.id)
    ElMessage.success(`已切换到 ${row.name}`)
    await load()
  } catch {
    // 响应拦截器已提示错误
  }
}

async function remove(row: ModelConfig) {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认删除模型配置「${row.name}」?`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await modelConfigApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 响应拦截器已提示错误
  }
}

function maskKey(key?: string): string {
  if (!key) return '-'
  return key.length <= 8 ? '****' : `${key.slice(0, 4)}****${key.slice(-4)}`
}

onMounted(load)
</script>
