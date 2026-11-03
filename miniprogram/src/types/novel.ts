// 与 Web 前端保持对齐的小说业务类型

export type NovelStatus =
  | 'DRAFT'
  | 'OUTLINING'
  | 'OUTLINE_READY'
  | 'WRITING'
  | 'COMPLETED'
  | 'FAILED'

export interface NovelGenreOption {
  code: string
  label: string
  description: string
}

export interface NovelAgentLog {
  id: number
  chapterNo?: number
  agentName: string
  stage?: string
  elapsedMs?: number
  outputText?: string
  createTime: string
}

export interface NovelChapterBrief {
  id: number
  chapterNo: number
  title?: string
  summary?: string
  reviewPassed?: boolean
  needsReview?: boolean
  rewriteRounds?: number
  wordCount?: number
  reviewSummary?: string
}

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

export interface NovelStatusVO {
  status: NovelStatus
  stage?: string
  totalChapters: number
  currentChapter?: number
  errorMsg?: string
  logs: NovelAgentLog[]
}

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

export interface Result<T> {
  code: number
  message?: string
  msg?: string
  data: T
}
