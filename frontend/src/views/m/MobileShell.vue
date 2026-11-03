<template>
  <div class="h-full flex justify-center bg-[#dfe3e8]">
    <div class="w-full max-w-[640px] h-full flex flex-col overflow-hidden bg-[#f5f7fa]">
      <!-- 非 Tab 页面: 顶部返回栏 -->
      <header
        v-if="!isTabPage"
        class="shrink-0 flex items-center min-h-[44px] px-1 bg-white border-b border-[#e5e6eb]"
        :style="{ paddingTop: 'env(safe-area-inset-top, 0px)' }"
      >
        <button
          type="button"
          class="w-10 h-10 shrink-0 flex items-center justify-center text-[26px] leading-none text-[#1d2129] active:opacity-50"
          @click="goBack"
        >‹</button>
        <div class="flex-1 min-w-0 text-center text-[16px] font-medium text-[#1d2129] truncate">
          {{ pageTitle }}
        </div>
        <div class="w-10 shrink-0"></div>
      </header>

      <!-- 页面内容区: 各页面自己控制滚动 -->
      <div class="flex-1 min-h-0">
        <router-view />
      </div>

      <!-- Tab 页面: 底部导航 -->
      <nav
        v-if="isTabPage"
        class="shrink-0 flex bg-white border-t border-[#e5e6eb]"
        :style="{ paddingBottom: 'env(safe-area-inset-bottom, 0px)' }"
      >
        <button
          v-for="tab in TABS"
          :key="tab.path"
          type="button"
          class="flex-1 h-[50px] flex items-center justify-center text-[13px] transition-colors"
          :class="activePath === tab.path ? 'text-[#3b82f6] font-semibold' : 'text-[#86909c]'"
          @click="router.push(tab.path)"
        >
          {{ tab.label }}
        </button>
      </nav>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const TABS = [
  { label: '首页', path: '/m/home' },
  { label: '工坊', path: '/m/workshop' },
  { label: '我的', path: '/m/mine' },
]
const TAB_PATHS = TABS.map((t) => t.path)

const isTabPage = computed(() => TAB_PATHS.includes(route.path))
const activePath = computed(() => (TAB_PATHS.find((p) => route.path.startsWith(p)) || ''))
const pageTitle = computed(() => (route.meta.title as string) || '小说智能体')

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/m/home')
  }
}
</script>
