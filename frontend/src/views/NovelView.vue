<template>
  <div class="h-full">
    <div class="grid grid-cols-12 gap-4">
      <!-- 左栏: 创建作品 + 历史作品 -->
      <div class="col-span-12 lg:col-span-3 space-y-4">
        <el-card shadow="never">
          <template #header>
            <div class="flex items-center gap-2 font-medium">
              <el-icon><EditPen /></el-icon>
              <span>新建小说</span>
            </div>
          </template>

          <el-form label-position="top" size="default">
            <el-form-item label="作品名">
              <el-input v-model="form.title" placeholder="例如: 星穹之外" maxlength="200" />
            </el-form-item>
            <el-form-item label="题材类型">
              <el-select v-model="form.genre" class="w-full" placeholder="请选择题材">
                <el-option
                  v-for="genre in store.genres"
                  :key="genre.code"
                  :label="genre.label"
                  :value="genre.code"
                >
                  <span>{{ genre.label }}</span>
                  <span class="text-xs text-gray-400 ml-2">{{ genre.code }}</span>
                </el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="目标章节数">
              <el-input-number v-model="form.totalChapters" :min="1" :max="200" class="w-full" />
            </el-form-item>
            <el-form-item label="你的想法 / 已有大纲 (可选)">
              <el-input
                v-model="form.userIdea"
                type="textarea"
                :rows="5"
                placeholder="可粘贴你已有的大纲, 或描述创意想法, 智能体会在此基础上扩展写作"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="w-full"
              :loading="creating"
              @click="createProject"
            >
              生成世界观与大纲
            </el-button>
          </el-form>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <div class="flex items-center gap-2 font-medium">
              <el-icon><Files /></el-icon>
              <span>历史作品</span>
            </div>
          </template>
          <el-empty v-if="store.projects.length === 0" description="暂无作品" :image-size="60" />
          <ul v-else class="space-y-2">
            <li
              v-for="project in store.projects"
              :key="project.id"
              class="flex items-center justify-between gap-2 px-3 py-2 rounded-lg cursor-pointer hover:bg-blue-50"
              :class="{ 'bg-blue-50': store.detail?.id === project.id }"
              @click="openProject(project.id)"
            >
              <div class="min-w-0">
                <div class="text-sm font-medium truncate">{{ project.title }}</div>
                <div class="text-xs text-gray-400">
                  {{ project.totalChapters }} 章 / 已完成 {{ project.currentChapter || 0 }} 章
                </div>
              </div>
              <el-tag size="small" :type="statusTagType(project.status)">
                {{ statusLabel(project.status) }}
              </el-tag>
            </li>
          </ul>
        </el-card>
      </div>

      <!-- 右栏: 进度 / 大纲 / 章节 -->
      <div class="col-span-12 lg:col-span-9">
        <el-empty v-if="!store.detail" description="创建或选择一个作品开始" />

        <div v-else class="space-y-4">
          <!-- 进度时间线 -->
          <el-card shadow="never">
            <template #header>
              <div class="flex items-center justify-between gap-2 flex-wrap">
                <div class="flex items-center gap-2 font-medium min-w-0">
                  <el-icon class="shrink-0"><DataLine /></el-icon>
                  <span class="truncate">{{ store.detail.title }} · 多智能体协同进度</span>
                  <el-tag v-if="store.status" :type="statusTagType(store.status.status)" size="small">
                    {{ currentStatusLabel }}
                  </el-tag>
                  <el-tag v-if="store.running" size="small" type="warning" effect="plain">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    <span class="ml-1">后台生成中, 可离开页面</span>
                  </el-tag>
                </div>
                <el-button link type="danger" @click="removeProject">删除</el-button>
              </div>
            </template>

            <div class="text-sm text-gray-600 mb-3">
              当前阶段: {{ store.status?.stage || '-' }}
              <span class="ml-4 text-gray-400">
                进度: {{ store.status?.currentChapter || 0 }} / {{ store.status?.totalChapters || 0 }} 章
              </span>
            </div>
            <el-alert
              v-if="store.status?.errorMsg"
              type="error"
              :closable="false"
              :title="store.status.errorMsg"
              class="mb-3"
            />
            <el-alert
              v-if="awaitingWorldReview"
              type="warning"
              :closable="false"
              show-icon
              class="mb-3"
            >
              <template #title>
                <span class="font-medium">世界观与人物设定 · 人工复审</span>
              </template>
              设定师已生成世界观与人物设定。请审阅后放行, 或填写修改意见让设定师重生成。
            </el-alert>

            <el-alert
              v-if="awaitingVolumeReview"
              type="warning"
              :closable="false"
              show-icon
              class="mb-3"
            >
              <template #title>
                <span class="font-medium">第 {{ store.detail?.currentChapter ? Math.ceil(store.detail.currentChapter / 20) : '-' }} 卷大纲 · 人工复审</span>
              </template>
              分卷大纲师已生成本卷分章大纲。请审阅后放行, 或填写修改意见让分卷大纲师重生成。
            </el-alert>

            <el-alert
              v-if="awaitingManual"
              type="warning"
              :closable="false"
              show-icon
              class="mb-3"
            >
              <template #title>
                <span class="font-medium">人工复审 · 第 {{ store.detail.currentChapter || '-' }} 章</span>
              </template>
              本章 AI 审核已完成。请查看正文与审核意见, 可直接放行, 或填写修改意见让写手重写。
            </el-alert>

            <el-scrollbar max-height="min(420px, 55svh)" class="mobile-scrollbar">
              <el-timeline>
                <el-timeline-item
                  v-for="log in timelineLogs"
                  :key="log.id"
                  :timestamp="log.createTime"
                  placement="top"
                  :type="log.agentName === 'reviewer' ? 'warning' : 'primary'"
                >
                  <div class="text-sm">
                    <span class="font-medium">{{ agentLabel(log.agentName) }}</span>
                    <span v-if="log.chapterNo" class="text-gray-400 ml-2">第 {{ log.chapterNo }} 章</span>
                    <span class="text-gray-400 ml-2">{{ log.elapsedMs }}ms</span>
                  </div>
                  <div class="text-xs text-gray-500 mt-1">{{ log.stage }}</div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-if="timelineLogs.length === 0" description="等待智能体执行" :image-size="50" />
            </el-scrollbar>
          </el-card>

          <!-- 世界观 / 人物 -->
          <el-card v-if="store.detail.worldview" shadow="never">
            <template #header>
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-2 font-medium">
                  <el-icon><Location /></el-icon>
                  <span>世界观与人物 (设定师产出)</span>
                </div>
                <el-tag v-if="worldReviewable" type="warning" size="small" effect="plain">人工复审中</el-tag>
                <el-tag v-else type="info" size="small" effect="plain">已定稿</el-tag>
              </div>
            </template>

            <!-- 复审阶段: 可编辑 + 复审操作 -->
            <div v-if="worldReviewable" class="mb-4 p-3 bg-amber-50 border border-amber-200 rounded-lg">
              <div class="text-sm text-amber-700 mb-2 font-medium">
                ⚠ 世界观与人物设定已生成, 请人工复审。可直接编辑并保存, 也可填写修改意见让设定师重生成。
              </div>
              <div class="text-xs text-gray-500 mb-1">世界观 / 规则体系 (可编辑)</div>
              <el-input
                v-model="worldviewDraft"
                type="textarea"
                :rows="8"
                resize="vertical"
                placeholder="世界观内容不能为空"
              />
              <div class="text-xs text-gray-500 mt-3 mb-1">主要人物 (可编辑)</div>
              <el-input
                v-model="charactersDraft"
                type="textarea"
                :rows="8"
                resize="vertical"
                placeholder="主要人物设定"
              />
              <div class="text-xs text-gray-500 mt-3 mb-1">驳回修改意见 (按意见重生成时必填)</div>
              <el-input
                v-model="worldReviewHint"
                type="textarea"
                :rows="2"
                resize="vertical"
                placeholder="比如: 世界观太宏大收不住; 主角性格不够鲜明; 增加一个反派人物..."
              />
              <div class="flex items-center gap-2 mt-2 flex-wrap">
                <el-button
                  size="small"
                  :disabled="!worldDirty || worldSaving"
                  :loading="worldSaving"
                  @click="saveWorldReview"
                >保存修改</el-button>
                <el-button
                  size="small"
                  type="warning"
                  :loading="worldSubmitting"
                  :disabled="worldSaving"
                  @click="worldRerun"
                >按意见重生成</el-button>
                <el-button
                  size="small"
                  type="success"
                  :loading="worldSubmitting"
                  :disabled="worldSaving || !worldviewDraft.trim()"
                  @click="worldApprove"
                >复审通过, 生成大纲</el-button>
              </div>
            </div>

            <!-- 已定稿: 只读展示, 编辑与复审按钮全部隐藏 -->
            <template v-else>
              <div class="text-sm whitespace-pre-wrap leading-relaxed text-gray-700">
                {{ store.detail.worldview }}
              </div>
              <el-divider v-if="store.detail.characters" />
              <div v-if="store.detail.characters" class="text-sm whitespace-pre-wrap leading-relaxed text-gray-700">
                {{ store.detail.characters }}
              </div>
            </template>
          </el-card>

          <!-- 大纲编辑 -->
          <el-card shadow="never">
            <template #header>
              <div class="flex items-center justify-between gap-2 flex-wrap">
                <div class="flex items-center gap-2 font-medium">
                  <el-icon><Document /></el-icon>
                  <span>分章大纲 (可编辑)</span>
                </div>
                <div class="flex items-center gap-2 flex-wrap">
                  <el-button
                    size="small"
                    :disabled="store.running || !outlineDraft.trim()"
                    @click="saveOutline"
                  >
                    保存大纲
                  </el-button>
                  <el-button
                    type="primary"
                    size="small"
                    :disabled="writeDisabled"
                    @click="startWriting"
                  >
                    {{ writeButtonText }}
                  </el-button>
                </div>
              </div>
            </template>
            <el-input
              v-model="outlineDraft"
              type="textarea"
              :rows="12"
              placeholder="大纲生成后可在此修改; 修改只对未写作的章节生效"
            />
            <div class="text-xs text-gray-400 mt-2">
              提示: 保存大纲会同步到写作图的执行状态, 之后点"开始写作"即按此大纲逐章生成。
            </div>

            <!-- 分卷大纲人工复审面板 -->
            <div v-if="awaitingVolumeReview" class="mt-4 p-3 bg-amber-50 rounded border border-amber-200">
              <div class="text-xs text-amber-700 font-medium mb-2">
                ⚠ 第 {{ store.detail?.currentChapter ? Math.ceil(store.detail.currentChapter / 20) : '-' }} 卷大纲待人工复审
              </div>
              <div class="text-xs text-gray-500 mb-1">
                ✍ 修改意见 (可选, 分卷大纲师将按此重生成)
              </div>
              <el-input
                v-model="volumeReviewHint"
                type="textarea"
                :rows="2"
                resize="vertical"
                placeholder="例: 本卷节奏太慢, 加快冲突升级 / 增加一条暗线 / 调整卷末高潮的设计"
              />
              <div class="flex items-center gap-2 mt-2 flex-wrap">
                <el-button
                  size="small"
                  type="warning"
                  :loading="volumeSubmitting"
                  @click="volumeRerun"
                >按意见重生成</el-button>
                <el-button
                  size="small"
                  type="success"
                  :loading="volumeSubmitting"
                  @click="volumeApprove"
                >复审通过, 继续写作</el-button>
              </div>
            </div>
          </el-card>

          <!-- 章节 -->
          <el-card shadow="never">
            <template #header>
              <div class="flex items-center gap-2 font-medium">
                <el-icon><Notebook /></el-icon>
                <span>章节 ({{ store.detail.chapters.length }})</span>
              </div>
            </template>
            <el-empty v-if="store.detail.chapters.length === 0" description="尚未开始写作" :image-size="60" />
            <el-collapse v-else @change="onChapterToggle">
              <el-collapse-item
                v-for="chapter in store.detail.chapters"
                :key="chapter.id"
                :name="chapter.id"
              >
                <template #title>
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-medium">第 {{ chapter.chapterNo }} 章 {{ chapter.title }}</span>
                    <el-tag size="small" :type="chapter.reviewPassed ? 'success' : 'danger'">
                      {{ chapter.reviewPassed ? '审核通过' : '审核未通过' }}
                    </el-tag>
                    <el-tag v-if="chapter.needsReview" size="small" type="warning">待人工复核</el-tag>
                    <span class="text-xs text-gray-400">
                      {{ chapter.wordCount || 0 }} 字 / 重写 {{ chapter.rewriteRounds || 0 }} 轮
                    </span>
                    <span
                      v-if="!chapter.reviewPassed && chapter.reviewSummary"
                      class="text-xs text-amber-600 max-w-[min(500px,55vw)] truncate"
                      :title="chapter.reviewSummary"
                    >审核问题: {{ chapter.reviewSummary }}</span>
                  </div>
                </template>
                <div v-if="chapter.summary" class="text-xs text-gray-500 mb-2">
                  摘要: {{ chapter.summary }}
                </div>
                <template v-if="store.chapter?.id === chapter.id">
                  <template v-if="chapterNeedsManual(chapter) && !store.running">
                    <!-- 人工复审模式: 可编辑正文 + 修改意见 + 操作按钮 -->
                    <div class="text-xs text-amber-600 mb-2 font-medium">
                      ⚠ 本章已完成 AI 审核, 请人工复审。可直接放行, 或填写修改意见让写手重写。
                    </div>
                    <div class="text-xs text-gray-500 mb-1">
                      💡 审核记录:
                    </div>
                    <div
                      v-if="store.chapter?.reviewIssues"
                      class="text-xs text-gray-600 whitespace-pre-wrap mb-3 p-2 bg-gray-50 rounded border border-gray-200 max-h-32 overflow-auto"
                    >{{ reviewIssuesText(store.chapter.reviewIssues) }}</div>
                    <el-input
                      v-model="manualDrafts[chapter.id]"
                      type="textarea"
                      :rows="isMobileViewport ? 10 : 18"
                      resize="vertical"
                      placeholder="章节正文..."
                    />
                    <div class="mt-3 mb-1">
                      <div class="text-xs text-gray-500 mb-1">
                        ✍ 修改意见 (可选, 写手将按此重写。比如: 这段比喻太多删掉; 人物对话更口语化; 增加环境描写)
                      </div>
                      <el-input
                        v-model="manualPrompts[chapter.id]"
                        type="textarea"
                        :rows="2"
                        resize="vertical"
                        placeholder="例: 删掉第X段的比喻 / 让秦小春的语气更冷峻 / 增加一段环境描写"
                      />
                    </div>
                    <div class="mt-2">
                      <el-checkbox v-model="manualFullRewrite[chapter.id]">
                        完全重写 (不参考上一稿, 按大纲/设定/前情从零写)
                      </el-checkbox>
                    </div>
                    <div class="flex items-center gap-2 mt-2 flex-wrap">
                      <el-button
                        size="small"
                        :loading="manualSubmitting"
                        @click="manualSaveDraft(chapter.id)"
                      >仅保存正文</el-button>
                      <el-button
                        size="small"
                        type="warning"
                        :loading="manualSubmitting"
                        @click="manualRerunChapter"
                      >按意见重写</el-button>
                      <el-button
                        size="small"
                        type="success"
                        :loading="manualSubmitting"
                        @click="manualApproveChapter"
                      >复审通过, 继续</el-button>
                    </div>
                  </template>
                  <template v-else>
                    <div class="text-sm whitespace-pre-wrap leading-relaxed">
                      {{ store.chapter.content }}
                    </div>
                  </template>
                </template>
                <div v-else class="text-sm text-gray-400">加载正文中...</div>
                <el-divider v-if="store.chapter?.id === chapter.id && store.chapter?.reviewIssues" />
                <div
                  v-if="store.chapter?.id === chapter.id && store.chapter?.reviewIssues"
                  class="text-xs text-gray-500 whitespace-pre-wrap"
                >
                  审核记录: {{ reviewIssuesText(store.chapter.reviewIssues) }}
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-card>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useNovelStore } from '@/stores/novel'
import type { NovelStatus } from '@/types'

