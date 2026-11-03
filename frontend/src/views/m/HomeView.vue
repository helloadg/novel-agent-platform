<template>
  <div
    class="h-full overflow-y-auto bg-gradient-to-b from-[#eff6ff] to-[#f5f7fa]"
    :style="{ paddingTop: 'env(safe-area-inset-top, 0px)' }"
  >
    <div class="px-4">
      <!-- Hero -->
      <div class="pt-10 pb-5 flex flex-col gap-2">
        <h1 class="text-[20px] font-semibold text-[#1d2129]">创作你的下一本小说</h1>
        <p class="text-[14px] text-[#4e5969]">多智能体协同 · 世界观 → 大纲 → 逐章写作</p>
      </div>

      <!-- 新建入口 -->
      <button
        type="button"
        class="w-full mb-4 p-5 rounded-lg flex items-center gap-3 text-left bg-gradient-to-br from-[#3b82f6] to-[#1e3a8a] shadow-[0_4px_16px_rgba(59,130,246,0.35)] active:opacity-90 active:scale-[0.99] transition"
        @click="router.push('/m/create')"
      >
        <div class="flex-1">
          <div class="text-[16px] font-semibold text-white mb-1">✨ 启动新作品</div>
          <div class="text-[12px] text-white/85">填写题材与想法, 后端 Agent 自动开写</div>
        </div>
        <div class="text-[22px] text-white/90">›</div>
      </button>

      <!-- 进行中 -->
      <div v-if="running.length > 0">
        <div class="flex items-center justify-between py-2">
          <span class="text-[16px] font-semibold text-[#1d2129]">🔥 进行中</span>
        </div>
        <button
          v-for="project in running"
          :key="project.id"
          type="button"
          class="w-full text-left bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)] active:opacity-80 transition"
          @click="openDetail(project.id)"
        >
          <div class="flex items-start justify-between gap-3 mb-1.5">
            <span class="flex-1 min-w-0 text-[16px] font-semibold text-[#1d2129] truncate">
              {{ project.title }}
            </span>
            <span
              class="shrink-0 text-[11px] px-2 py-[2px] rounded-full whitespace-nowrap"
              :class="statusTagClass(project.status)"
            >{{ statusLabel(project.status) }}</span>
          </div>
          <div class="flex gap-3 text-[12px] text-[#86909c] mb-2">
            <span>{{ project.genre }}</span>
            <span>{{ project.currentChapter ?? 0 }} / {{ project.totalChapters }} 章</span>
          </div>
          <div class="h-1 rounded overflow-hidden bg-[#e5e7eb]">
            <div
              class="h-full rounded bg-gradient-to-r from-[#60a5fa] to-[#3b82f6] transition-[width] duration-500"
              :style="{ width: `${progressPercent(project)}%` }"
            />
          </div>
        </button>
      </div>

      <!-- 近期完成 -->
      <div v-if="done.length > 0">
        <div class="flex items-center justify-between py-2">
          <span class="text-[16px] font-semibold text-[#1d2129]">📚 近期完成</span>
          <button type="button" class="text-[12px] text-[#3b82f6]" @click="router.push('/m/workshop')">
            全部作品 ›
          </button>
        </div>
        <button
          v-for="project in done"
          :key="project.id"
          type="button"
          class="w-full text-left bg-white rounded-lg p-4 mb-3 shadow-[0_1px_6px_rgba(0,0,0,0.08)] active:opacity-80 transition"
          @click="openDetail(project.id)"
        >
          <div class="flex items-start justify-between gap-3 mb-1.5">
            <span class="flex-1 min-w-0 text-[16px] font-semibold text-[#1d2129] truncate">
              {{ project.title }}
            </span>
            <span
              class="shrink-0 text-[11px] px-2 py-[2px] rounded-full whitespace-nowrap"
              :class="statusTagClass(project.status)"
            >{{ statusLabel(project.status) }}</span>
          </div>
          <div class="flex gap-3 text-[12px] text-[#86909c]">
            <span>{{ project.genre }}</span>
            <span>共 {{ project.totalChapters }} 章</span>
          </div>
        </button>
      </div>

      <div v-if="!loading && items.length === 0" class="py-10 text-center text-[12px] text-[#86909c]">
        还没有作品, 点上面按钮创建第一本 ✨
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { novelApi } from '@/api/novel'
import type { NovelProjectBrief } from '@/types'
import { progressPercent, statusLabel, statusTagClass } from './mobileStatus'

const router = useRouter()
const items = ref<NovelProjectBrief[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    items.value = await novelApi.pageProjects(1, 10)
  } catch (err) {
    console.error('[MobileHome] load fail', err)
  } finally {
    loading.value = false
  }
})

const running = computed(() =>
  items.value.filter((p) => ['WRITING', 'OUTLINING', 'OUTLINE_READY'].includes(p.status)),
)
const done = computed(() => items.value.filter((p) => p.status === 'COMPLETED').slice(0, 3))

function openDetail(id: number | string) {
  router.push(`/m/detail/${id}`)
}
</script>
