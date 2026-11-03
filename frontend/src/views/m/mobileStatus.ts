/** 移动端页面共用的状态/进度展示工具, 文案与配色与小程序端保持一致 */

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  OUTLINING: '大纲生成中',
  OUTLINE_READY: '待审核大纲',
  WRITING: '写作中',
  COMPLETED: '已完成',
  FAILED: '异常',
}

export function statusLabel(status: string): string {
  return STATUS_LABEL[status] || status
}

/** 状态标签配色 (Tailwind 类名) */
export function statusTagClass(status: string): string {
  if (status === 'WRITING' || status === 'OUTLINING') return 'bg-[#dbeafe] text-[#3b82f6]'
  if (status === 'COMPLETED') return 'bg-[#d1fae5] text-[#10b981]'
  if (status === 'FAILED') return 'bg-[#fee2e2] text-[#ef4444]'
  if (status === 'OUTLINE_READY') return 'bg-[#fef3c7] text-[#f59e0b]'
  return 'bg-[#f3f4f6] text-[#86909c]'
}

/** 进度条百分比 (已完成记为 100%, 其余最多显示 98% 避免未完成就满格) */
export function progressPercent(project: {
  status: string
  totalChapters: number
  currentChapter?: number
}): number {
  if (project.status === 'COMPLETED') return 100
  if (!project.totalChapters) return 10
  return Math.min(98, Math.round(((project.currentChapter ?? 0) / project.totalChapters) * 100))
}