const store = useNovelStore()

/** 视口宽度断点: md=768px, 小屏下 textarea 行数减少避免滚动过多 */
const isMobileViewport = ref(false)
let viewportMq: MediaQueryList | null = null
let viewportHandler: ((e: MediaQueryListEvent) => void) | null = null
function initViewport() {
  viewportMq = window.matchMedia('(max-width: 767px)')
  isMobileViewport.value = viewportMq.matches
  viewportHandler = (e: MediaQueryListEvent) => {
    isMobileViewport.value = e.matches
  }
  if (viewportMq.addEventListener) {
    viewportMq.addEventListener('change', viewportHandler)
  } else {
    // @ts-expect-error Safari 旧版
    viewportMq.addListener(viewportHandler)
  }
}
function disposeViewport() {
  if (!viewportMq || !viewportHandler) return
  if (viewportMq.removeEventListener) {
    viewportMq.removeEventListener('change', viewportHandler)
  } else {
    // @ts-expect-error Safari 旧版
    viewportMq.removeListener(viewportHandler)
  }
}

/** 进度轮询间隔 (毫秒) */
const POLL_INTERVAL = 2500

const form = ref({
  title: '',
  genre: 'XUANHUAN',
  userIdea: '',
  totalChapters: 5,
})
const creating = ref(false)
const outlineDraft = ref('')
let pollTimer: number | undefined
/** 人工干预中正在编辑的章节正文草稿 (key = chapterId) */
const manualDrafts = ref<Record<string, string>>({})
/** 人工干预中写给写手的提示词 (key = chapterId) */
const manualPrompts = ref<Record<string, string>>({})
/** 完全重写: 不参考上一稿 (key = chapterId) */
const manualFullRewrite = ref<Record<string, boolean>>({})
const manualSubmitting = ref(false)

