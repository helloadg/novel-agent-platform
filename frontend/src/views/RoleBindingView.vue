<template>
  <div class="role-binding-page p-4 max-w-5xl mx-auto">
    <el-card>
      <template #header>
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-bold">🎭 角色-模型绑定</h2>
          <el-button type="primary" @click="saveAll" :loading="saving">💾 保存所有绑定</el-button>
        </div>
        <p class="text-xs text-gray-500 mt-1">
          为每个智能体角色独立选择模型配置和温度档位，不用重启即时生效。未绑定的角色会 fallback 到全局 active 模型。
        </p>
      </template>

      <div class="overflow-x-auto">
        <el-table :data="rows" border stripe style="width: 100%">
          <el-table-column label="角色标识" width="160">
            <template #default="{ row }">
              <span class="text-xs font-mono text-gray-500">{{ row.role.key }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色说明" min-width="240">
            <template #default="{ row }">
              <div class="font-semibold">{{ row.role.label }}</div>
              <div class="text-xs text-gray-500">{{ row.role.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型配置" min-width="200">
            <template #default="{ row }">
              <el-select v-model="row.modelConfigId" placeholder="请选择模型" clearable style="width: 100%">
                <el-option
                  v-for="m in modelList"
                  :key="m.id"
                  :label="`${m.name} (${m.modelName})${m.active ? ' ⭐' : ''}`"
                  :value="m.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="档位" width="160">
            <template #default="{ row }">
              <el-select v-model="row.profile" style="width: 100%">
                <el-option label="🎨 creative (高温度)" value="creative" />
                <el-option label="⚖️ balanced (均衡)" value="balanced" />
                <el-option label="📋 strict (低温稳定)" value="strict" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="当前生效模型" min-width="200">
            <template #default="{ row }">
              <span class="text-xs" :class="row.modelConfigId ? 'text-green-600' : 'text-orange-500'">
                {{ effectiveModelName(row) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="mt-4 p-3 bg-gray-50 rounded border text-xs text-gray-600">
        <div class="font-semibold mb-1">📚 档位说明</div>
        <ul class="list-disc pl-5 space-y-1">
          <li><b>creative:</b> 温度 0.9、超时 300s、max_tokens 4000 —— 世界构建、章节写作等需要创意发散的环节</li>
          <li><b>balanced:</b> 温度 0.7、超时 180s、max_tokens 2500 —— 润色师、总结师等常规任务</li>
          <li><b>strict:</b> 温度 0.2、超时 240s、max_tokens 2000 —— 大纲设计、一致性审核等需要结构化稳定输出的环节</li>
        </ul>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { novelBindingApi, AGENT_ROLES, type RoleBinding, type AgentRole } from '@/api/novelBinding'
import type { ModelConfig } from '@/types'

interface Row {
  role: AgentRole
  modelConfigId: number | null
  profile: RoleBinding['profile']
  binding?: RoleBinding
}

const rows = ref<Row[]>([])
const modelList = ref<ModelConfig[]>([])
const saving = ref(false)
const activeModel = ref<ModelConfig | null>(null)

onMounted(async () => {
  await Promise.all([loadBindings(), loadModels()])
})

async function loadModels() {
  const list = await novelBindingApi.listModels()
  modelList.value = list
  activeModel.value = list.find(m => m.active) || null
}

async function loadBindings() {
  const list = await novelBindingApi.list()
  rows.value = AGENT_ROLES.map(role => {
    const existing = list.find(b => b.agentRole === role.key)
    return {
      role,
      modelConfigId: existing?.modelConfigId ?? null,
      profile: existing?.profile ?? role.defaultProfile,
      binding: existing,
    }
  })
}

function effectiveModelName(row: Row): string {
  if (row.modelConfigId) {
    const m = modelList.value.find(x => x.id === row.modelConfigId)
    return m ? `${m.name} · ${m.modelName}` : '(已删除)'
  }
  if (activeModel.value) {
    return `📌 fallback: ${activeModel.value.name} · ${activeModel.value.modelName}`
  }
  return '⚠️ 未绑定且无 active 模型'
}

async function saveAll() {
  saving.value = true
  try {
    const payload: RoleBinding[] = rows.value
      .filter(r => r.modelConfigId != null)
      .map(r => ({
        agentRole: r.role.key,
        modelConfigId: r.modelConfigId!,
        profile: r.profile,
      }))
    const count = await novelBindingApi.saveBatch(payload)
    ElMessage.success(`已保存 ${count} 条绑定`)
    await loadBindings()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>
