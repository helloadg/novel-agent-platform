import { defineStore } from 'pinia'
import { novelApi } from '@/api/novel'
import type {
  NovelChapter,
  NovelGenreOption,
  NovelProjectBrief,
  NovelProjectDetail,
  NovelStatusVO,
} from '@/types'

/** 需要持续轮询进度的状态 (终态不再轮询) */
const RUNNING_STATUS = ['OUTLINING', 'WRITING']

/** stage 含这些关键词时, 图已暂停等待人工, 不算 running */
const PAUSED_STAGE_KEYWORDS = ['人工复核', '人工放行', '人工触发', '人工干预', '重写超限', '等待人工', '标记待人工', '人工复审']

export const useNovelStore = defineStore('novel', {
  state: () => ({
    genres: [] as NovelGenreOption[],
    projects: [] as NovelProjectBrief[],
    detail: null as NovelProjectDetail | null,
    status: null as NovelStatusVO | null,
    chapter: null as NovelChapter | null,
    loading: false,
    /** 防止上一次轮询未返回时请求堆积 */
    polling: false,
    /** 人工触发重写/放行后, 图正在后台执行, 临时让 running=true 以启用轮询 */
    manualRunning: false,
  }),
  getters: {
    /** 是否处于生成中 (用于控制轮询与按钮禁用) */
    running: (state): boolean => {
      if (state.manualRunning) return true  // 人工触发后临时视为 running
      if (!state.status) return false
      if (!RUNNING_STATUS.includes(state.status.status)) return false
      // stage 含人工干预相关关键词 → 图已暂停等待, 不算 running
      const stage = state.status.stage || ''
      if (PAUSED_STAGE_KEYWORDS.some(k => stage.includes(k))) return false
      return true
    },
  },
  actions: {
    async loadGenres() {
      if (this.genres.length > 0) return
      this.genres = await novelApi.genres()
    },
    async createProject(payload: {
      title: string
      genre: string
      userIdea?: string
      totalChapters?: number
    }): Promise<string> {
      const { projectId } = await novelApi.create(payload)
      await this.loadProjects()
      return projectId
    },
    async loadProjects() {
      this.projects = await novelApi.pageProjects(1, 20)
    },
    async openProject(projectId: number) {
      this.detail = await novelApi.detail(projectId)
      this.status = await novelApi.status(projectId)
      this.chapter = null
    },
    /** 轮询进度 (running 时由页面定时调用) */
    async refreshStatus(projectId: number) {
      if (this.polling) return
      this.polling = true
      try {
        this.status = await novelApi.status(projectId)
        // 生成中章节数量会变化, 同步刷新详情里的章节列表
        if (this.detail && this.detail.id === projectId) {
          this.detail = await novelApi.detail(projectId)
        }
        // 人工触发后, 如果 stage 已经不含人工关键词 (说明图跑完了或跑到下一个节点了), 清 manualRunning
        if (this.manualRunning && this.status) {
          const stage = this.status.stage || ''
          const stillPaused = PAUSED_STAGE_KEYWORDS.some(k => stage.includes(k))
          if (stillPaused) {
            // 图已暂停等待人工 (到达下一个中断点), 清 manualRunning 让 running=false 停止轮询
            this.manualRunning = false
          } else if (this.status.status !== 'WRITING' && this.status.status !== 'OUTLINING') {
            // 图跑到终态 (COMPLETED/FAILED), 清 manualRunning
            this.manualRunning = false
          }
          // 其余情况 (WRITING/OUTLINING 且非暂停): 图仍在跑, manualRunning 保持 true 继续轮询
        }
      } finally {
        this.polling = false
      }
    },
    async saveOutline(projectId: number, outline: string) {
      await novelApi.saveOutline(projectId, outline)
      if (this.detail) {
        this.detail.outline = outline
      }
      await this.refreshStatus(projectId)
    },
    async startWriting(projectId: number) {
      await novelApi.write(projectId)
      await this.refreshStatus(projectId)
    },
    /** 大纲阶段失败后的重试 (大纲为空时由页面调用) */
    async retryOutline(projectId: number) {
      await novelApi.retryOutline(projectId)
      await this.refreshStatus(projectId)
    },
    /** 保存人工修改后的世界观与人物设定 (仅在复审阶段有效) */
    async saveWorld(projectId: number, worldview: string, characters: string) {
      await novelApi.saveWorld(projectId, worldview, characters)
      if (this.detail && this.detail.id === projectId) {
        this.detail.worldview = worldview
        this.detail.characters = characters
      }
      await this.refreshStatus(projectId)
    },
    /** 世界观/人物设定人工复审: 放行, 继续生成大纲 */
    async worldApprove(projectId: number) {
      await novelApi.worldApprove(projectId)
      this.manualRunning = true
      await this.refreshStatus(projectId)
    },
    /** 世界观/人物设定人工复审: 驳回, 提修改意见让设定师重生成 */
    async worldRerun(projectId: number, hint?: string) {
      await novelApi.worldRerun(projectId, hint)
      this.manualRunning = true
      await this.refreshStatus(projectId)
    },
    /** 分卷大纲人工复审: 放行, 继续写作 */
    async volumeApprove(projectId: number) {
      await novelApi.volumeApprove(projectId)
      this.manualRunning = true
      await this.refreshStatus(projectId)
    },
    /** 分卷大纲人工复审: 驳回, 提修改意见让分卷大纲师重生成 */
    async volumeRerun(projectId: number, hint?: string) {
      await novelApi.volumeRerun(projectId, hint)
      this.manualRunning = true
      await this.refreshStatus(projectId)
    },
    /** 硬门禁触发后: 强行放行当前章, 继续写作 (可带人工编辑正文 + 提示词) */
    async manualApprove(projectId: number, payload?: { editedContent?: string; customPrompt?: string }) {
      await novelApi.manualApprove(projectId, payload)
      this.manualRunning = true  // 图正在后台跑, 开启轮询
      await this.refreshStatus(projectId)
    },
    /** 硬门禁触发后: 重写当前章 (可带提示词; fullRewrite=true 时不参考上一稿) */
    async manualRerun(projectId: number, customPrompt?: string, fullRewrite = false) {
      await novelApi.manualRerun(projectId, { customPrompt, fullRewrite })
      this.manualRunning = true  // 图正在后台跑, 开启轮询
      await this.refreshStatus(projectId)
    },
    /** 保存人工编辑的章节正文 */
    async saveEditedDraft(chapterId: string, content: string) {
      await novelApi.saveEditedDraft(chapterId, content)
      if (this.chapter?.id === chapterId) {
        this.chapter.content = content
      }
    },
    async openChapter(chapterId: string) {
      this.chapter = await novelApi.chapter(chapterId)
    },
    async removeProject(projectId: number) {
      await novelApi.remove(projectId)
      if (this.detail?.id === projectId) {
        this.detail = null
        this.status = null
      }
      await this.loadProjects()
    },
    reset() {
      this.detail = null
      this.status = null
      this.chapter = null
    },
  },
})