/** 世界观人工复审: 修改意见 */
const worldReviewHint = ref('')
/** 世界观人工复审: 提交中 */
const worldSubmitting = ref(false)
/** 分卷大纲人工复审: 修改意见 */
const volumeReviewHint = ref('')
/** 分卷大纲人工复审: 提交中 */
const volumeSubmitting = ref(false)
/** 世界观人工复审: 可编辑的世界观草稿 */
const worldviewDraft = ref('')
/** 世界观人工复审: 可编辑的人物设定草稿 */
const charactersDraft = ref('')
/** 世界观草稿保存中 */
const worldSaving = ref(false)

/** 项目状态 → 中文展示名 */
const STATUS_LABELS: Record<NovelStatus, string> = {
  DRAFT: '已创建',
  OUTLINING: '生成大纲中',
  OUTLINE_READY: '大纲待确认',
  WRITING: '写作中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

/** 后端 agent_name (下划线) → 中文展示名, 用于协同日志 */
const AGENT_LABELS: Record<string, string> = {
  world_builder: '设定师',
  outline_architect: '大纲师',
  chapter_writer: '写手',
  consistency_reviewer: '审核员',
  chapter_polisher: '润色编辑',
  summary: '摘要员',
}

const timelineLogs = computed(() => [...(store.status?.logs || [])].reverse())

const writeDisabled = computed(() => {
  const status = store.status?.status
  if (!status || store.running) return true
  if (!outlineDraft.value.trim()) {
    // 大纲为空时, 仅 FAILED 状态允许走"重试生成大纲"
    return status !== 'FAILED'
  }
  return !['OUTLINE_READY', 'FAILED'].includes(status)
})

const writeButtonText = computed(() => {
  if (store.status?.status !== 'FAILED') return '开始写作'
  // 失败且大纲为空 = 大纲阶段失败, 按钮变为重试大纲生成
  return outlineDraft.value.trim() ? '从失败处继续写' : '重试生成大纲'
})

function statusLabel(status?: NovelStatus): string {
  return status ? STATUS_LABELS[status] : '-'
}

/** 当前作品状态标签: OUTLINING 阶段需按 stage 区分"生成世界观设定"与"生成大纲" */
const currentStatusLabel = computed(() => {
  const status = store.status?.status
  if (!status) return '-'
  const stage = store.status?.stage || ''
  if (status === 'OUTLINING' && stage.includes('世界观')) {
    if (stage.includes('驳回') || stage.includes('重生成')) return '重新生成设定中'
    if (!stage.includes('开始生成大纲') && !stage.includes('已通过')) return '生成世界观设定中'
  }
  return STATUS_LABELS[status]
})

function statusTagType(status?: NovelStatus): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'OUTLINING':
    case 'WRITING':
      return 'warning'
    case 'OUTLINE_READY':
      return 'primary'
    default:
      return 'info'
  }
}

