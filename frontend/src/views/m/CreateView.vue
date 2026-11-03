<template>
  <div class="h-full flex flex-col">
    <div class="flex-1 overflow-y-auto px-4 py-4 space-y-3">
      <!-- 作品名称 -->
      <div class="bg-white rounded-lg p-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <label class="block text-[14px] font-medium text-[#1d2129] mb-2">作品名称</label>
        <input
          v-model="title"
          type="text"
          maxlength="40"
          placeholder="例: 长安十二时辰 · 续"
          class="w-full box-border bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] text-[#1d2129] outline-none focus:border-[#3b82f6] transition-colors"
        />
      </div>

      <!-- 题材 -->
      <div class="bg-white rounded-lg p-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <label class="block text-[14px] font-medium text-[#1d2129] mb-2">题材类型</label>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="item in genres"
            :key="item.code"
            type="button"
            class="px-4 py-2 rounded-full text-[12px] border transition-colors"
            :class="genre === item.code
              ? 'bg-[#eff6ff] text-[#3b82f6] border-[#60a5fa] font-medium'
              : 'bg-[#f3f4f6] text-[#4e5969] border-transparent'"
            @click="genre = item.code"
          >{{ item.label }}</button>
        </div>
      </div>

      <!-- 章节数 -->
      <div class="bg-white rounded-lg p-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <label class="block text-[14px] font-medium text-[#1d2129] mb-2">章节总数</label>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="n in CHAPTER_OPTIONS"
            :key="n"
            type="button"
            class="px-4 py-2 rounded-full text-[12px] border transition-colors"
            :class="chapters === n && !customChapters
              ? 'bg-[#eff6ff] text-[#3b82f6] border-[#60a5fa] font-medium'
              : 'bg-[#f3f4f6] text-[#4e5969] border-transparent'"
            @click="onPickChapters(n)"
          >{{ n }} 章</button>
        </div>
        <div class="flex items-center gap-2 mt-3">
          <span class="text-[11px] text-[#86909c]">自定义</span>
          <input
            v-model="customChapters"
            type="number"
            min="1"
            max="200"
            placeholder="章节数"
            class="w-20 bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-2 py-1.5 text-[14px] text-center text-[#1d2129] outline-none focus:border-[#3b82f6] transition-colors"
            @input="onCustomChapters"
          />
          <span class="text-[11px] text-[#86909c]">章 (1-200, 当前 {{ chapters }} 章)</span>
        </div>
        <div class="text-[11px] text-[#86909c] mt-2">建议 20-30 章, 多智能体大概 10-20 分钟完成</div>
      </div>

      <!-- 创意 -->
      <div class="bg-white rounded-lg p-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)]">
        <label class="block text-[14px] font-medium text-[#1d2129] mb-2">你的创意 (可选)</label>
        <textarea
          v-model="idea"
          maxlength="500"
          rows="5"
          placeholder="比如: 主角是一个能听到古董低语的修复师, 每件文物背后都有一段被遗忘的往事..."
          class="w-full box-border bg-[#f9fafb] border border-[#e5e6eb] rounded-md px-3 py-2.5 text-[14px] text-[#1d2129] leading-[1.6] outline-none resize-none focus:border-[#3b82f6] transition-colors"
        ></textarea>
      </div>
    </div>

    <!-- 底部提交 -->
    <div
      class="shrink-0 bg-white px-4 pt-4 shadow-[0_-2px_8px_rgba(0,0,0,0.06)]"
      :style="{ paddingBottom: 'calc(16px + env(safe-area-inset-bottom, 0px))' }"
    >
      <button
        type="button"
        class="w-full h-12 rounded-full text-[16px] font-semibold text-white bg-gradient-to-br from-[#3b82f6] to-[#1e3a8a] active:opacity-90 transition"
        :class="{ 'opacity-60': disabled }"
        :disabled="disabled"
        @click="onSubmit"
      >
        {{ submitting ? '正在创建...' : '✨ 启动生成' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { novelApi } from '@/api/novel'
import type { NovelGenreOption } from '@/types'

const CHAPTER_OPTIONS = [10, 20, 30, 50, 80]

const router = useRouter()
const title = ref('')
const idea = ref('')
const chapters = ref(20)
// 自定义章节数输入框内容 (空字符串表示未使用自定义, 此时以 chip 选择为准)
const customChapters = ref('')
const genres = ref<NovelGenreOption[]>([])
const genre = ref('')
const submitting = ref(false)

onMounted(async () => {
  try {
    genres.value = await novelApi.genres()
  } catch (err) {
    console.error('[MobileCreate] load genres fail', err)
    // 后端不可用时给几个默认题材, 保证页面可用
    genres.value = [
      { code: 'FANTASY', label: '玄幻奇幻', description: '' },
      { code: 'URBAN', label: '都市', description: '' },
      { code: 'SCI_FI', label: '科幻', description: '' },
      { code: 'HISTORY', label: '历史', description: '' },
      { code: 'ROMANCE', label: '言情', description: '' },
      { code: 'MYSTERY', label: '悬疑推理', description: '' },
    ]
  }
})

const disabled = computed(() => !title.value.trim() || !genre.value || submitting.value)

/** 自定义章节数输入: 合法数字钳位到 1-200, 非法/清空时保持当前值 */
function onCustomChapters() {
  const n = parseInt(customChapters.value, 10)
  if (!Number.isNaN(n)) {
    chapters.value = Math.max(1, Math.min(200, n))
  }
}

/** 点击常用章节数 chip 时清空自定义输入, 恢复 chip 高亮 */
function onPickChapters(n: number) {
  chapters.value = n
  customChapters.value = ''
}

async function onSubmit() {
  if (disabled.value) return
  submitting.value = true
  try {
    const res = await novelApi.create({
      title: title.value.trim(),
      genre: genre.value,
      userIdea: idea.value.trim() || undefined,
      totalChapters: chapters.value,
    })
    ElMessage.success('已启动生成')
    setTimeout(() => {
      router.replace(`/m/detail/${res.projectId}`)
    }, 600)
  } catch (err) {
    console.error('[MobileCreate] submit fail', err)
  } finally {
    submitting.value = false
  }
}
</script>
