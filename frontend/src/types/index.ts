// 后端 Result<T> 通用包装 + 业务实体类型

export interface Result<T> {
  code: number
  message?: string
  msg?: string
  data: T
}

export interface KnowledgeFile {
  id: number            // 数据库自增 ID (IdType.AUTO), JS 安全整数范围内
  title: string
  content?: string
  chunkCount: number
  ownerId: string
  allowedRoles: string
  createTime: string
}

export interface CitationReference {
  index: number
  title: string
  chunkText: string
}

export interface RagResponse {
  question: string
  rewrittenQuery: string
  answer: string
  references: CitationReference[]
  fromCache: boolean
  elapsedMs: number
}

/** 后端 PageResult 直接 data 就是数组, 总数在 response header 或单独接口 —— 这里直接用数组 */
export type KnowledgeFilePage = KnowledgeFile[]

// ==================== 小说写作工坊 ====================

/** 题材选项 (来自 GET /novel/genres) */
export interface NovelGenreOption {
  code: string
  label: string
  description: string
}

/** 作品状态: DRAFT / OUTLINING / OUTLINE_READY / WRITING / COMPLETED / FAILED */
export type NovelStatus = 'DRAFT' | 'OUTLINING' | 'OUTLINE_READY' | 'WRITING' | 'COMPLETED' | 'FAILED'

/** 智能体协同日志 (进度时间线) */
export interface NovelAgentLog {
  id: number
  chapterNo?: number
  agentName: string
  stage?: string
  elapsedMs?: number
  outputText?: string
  createTime: string
}

/** 章节概览 (作品详情内嵌) */
export interface NovelChapterBrief {
  id: number
  chapterNo: number
  title?: string
  summary?: string
  reviewPassed?: boolean
  needsReview?: boolean
  rewriteRounds?: number
  wordCount?: number
  /** 审核问题摘要 (high/medium severity 的 description, 后端截断到 80 字) */
  reviewSummary?: string
}

/** 作品详情 */
export interface NovelProjectDetail {
  id: number
  title: string
  genre: string
  genreLabel: string
  userId?: string
  userIdea?: string
  worldview?: string
  characters?: string
  outline?: string
  status: NovelStatus
  stage?: string
  totalChapters: number
  currentChapter?: number
  errorMsg?: string
  createTime?: string
  chapters: NovelChapterBrief[]
}

/** 历史作品列表项 */
export interface NovelProjectBrief {
  id: number
  title: string
  genre: string
  status: NovelStatus
  stage?: string
  totalChapters: number
  currentChapter?: number
  errorMsg?: string
  createTime?: string
}

/** 轮询进度 */
export interface NovelStatusVO {
  status: NovelStatus
  stage?: string
  totalChapters: number
  currentChapter?: number
  errorMsg?: string
  logs: NovelAgentLog[]
}

/** 章节详情 (含正文与审核结论) */
export interface NovelChapter {
  id: number
  projectId: number
  chapterNo: number
  title?: string
  outline?: string
  content?: string
  summary?: string
  reviewPassed?: boolean
  reviewIssues?: string
  needsReview?: boolean
  rewriteRounds?: number
  wordCount?: number
}

// ==================== 模型配置 ====================

/** 一个 OpenAI 兼容接入点 (URL + API Key + 模型名) */
export interface ModelConfig {
  id?: string
  name: string
  baseUrl: string
  apiKey: string
  modelName: string
  temperature?: number
  timeoutSeconds?: number
  maxTokens?: number | null
  active?: boolean
  createTime?: string
  updateTime?: string
}

/** 连通性测试结果 */
export interface ModelTestResult {
  success: boolean
  elapsedMs: number
  reply?: string
  error?: string
}