function agentLabel(agentName: string): string {
  return AGENT_LABELS[agentName] || agentName
}

/** 审核问题列是 JSON 文本, 解析失败时原样展示 */
function reviewIssuesText(raw?: string): string {
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw)
    const issues: Array<{ severity?: string; type?: string; description?: string }> = parsed.issues || []
    if (issues.length === 0) return parsed.passed ? '审核通过, 无问题' : '有建议但未列为问题'
    return issues
      .map((issue) => `[${issue.severity || 'info'}] ${issue.type || ''}: ${issue.description || ''}`)
      .join('\n')
  } catch {
    return raw
  }
}

/** 当前是否处于等待世界观/人物设定人工复审的状态 */
const awaitingWorldReview = computed(() => {
  const stage = store.status?.stage || ''
  if (store.status?.status !== 'OUTLINING') return false
  return stage.includes('世界观') && (stage.includes('人工复审') || stage.includes('等待人工'))
})

/** 当前是否处于等待分卷大纲人工复审的状态 */
const awaitingVolumeReview = computed(() => {
  const stage = store.status?.stage || ''
  if (store.status?.status !== 'WRITING') return false
  return stage.includes('卷大纲') && stage.includes('人工复审')
})

/** 世界观是否可复审/可编辑: 处于复审中断点且图未在跑 (通过或失败后自动变为只读) */
const worldReviewable = computed(() => awaitingWorldReview.value && !store.running)

