<template>
  <div class="h-full flex flex-col bg-[#f5f7fa]">
    <div v-if="!chapter" class="flex-1 flex items-center justify-center text-[12px] text-[#86909c]">
      加载中...
    </div>

    <template v-else>
      <div class="flex-1 overflow-y-auto px-4 py-4">
        <!-- 章节头部 -->
        <div class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
          <div class="flex flex-wrap items-center justify-between gap-2 mb-2">
            <span class="text-[16px] font-semibold text-[#1d2129]">
              第 {{ chapter.chapterNo }} 章 {{ chapter.title || '(未命名)' }}
            </span>
            <span v-if="chapter.reviewPassed" class="text-[11px] px-2 py-[2px] rounded-full bg-[#d1fae5] text-[#10b981]">✅ 通过</span>
            <span v-else-if="chapter.needsReview" class="text-[11px] px-2 py-[2px] rounded-full bg-[#fef3c7] text-[#f59e0b]">⚠️ 待复核</span>
            <span v-else-if="chapter.reviewPassed === false" class="text-[11px] px-2 py-[2px] rounded-full bg-[#fee2e2] text-[#ef4444]">❌ 未通过</span>
          </div>
          <div class="text-[12px] text-[#86909c]">
            {{ chapter.wordCount || 0 }} 字 · 重写 {{ chapter.rewriteRounds || 0 }} 轮
          </div>
        </div>

        <!-- 审核问题 -->
        <div v-if="chapter.reviewIssues" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
          <div class="text-[12px] font-medium text-[#1d2129] mb-2">🔍 AI 审核发现的问题</div>
          <div class="max-h-[180px] overflow-y-auto bg-[#fef3c7] border border-[#fde68a] rounded-md px-3 py-2.5 text-[12px] text-[#92400e] leading-[1.6] whitespace-pre-wrap break-all">{{ chapter.reviewIssues }}</div>
        </div>

        <!-- 本章摘要 -->
        <div v-if="chapter.summary" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
          <div class="text-[12px] font-medium text-[#1d2129] mb-2">📝 本章摘要</div>
          <div class="bg-[#eff6ff] border border-[#bfdbfe] rounded-md px-3 py-2.5 text-[12px] text-[#1e40af] leading-[1.6] whitespace-pre-wrap break-all">{{ chapter.summary }}</div>
        </div>

        <!-- 正文 -->
        <div class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
          <div class="text-[12px] font-medium text-[#1d2129] mb-2">✍️ 章节正文 (可直接修改)</div>
          <textarea
            ref="bodyEl"
            v-model="bodyDraft"
            :disabled="!operable"
            class="w-full box-border min-h-[260px] bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-3 text-[14px] text-[#1d2129] leading-[1.8] outline-none resize-none focus:border-[#3b82f6] transition-colors disabled:opacity-80"
            @input="autoGrow"
          ></textarea>
          <div class="text-[11px] text-[#86909c] mt-1.5">修改后请选择底部按钮: 仅保存正文 / 放行 / 重写</div>
        </div>

        <!-- 修改意见 -->
        <div v-if="operable" class="bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
          <div class="text-[12px] font-medium text-[#1d2129] mb-2">💬 修改意见 (仅在"按意见重写"时使用)</div>
          <textarea
            v-model="hint"
            maxlength="500"
            rows="3"
            placeholder="例: 这段比喻太多删掉; 人物对话更口语化; 增加环境描写"
            class="w-full box-border bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] leading-[1.6] outline-none resize-none focus:border-[#3b82f6] transition-colors"
          ></textarea>
          <div class="text-[11px] text-[#86909c] mt-1.5">意见会传递给写手 Agent, 作为重写本章的指令</div>
        </div>

        <div v-if="!operable" class="py-8 text-center text-[12px] text-[#86909c]">本章已通过审核</div>
      </div>

      <!-- 底部操作 -->
      <div
        v-if="operable"
        class="shrink-0 flex gap-2 bg-white px-4 pt-4 shadow-[0_-2px_8px_rgba(0,0,0,0.06)]"
        :style="{ paddingBottom: 'calc(16px + env(safe-area-inset-bottom, 0px))' }"
      >
        <button
          type="button"
          class="flex-1 h-12 rounded-full text-[14px] font-medium text-[#3b82f6] bg-white border border-[#60a5fa] active:opacity-80 disabled:opacity-60"
          :disabled="saving"
          @click="onSaveDraft"
        >{{ saving ? '处理中' : '仅保存正文' }}</button>
        <button
          type="button"
          class="flex-1 h-12 rounded-full text-[14px] font-medium text-white bg-[#f59e0b] active:opacity-90 disabled:opacity-60"
          :disabled="saving"
          @click="onRerun"
        >{{ saving ? '处理中' : '🔄 按意见重写' }}</button>
        <button
          type="button"
          class="flex-1 h-12 rounded-full text-[14px] font-medium text-white bg-[#10b981] active:opacity-90 disabled:opacity-60"
          :disabled="saving"
          @click="onApprove"
        >{{ saving ? '处理中' : '✅ 放行, 继续' }}</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { novelApi } from '@/api/novel'
