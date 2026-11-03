import request from './request'
import type {
  NovelChapter,
  NovelGenreOption,
  NovelProjectBrief,
  NovelProjectDetail,
  NovelStatusVO,
} from '@/types'

export const novelApi = {
  // 题材列表 (后端枚举为单一事实源)
  genres: (): Promise<NovelGenreOption[]> => request.get('/novel/genres'),

  // 创建作品: 后端立即返回 projectId, 大纲在后台异步生成
  // 注意: 后端返回的 ID 是字符串 —— 雪花 ID 超出 JS 安全整数范围, 用数字会丢精度
  create: (payload: {
    title: string
    genre: string
    userIdea?: string
    totalChapters?: number
  }): Promise<{ projectId: number | string }> => request.post('/novel/project', payload),

  // 历史作品分页
  pageProjects: (page = 1, size = 10): Promise<NovelProjectBrief[]> =>
    request.get('/novel/project/page', { params: { page, size } }),

  // 作品详情 (设定 / 大纲 / 章节概览)
  detail: (projectId: number | string): Promise<NovelProjectDetail> =>
    request.get(`/novel/project/${projectId}`),

  // 轮询进度
  status: (projectId: number | string): Promise<NovelStatusVO> =>
    request.get(`/novel/project/${projectId}/status`),

  // 保存用户修改后的大纲
  saveOutline: (projectId: number | string, outline: string): Promise<void> =>
    request.put(`/novel/project/${projectId}/outline`, { outline }),

  // 保存人工修改后的世界观与人物设定 (仅在复审阶段有效)
  saveWorld: (projectId: number | string, worldview: string, characters: string): Promise<void> =>
    request.put(`/novel/project/${projectId}/world`, { worldview, characters }),

  // 世界观/人物设定人工复审: 放行, 继续生成大纲
  worldApprove: (projectId: number | string): Promise<void> =>
    request.post(`/novel/project/${projectId}/world-approve`),

  // 世界观/人物设定人工复审: 驳回, 提修改意见让设定师重生成
  worldRerun: (projectId: number | string, hint?: string): Promise<void> =>
    request.post(`/novel/project/${projectId}/world-rerun`, hint ? { hint } : {}),

  // 分卷大纲人工复审: 放行, 继续写作
  volumeApprove: (projectId: number | string): Promise<void> =>
    request.post(`/novel/project/${projectId}/volume-approve`),

  // 分卷大纲人工复审: 驳回, 提修改意见让分卷大纲师重生成
  volumeRerun: (projectId: number | string, hint?: string): Promise<void> =>
    request.post(`/novel/project/${projectId}/volume-rerun`, hint ? { hint } : {}),

  // 开始 / 继续逐章写作
  write: (projectId: number | string): Promise<void> =>
    request.post(`/novel/project/${projectId}/write`),

  // 大纲生成失败后的重试 (有检查点续跑大纲师, 无则从头重新生成)
  retryOutline: (projectId: number | string): Promise<void> =>
    request.post(`/novel/project/${projectId}/retry-outline`),

  // 硬门禁触发后的人工干预: 强行放行当前章, 继续正常写作
  manualApprove: (projectId: number | string, payload?: { editedContent?: string; customPrompt?: string }): Promise<void> =>
    request.post(`/novel/project/${projectId}/manual-approve`, payload || {}),

  // 硬门禁触发后的人工干预: 重写当前章 (退回 chapter_writer)
  manualRerun: (projectId: number | string, payload?: { customPrompt?: string; fullRewrite?: boolean }): Promise<void> =>
    request.post(`/novel/project/${projectId}/manual-rerun`, payload || {}),

  // 保存人工编辑后的章节正文
  saveEditedDraft: (chapterId: string, content: string): Promise<void> =>
    request.put(`/novel/chapter/${chapterId}/draft`, { content }),

  // 章节详情
  chapter: (chapterId: string): Promise<NovelChapter> =>
    request.get(`/novel/chapter/${chapterId}`),

  // 删除作品
  remove: (projectId: number | string): Promise<void> =>
    request.delete(`/novel/project/${projectId}`),
}