/** 世界观草稿是否有未保存的修改 */
const worldDirty = computed(() => {
  const detail = store.detail
  if (!detail) return false
  return (worldviewDraft.value || '') !== (detail.worldview || '')
    || (charactersDraft.value || '') !== (detail.characters || '')
})

/** 当前是否处于等待人工复审的状态 (章节级, 排除世界观复审与分卷大纲复审) */
const awaitingManual = computed(() => {
  if (awaitingWorldReview.value || awaitingVolumeReview.value) return false
  const stage = store.status?.stage || ''
  const pausedKeywords = ['等待人工干预', '重写超限', '标记待人工', '待人工复核', '人工放行', '人工触发', '人工干预', '人工复审', '等待人工']
  if (pausedKeywords.some(k => stage.includes(k))) return true
  // FAILED 状态也视为可人工干预
  if (store.status?.status === 'FAILED') return true
  return false
})

/** 当前章是否需要人工操作: 处于等待人工复审状态时一律显示 (放行或提意见重写) */
function chapterNeedsManual(chapter: { needsReview?: boolean; reviewPassed?: boolean }) {
  if (awaitingManual.value) return true
  if (chapter.needsReview) return true
  if (chapter.reviewPassed === false) return true
  return false
}

async function openChapterWithManual(chapterId: string) {
  await store.openChapter(chapterId)
  // 人工干预时预填可编辑草稿
  if (store.chapter?.content) {
    manualDrafts.value[chapterId] = store.chapter.content
  }
}

