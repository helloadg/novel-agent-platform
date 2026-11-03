import request from './request'
import type { ModelConfig } from '../types'

export interface RoleBinding {
  id?: number
  agentRole: string
  modelConfigId: number
  profile: 'creative' | 'balanced' | 'strict'
  createTime?: string
  updateTime?: string
}

export interface AgentRole {
  key: string
  label: string
  description: string
  defaultProfile: 'creative' | 'balanced' | 'strict'
}

/** 可绑定模型的智能体角色清单 (页面下拉与默认档位) */
export const AGENT_ROLES: AgentRole[] = [
  { key: 'world-builder',      label: '🌍 世界构建师',    description: '构建世界观、时代背景、势力分布',   defaultProfile: 'creative' },
  { key: 'outline-architect',  label: '📐 大纲设计师',    description: '将设定展开为多卷大纲与章节序列',   defaultProfile: 'strict' },
  { key: 'chapter-writer',     label: '✍️ 章节写手',     description: '按大纲逐章撰写正文',              defaultProfile: 'creative' },
  { key: 'chapter-polisher',   label: '✨ 润色师',        description: '语言润色、节奏调整、感官细节补充', defaultProfile: 'balanced' },
  { key: 'consistency-reviewer', label: '🔍 一致性审核员', description: '检查人设/时间线/道具前后矛盾',    defaultProfile: 'strict' },
  { key: 'summary',            label: '📝 总结师',        description: '每章结束后生成滚动摘要',          defaultProfile: 'balanced' },
]

export const novelBindingApi = {
  list: () => request.get<RoleBinding[]>('/model-config/bindings'),
  saveBatch: (bindings: RoleBinding[]) =>
    request.post<number>('/model-config/bindings/batch', bindings),
  saveOne: (binding: RoleBinding) =>
    request.post<RoleBinding>('/model-config/bindings', binding),
  delete: (agentRole: string) =>
    request.delete<void>(`/model-config/bindings/${agentRole}`),
  // 辅助: 拉模型配置列表用于下拉选择
  listModels: () => request.get<ModelConfig[]>('/model-config'),
}
