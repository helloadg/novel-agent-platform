<template>
  <div class="h-full flex flex-col" :style="{ paddingTop: 'env(safe-area-inset-top, 0px)' }">
    <!-- 筛选栏 -->
    <div class="shrink-0 flex gap-2 px-4 pt-3 pb-2 overflow-x-auto">
      <button
        v-for="item in FILTERS"
        :key="item.key"
        type="button"
        class="shrink-0 px-3.5 py-1.5 rounded-full text-[12px] border transition-colors"
        :class="filter === item.key
          ? 'bg-[#3b82f6] text-white border-[#3b82f6]'
          : 'bg-white text-[#4e5969] border-[#e5e6eb]'"
        @click="filter = item.key"
      >
        {{ item.label }}
      </button>
    </div>

    <!-- 作品列表 -->
    <div class="flex-1 overflow-y-auto px-4 pb-24">
      <button
        v-for="project in filtered"
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
        <div class="flex flex-wrap gap-3 text-[12px] text-[#86909c]">
          <span>{{ project.genre }}</span>
          <span>共 {{ project.totalChapters }} 章</span>
          <span v-if="project.createTime">{{ project.createTime.slice(0, 10) }}</span>
        </div>
        <div class="flex items-center gap-2 mt-2.5">
          <div class="flex-1 h-1.5 rounded overflow-hidden bg-[#e5e7eb]">
            <div
              class="h-full rounded bg-gradient-to-r from-[#60a5fa] to-[#3b82f6]"
              :style="{ width: `${progressPercent(project)}%` }"
            />
          </div>
          <span class="text-[11px] text-[#86909c] whitespace-nowrap">
            {{ project.currentChapter ?? 0 }}/{{ project.totalChapters }}
          </span>
        </div>
      </button>

      <div v-if="!loading && filtered.length === 0" class="py-14 text-center text-[12px] text-[#86909c]">
        这里空空如也
      </div>
    </div>

    <!-- 新建悬浮按钮 -->
    <button
      type="button"
      class="fixed w-14 h-14 rounded-full flex items-center justify-center text-[28px] leading-none text-white bg-gradient-to-br from-[#3b82f6] to-[#1e3a8a] shadow-[0_4px_16px_rgba(59,130,246,0.5)] active:opacity-90 z-10"
      :style="fabStyle"
      @click="router.push('/m/create')"
    >+</button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { novelApi } from '@/api/novel'
import type { NovelProjectBrief } from '@/types'
import { progressPercent, statusLabel, statusTagClass } from './mobileStatus'

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'running', label: '进行中' },
  { key: 'done', label: '已完成' },
  { key: 'failed', label: '异常' },
]

const router = useRouter()
const items = ref<NovelProjectBrief[]>([])
const filter = ref('all')
const loading = ref(true)

onMounted(async () => {
  try {
    items.value = await novelApi.pageProjects(1, 50)
  } catch (err) {
    console.error('[MobileWorkshop] load fail', err)
  } finally {
    loading.value = false
  }
})

const filtered = computed(() => items.value.filter((p) => matchFilter(p.status)))

function matchFilter(status: string): boolean {
  if (filter.value === 'all') return true
  if (filter.value === 'running') {
    return ['WRITING', 'OUTLINING', 'OUTLINE_READY'].includes(status)
  }
  if (filter.value === 'done') return status === 'COMPLETED'
  if (filter.value === 'failed') return status === 'FAILED'
  return true
}

function openDetail(id: number | string) {
  router.push(`/m/detail/${id}`)
}

/** 悬浮按钮避开底部 Tab 与安全区, 同时在宽屏下贴住 640px 内容列 */
const fabStyle = {
  right: 'max(16px, calc(50% - 320px + 16px))',
  bottom: 'calc(50px + env(safe-area-inset-bottom, 0px) + 16px)',
}
</script>