async function manualSaveDraft(chapterId: string) {
  const content = manualDrafts.value[chapterId]
  if (!content) {
    ElMessage.warning('正文不能为空')
    return
  }
  await store.saveEditedDraft(chapterId, content)
  ElMessage.success('正文已保存')
}

async function manualApproveChapter() {
  if (!store.detail) return
  if (!confirm('确认复审通过, 继续后续润色/摘要流程?')) return
  manualSubmitting.value = true
  try {
    const payload: { editedContent?: string; customPrompt?: string } = {}
    if (store.chapter?.id && manualDrafts.value[store.chapter.id]) {
      payload.editedContent = manualDrafts.value[store.chapter.id]
    }
    if (store.chapter?.id && manualPrompts.value[store.chapter.id]?.trim()) {
      payload.customPrompt = manualPrompts.value[store.chapter.id].trim()
    }
    await store.manualApprove(store.detail.id, payload)
    ElMessage.success('已放行, 写作继续')
  } catch (e) {
    ElMessage.error('放行失败: ' + (e as Error).message)
  } finally {
    manualSubmitting.value = false
  }
}

async function manualRerunChapter() {
  if (!store.detail) return
  if (!confirm('确认让写手按修改意见重写当前章?')) return
  manualSubmitting.value = true
  try {
    let customPrompt: string | undefined
    if (store.chapter?.id && manualPrompts.value[store.chapter.id]?.trim()) {
      customPrompt = manualPrompts.value[store.chapter.id].trim()
    }
    await store.manualRerun(store.detail.id, customPrompt, !!manualFullRewrite.value[store.chapter.id])
    ElMessage.success('已触发重写, 进度会自动刷新')
  } catch (e) {
    ElMessage.error('重写失败: ' + (e as Error).message)
  } finally {
    manualSubmitting.value = false
  }
}