import type { NovelChapter } from '@/types'

const route = useRoute()
const router = useRouter()
const chapterId = route.params.id as string

const chapter = ref<NovelChapter | null>(null)
const bodyDraft = ref('')
const hint = ref('')
const saving = ref(false)
const bodyEl = ref<HTMLTextAreaElement | null>(null)

const reviewDone = computed(() => chapter.value?.reviewPassed === true)
/** 当前项目是否停在章节级人工复审中断点 */
const awaitingManual = ref(false)
/**
 * 是否允许人工操作: AI 未判通过的章节始终可操作;
 * AI 已判通过但项目正等待本章人工复审时也允许操作 (避免错过人工复核环节)
 */
const operable = computed(() => {
  if (!chapter.value) return false
  if (!reviewDone.value) return true
  return awaitingManual.value
})

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

onMounted(async () => {
  try {
    chapter.value = await novelApi.chapter(chapterId)
    const loaded = chapter.value
    bodyDraft.value = loaded.content || ''
    await nextTick()
    autoGrow()

    // 章节是否正处于人工复审中断点 (后端 stage 文案包含人工复审关键词, 且当前章号一致)
    const status = await novelApi.status(loaded.projectId)
    const stage = status.stage || ''
    awaitingManual.value = !stage.includes('世界观')
      && !stage.includes('卷大纲')
      && status.currentChapter === loaded.chapterNo
      && MANUAL_STAGE_KEYWORDS.some((k) => stage.includes(k))
  } catch (err) {
    console.error('[MobileChapter] load fail', err)
  }
})

watch(bodyDraft, () => nextTick(autoGrow))

/** 正文输入框按内容自动撑高, 避免长章节只能在小框里滚动 */
function autoGrow() {
  const el = bodyEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

async function saveDraftIfChanged() {
  if (!chapter.value) return
  if (bodyDraft.value !== (chapter.value.content || '')) {
    await novelApi.saveEditedDraft(chapterId, bodyDraft.value)
  }
}

async function onSaveDraft() {
  saving.value = true
  try {
    if (!bodyDraft.value.trim()) {
      ElMessage.warning('正文不能为空')
      return
    }
    await novelApi.saveEditedDraft(chapterId, bodyDraft.value)
    if (chapter.value) chapter.value.content = bodyDraft.value
    ElMessage.success('已保存正文')
  } catch (err) {
    console.error('[MobileChapter] save draft fail', err)
  } finally {
    saving.value = false
  }
}

async function onApprove() {
  if (!chapter.value) return
  saving.value = true
  try {
    await saveDraftIfChanged()
    await novelApi.manualApprove(chapter.value.projectId, { editedContent: bodyDraft.value })
    ElMessage.success('已放行')
    setTimeout(() => router.back(), 600)
  } catch (err) {
    console.error('[MobileChapter] approve fail', err)
  } finally {
    saving.value = false
  }
}

async function onRerun() {
  if (!chapter.value) return
  if (!hint.value.trim()) {
    ElMessage.warning('请填写修改意见')
    return
  }
  saving.value = true
  try {
    await saveDraftIfChanged()
    await novelApi.manualRerun(chapter.value.projectId, { customPrompt: hint.value.trim() })
    ElMessage.success('已提交重写')
    setTimeout(() => router.back(), 600)
  } catch (err) {
    console.error('[MobileChapter] rerun fail', err)
  } finally {
    saving.value = false
  }
}
</script>
