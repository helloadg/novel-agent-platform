import request from './request'
import type { ModelConfig, ModelTestResult } from '@/types'

export const modelConfigApi = {
  // 配置列表 (active 排在最前)
  list: (): Promise<ModelConfig[]> => request.get('/model-config'),

  create: (payload: ModelConfig): Promise<ModelConfig> => request.post('/model-config', payload),

  update: (id: number, payload: ModelConfig): Promise<ModelConfig> =>
    request.put(`/model-config/${id}`, payload),

  remove: (id: number): Promise<void> => request.delete(`/model-config/${id}`),

  // 启用为当前使用模型 (RAG 与小说智能体立即切换)
  activate: (id: number): Promise<ModelConfig> => request.post(`/model-config/${id}/activate`),

  // 连通性测试: 传表单值测未保存的配置, 只传 id 测已保存的配置
  test: (payload: Partial<ModelConfig>): Promise<ModelTestResult> =>
    request.post('/model-config/test', payload, { timeout: 90_000 }),
}