/** 保存世界观草稿到后端; 返回是否成功 (失败时已提示, 调用方可直接 return) */
async function persistWorldDrafts(): Promise<boolean> {
  if (!store.detail || !worldDirty.value) return true
  worldSaving.value = true
  try {
    await store.saveWorld(store.detail.id, worldviewDraft.value, charactersDraft.value)
    return true
  } catch (e) {
    ElMessage.error('保存修改失败: ' + (e as Error).message)
    return false
  } finally {
    worldSaving.value = false
  }
}

/** 世界观人工复审: 保存人工修改的世界观与人物设定 */
async function saveWorldReview() {
  if (!store.detail) return
  if (!worldDirty.value) {
    ElMessage.info('没有需要保存的修改')
    return
  }
  if (await persistWorldDrafts()) {
    ElMessage.success('世界观与人物设定已保存')
  }
}

/** 世界观人工复审: 放行, 继续生成大纲 (先保存人工修改) */
async function worldApprove() {
  if (!store.detail) return
  if (!confirm('确认世界观与人物设定通过, 继续生成大纲?')) return
  worldSubmitting.value = true
  try {
    if (!(await persistWorldDrafts())) return
    await store.worldApprove(store.detail.id)
    worldReviewHint.value = ''
    ElMessage.success('已通过, 开始生成大纲')
  } catch (e) {
    ElMessage.error('放行失败: ' + (e as Error).message)
  } finally {
    worldSubmitting.value = false
  }
}

/** 世界观人工复审: 驳回, 提修改意见让设定师重生成 (先保存人工修改作为上一稿) */
async function worldRerun() {
  if (!store.detail) return
  const hint = worldReviewHint.value.trim()
  if (!hint) {
    ElMessage.warning('请填写修改意见')
    return
  }
  if (!confirm('确认让设定师按修改意见重生成世界观与人物设定?')) return
  worldSubmitting.value = true
  try {
    if (!(await persistWorldDrafts())) return
    await store.worldRerun(store.detail.id, hint)
    worldReviewHint.value = ''
    ElMessage.success('已触发重生成, 进度会自动刷新')
  } catch (e) {
    ElMessage.error('重生成失败: ' + (e as Error).message)
  } finally {
    worldSubmitting.value = false
  }
}

/** 分卷大纲人工复审: 放行, 继续写作 */
async function volumeApprove() {
  if (!store.detail) return
  if (!confirm('确认本卷大纲通过, 继续写作?')) return
  volumeSubmitting.value = true
  try {
    await store.volumeApprove(store.detail.id)
    volumeReviewHint.value = ''
    ElMessage.success('已通过, 继续写作')
  } catch (e) {
    ElMessage.error('放行失败: ' + (e as Error).message)
  } finally {
    volumeSubmitting.value = false
  }
}

