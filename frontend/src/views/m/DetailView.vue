<template>
  <div class="h-full overflow-y-auto bg-[#f5f7fa] px-4 py-4">
    <div v-if="loading && !detail" class="py-20 text-center text-[12px] text-[#86909c]">加载中...</div>

    <div v-else-if="detail" class="pb-6">
      <!-- 头部 -->
      <div class="text-[20px] font-semibold text-[#1d2129] mb-2">{{ detail.title }}</div>
      <div class="flex flex-wrap items-center gap-2 mb-4">
        <span
          class="text-[11px] px-2 py-[3px] rounded-full"
          :class="statusTagClass(detail.status)"
        >{{ statusLabel(detail.status) }}{{ isActive ? ' · 运行中' : (isPaused ? ' · 等待人工' : '') }}</span>
        <span class="text-[12px] text-[#86909c]">{{ detail.genreLabel }}</span>
        <span class="text-[12px] text-[#86909c]">
          {{ status?.currentChapter ?? 0 }} / {{ detail.totalChapters }} 章
        </span>
      </div>

      <!-- 章节人工复审提示: 写作已暂停, 引导用户进入复审 -->
      <div v-if="manualChapter" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-1">🔍 第 {{ manualChapter.chapterNo }} 章人工复审</div>
        <div class="text-[12px] text-[#86909c] mb-2.5">
          本章 AI 审核已完成, 写作已暂停。复审放行或按意见重写后, 才会继续后续章节。
        </div>
        <button
          type="button"
          class="h-9 px-4 rounded-full text-[12px] font-medium text-white bg-[#3b82f6] active:opacity-90"
          @click="openChapter(manualChapter.id)"
        >进入章节复审</button>
      </div>

      <!-- 进度日志 -->
      <div v-if="logs.length > 0" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="flex items-center justify-between mb-2">
          <span class="text-[16px] font-semibold text-[#1d2129]">🧠 进度日志</span>
          <span v-if="isActive" class="text-[12px] text-[#86909c]">自动刷新中</span>
          <span v-else-if="isPaused" class="text-[12px] text-[#f59e0b]">等待人工处理</span>
        </div>
        <div class="max-h-[200px] overflow-y-auto">
          <div
            v-for="log in logs"
            :key="log.id"
            class="flex gap-2 py-2 border-b border-dashed border-[#f2f3f5] last:border-b-0"
          >
            <div class="w-2 h-2 shrink-0 mt-2 rounded-full bg-[#3b82f6]"></div>
            <div class="flex-1 min-w-0">
              <div class="text-[12px] font-medium text-[#1d2129]">
                {{ log.agentName }}<span v-if="log.chapterNo"> · 第 {{ log.chapterNo }} 章</span>
              </div>
              <div v-if="log.outputText" class="text-[11px] text-[#86909c] mt-0.5 truncate">
                {{ log.outputText }}
              </div>
              <div class="text-[10px] text-[#c9cdd4]">{{ log.createTime.slice(11, 19) }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 世界观 -->
      <div v-if="detail.worldview" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-2">🌍 世界观</div>
        <div class="bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] text-[#4e5969] leading-[1.7] whitespace-pre-wrap break-all">{{ detail.worldview }}</div>
      </div>

      <!-- 人物 -->
      <div v-if="detail.characters" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-2">👥 人物设定</div>
        <div class="bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] text-[#4e5969] leading-[1.7] whitespace-pre-wrap break-all">{{ detail.characters }}</div>
      </div>

      <!-- 世界观复审 -->
      <div v-if="showWorldReview" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-2">🔍 世界观人工复审</div>
        <textarea
          v-model="worldHint"
          maxlength="300"
          rows="3"
          placeholder="填修改意见 (可选), 设定师将据此调整世界观..."
          class="w-full box-border bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] leading-[1.6] outline-none resize-none focus:border-[#3b82f6] transition-colors"
        ></textarea>
        <div class="flex flex-wrap gap-2 mt-2.5">
          <button
            type="button"
            class="h-9 px-4 rounded-full text-[12px] font-medium text-white bg-[#10b981] active:opacity-90 disabled:opacity-60"
            :disabled="worldSubmitting"
            @click="onWorldApprove"
          >{{ worldSubmitting ? '处理中...' : '✅ 复审通过, 生成大纲' }}</button>
          <button
            type="button"
            class="h-9 px-4 rounded-full text-[12px] font-medium text-white bg-[#f59e0b] active:opacity-90 disabled:opacity-60"
            :disabled="worldSubmitting"
            @click="onWorldRerun"
          >{{ worldSubmitting ? '处理中...' : '🔄 按意见重生成' }}</button>
        </div>
      </div>

      <!-- 分卷大纲 -->
      <div v-if="detail.outline" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="flex items-center justify-between gap-2 mb-2">
          <span class="text-[16px] font-semibold text-[#1d2129]">📋 分卷大纲</span>
          <button
            v-if="canStartWriting"
            type="button"
            class="shrink-0 h-8 px-3.5 rounded-full text-[12px] font-medium text-white bg-[#3b82f6] active:opacity-90 disabled:opacity-60"
            :disabled="writing"
            @click="onStartWriting"
          >{{ writing ? '启动中...' : '开始写作' }}</button>
        </div>
        <div class="bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] text-[#4e5969] leading-[1.7] whitespace-pre-wrap break-all">{{ detail.outline }}</div>
      </div>

      <!-- 分卷大纲复审 -->
      <div v-if="showVolumeReview" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-2">🔍 分卷大纲人工复审</div>
        <textarea
          v-model="volumeHint"
          maxlength="300"
          rows="3"
          placeholder="填修改意见 (可选), 大纲师将据此调整..."
          class="w-full box-border bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] leading-[1.6] outline-none resize-none focus:border-[#3b82f6] transition-colors"
        ></textarea>
        <div class="flex flex-wrap gap-2 mt-2.5">
          <button
            type="button"
            class="h-9 px-4 rounded-full text-[12px] font-medium text-white bg-[#10b981] active:opacity-90 disabled:opacity-60"
            :disabled="volumeSubmitting"
            @click="onVolumeApprove"
          >{{ volumeSubmitting ? '处理中...' : '✅ 放行, 继续写作' }}</button>
          <button
            type="button"
            class="h-9 px-4 rounded-full text-[12px] font-medium text-white bg-[#f59e0b] active:opacity-90 disabled:opacity-60"
            :disabled="volumeSubmitting"
            @click="onVolumeRerun"
          >{{ volumeSubmitting ? '处理中...' : '🔄 驳回重生成' }}</button>
        </div>
      </div>

      <!-- 章节列表 -->
      <div v-if="detail.chapters.length > 0" class="bg-white rounded-lg p-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <div class="text-[16px] font-semibold text-[#1d2129] mb-2">📖 章节 ({{ detail.chapters.length }})</div>
        <button
          v-for="item in detail.chapters"
          :key="item.id"
          type="button"
          class="w-full flex items-center justify-between gap-2 py-2.5 border-b border-[#f2f3f5] last:border-b-0 text-left active:opacity-70"
          @click="openChapter(item.id)"
        >
          <div class="flex-1 min-w-0">
            <div class="text-[14px] font-medium text-[#1d2129] truncate">
              第 {{ item.chapterNo }} 章 {{ item.title || '(生成中...)' }}
            </div>
            <div class="text-[11px] text-[#86909c] mt-1 truncate">
              {{ item.summary ? item.summary.slice(0, 30) : '—' }} · {{ item.wordCount || 0 }} 字 · 重写 {{ item.rewriteRounds || 0 }} 轮
            </div>
          </div>
          <span v-if="isManualChapter(item.chapterNo)" class="shrink-0 text-[11px] px-2 py-[2px] rounded-full bg-[#fef3c7] text-[#f59e0b]">待复核</span>
          <span v-else-if="item.reviewPassed" class="shrink-0 text-[11px] px-2 py-[2px] rounded-full bg-[#d1fae5] text-[#10b981]">通过</span>
          <span v-else-if="item.needsReview" class="shrink-0 text-[11px] px-2 py-[2px] rounded-full bg-[#fef3c7] text-[#f59e0b]">待复核</span>
          <span v-else-if="item.reviewPassed === false" class="shrink-0 text-[11px] px-2 py-[2px] rounded-full bg-[#fee2e2] text-[#ef4444]">未通过</span>
        </button>
      </div>

      <div v-if="detail.status === 'COMPLETED'" class="py-8 text-center text-[12px] text-[#86909c]">
        🎉 整本小说已完成创作
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { novelApi } from '@/api/novel'
import type { NovelAgentLog, NovelProjectDetail, NovelStatusVO } from '@/types'
import { statusLabel, statusTagClass } from './mobileStatus'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id as string

/** 章节级人工复审中断点的阶段关键词 */
const MANUAL_STAGE_KEYWORDS = [
  '等待人工干预',
  '重写超限',
  '标记待人工',
  '待人工复核',
  '人工放行',
  '人工触发',
  '人工干预',
  '人工复审',
  '等待人工',
]

const detail = ref<NovelProjectDetail | null>(null)
const status = ref<NovelStatusVO | null>(null)
const loading = ref(true)

const worldHint = ref('')
const worldSubmitting = ref(false)
const volumeHint = ref('')
const volumeSubmitting = ref(false)
const writing = ref(false)

let pollTimer: number | undefined
let lastSignature = ''

const logs = computed<NovelAgentLog[]>(() => (status.value?.logs || []).slice(0, 30))
const isRunning = computed(() =>
  ['WRITING', 'OUTLINING'].includes(status.value?.status || ''),
)

/** 是否停在任一人工复审中断点 (此时后台并没有在写作) */
const isPaused = computed(() =>
  showWorldReview.value || showVolumeReview.value || awaitingChapterManual.value,
)

/** 后台确实在跑 (排除人工复审中断点) */
const isActive = computed(() => isRunning.value && !isPaused.value)

/** 当前等待人工复审的章节条目 */
const manualChapter = computed(() => {
  if (!awaitingChapterManual.value || !detail.value) return null
  const no = status.value?.currentChapter
  return detail.value.chapters.find((c) => c.chapterNo === no) || null
})

/** 大纲已生成, 等待用户确认开始逐章写作 */
const canStartWriting = computed(() => status.value?.status === 'OUTLINE_READY')

/** 世界观人工复审中断点 (后端 stage 文案: "...等待人工复审") */
const showWorldReview = computed(() => {
  const stage = status.value?.stage || ''
  return status.value?.status === 'OUTLINING'
    && stage.includes('世界观')
    && (stage.includes('人工复审') || stage.includes('等待人工'))
})

/** 分卷大纲人工复审中断点 (后端 stage 文案: "第 N 卷大纲已生成, 等待人工复审") */
const showVolumeReview = computed(() => {
  const stage = status.value?.stage || ''
  return status.value?.status === 'WRITING'
    && stage.includes('卷大纲')
    && stage.includes('人工复审')
})

/** 是否停在章节级人工复审中断点 (排除世界观/分卷大纲复审) */
const awaitingChapterManual = computed(() => {
  const stage = status.value?.stage || ''
  if (!stage || stage.includes('世界观') || stage.includes('卷大纲')) return false
  return MANUAL_STAGE_KEYWORDS.some((k) => stage.includes(k))
})

/** 本章是否为当前等待人工复审的章节 (AI 可能已判通过, 但仍需人工复核) */
function isManualChapter(chapterNo: number): boolean {
  return awaitingChapterManual.value && status.value?.currentChapter === chapterNo
}

async function loadDetail() {
  try {
    detail.value = await novelApi.detail(projectId)
  } catch (err) {
    console.error('[MobileDetail] load detail fail', err)
  } finally {
    loading.value = false
  }
}

async function pollStatus() {
  try {
    const next = await novelApi.status(projectId)
    status.value = next
    const signature = `${next.status}|${next.stage || ''}|${next.currentChapter ?? 0}|${(next.logs || []).length}`
    if (signature !== lastSignature) {
      lastSignature = signature
      await loadDetail()
    }
  } catch (err) {
    console.error('[MobileDetail] poll fail', err)
  }
}

onMounted(async () => {
  await Promise.all([loadDetail(), pollStatus()])
  pollTimer = window.setInterval(pollStatus, 3000)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})

async function refresh() {
  await Promise.all([loadDetail(), pollStatus()])
}

async function onWorldApprove() {
  worldSubmitting.value = true
  try {
    await novelApi.worldApprove(projectId)
    worldHint.value = ''
    ElMessage.success('已放行')
    await refresh()
  } catch (err) {
    console.error('[MobileDetail] world approve fail', err)
  } finally {
    worldSubmitting.value = false
  }
}

async function onWorldRerun() {
  worldSubmitting.value = true
  try {
    await novelApi.worldRerun(projectId, worldHint.value.trim() || undefined)
    worldHint.value = ''
    ElMessage.success('已驳回重生成')
    await refresh()
  } catch (err) {
    console.error('[MobileDetail] world rerun fail', err)
  } finally {
    worldSubmitting.value = false
  }
}

async function onVolumeApprove() {
  volumeSubmitting.value = true
  try {
    await novelApi.volumeApprove(projectId)
    volumeHint.value = ''
    ElMessage.success('已放行')
    await refresh()
  } catch (err) {
    console.error('[MobileDetail] volume approve fail', err)
  } finally {
    volumeSubmitting.value = false
  }
}

async function onVolumeRerun() {
  volumeSubmitting.value = true
  try {
    await novelApi.volumeRerun(projectId, volumeHint.value.trim() || undefined)
    volumeHint.value = ''
    ElMessage.success('已驳回重生成')
    await refresh()
  } catch (err) {
    console.error('[MobileDetail] volume rerun fail', err)
  } finally {
    volumeSubmitting.value = false
  }
}

async function onStartWriting() {
  writing.value = true
  try {
    await novelApi.write(projectId)
    ElMessage.success('已开始逐章写作')
    await refresh()
  } catch (err) {
    console.error('[MobileDetail] start writing fail', err)
  } finally {
    writing.value = false
  }
}

function openChapter(chapterId: number | string) {
  router.push(`/m/chapter/${chapterId}`)
}
</script>