/** 分卷大纲人工复审: 驳回, 提修改意见让分卷大纲师重生成 */
async function volumeRerun() {
  if (!store.detail) return
  const hint = volumeReviewHint.value.trim()
  if (!hint) {
    ElMessage.warning('请填写修改意见')
    return
  }
  if (!confirm('确认让分卷大纲师按修改意见重生成本卷大纲?')) return
  volumeSubmitting.value = true
  try {
    await store.volumeRerun(store.detail.id, hint)
    volumeReviewHint.value = ''
    ElMessage.success('已触发重生成, 进度会自动刷新')
  } catch (e) {
    ElMessage.error('重生成失败: ' + (e as Error).message)
  } finally {
    volumeSubmitting.value = false
  }
}

async function createProject() {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写作品名')
    return
  }
  creating.value = true
  try {
    const projectId = await store.createProject({
      title: form.value.title.trim(),
      genre: form.value.genre,
      userIdea: form.value.userIdea.trim(),
      totalChapters: form.value.totalChapters,
    })
    await store.openProject(projectId)
    ElMessage.success('已开始生成世界观与大纲, 进度会自动刷新')
  } finally {
    creating.value = false
  }
}

async function openProject(projectId: number) {
  await store.openProject(projectId)
}

async function saveOutline() {
  if (!store.detail) return
  await store.saveOutline(store.detail.id, outlineDraft.value)
  ElMessage.success('大纲已保存')
}

async function startWriting() {
  if (!store.detail) return
  const status = store.status?.status
  if (!outlineDraft.value.trim() && (status === 'FAILED' || status === 'OUTLINE_READY')) {
    await store.retryOutline(store.detail.id)
    ElMessage.success('已重新触发生成大纲, 进度会自动刷新')
    return
  }
  await store.startWriting(store.detail.id)
  ElMessage.success('已开始逐章写作, 进度会自动刷新')
}

async function removeProject() {
  if (!store.detail) return
  await store.removeProject(store.detail.id)
  ElMessage.success('已删除')
}

async function onChapterToggle(names: string | string[]) {
  // 章节 id 是字符串(雪花 ID), 直接使用避免 Number() 精度丢失
  const ids = Array.isArray(names) ? names : [names]
  const chapterId = ids[ids.length - 1]
  if (chapterId && store.chapter?.id !== chapterId) {
    await store.openChapter(chapterId)
    // 预填人工干预草稿
    if (store.chapter?.content) {
      manualDrafts.value[chapterId] = store.chapter.content
    }
  }
}

/** 详情加载/切换作品/轮询刷新/重新进入页面后, 用后端大纲初始化编辑框 */
watch(
  () => store.detail?.outline,
  (outline) => {
    outlineDraft.value = outline || ''
  },
  { immediate: true },
)

/** 已同步到草稿的后端设定快照: 用于判断草稿是否被用户手动改过 */
let syncedWorldview = ''
let syncedCharacters = ''

/** 把后端最新设定同步到编辑草稿; force=true 时覆盖用户改动 (进入复审时) */
function syncWorldDrafts(force = false) {
  if (!store.detail) return
  const worldview = store.detail.worldview || ''
  const characters = store.detail.characters || ''
  const userEdited = worldviewDraft.value !== syncedWorldview || charactersDraft.value !== syncedCharacters
  if (!force && userEdited) return
  worldviewDraft.value = worldview
  charactersDraft.value = characters
  syncedWorldview = worldview
  syncedCharacters = characters
}

/** 进入世界观复审时 (含重生成后再次进入) 重置修改意见, 并用后端设定初始化草稿 */
watch(
  awaitingWorldReview,
  (reviewing) => {
    if (!reviewing) return
    worldReviewHint.value = ''
    syncWorldDrafts(true)
  },
  { immediate: true },
)

/** 轮询时 status 先于 detail 更新, 这里跟随 detail 变化补齐草稿 (不覆盖用户已手改内容) */
watch(
  () => [store.detail?.worldview, store.detail?.characters],
  () => {
    if (awaitingWorldReview.value) syncWorldDrafts()
  },
)

onMounted(async () => {
  initViewport()
  await store.loadGenres()
  await store.loadProjects()
  // 固定间隔轮询: 仅在生成中才真正请求, 且上一次未返回时跳过
  pollTimer = window.setInterval(() => {
    if (store.running && store.detail) {
      void store.refreshStatus(store.detail.id)
    }
  }, POLL_INTERVAL)
})

onUnmounted(() => {
  disposeViewport()
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})
</script>
