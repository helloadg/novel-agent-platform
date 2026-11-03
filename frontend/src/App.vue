<template>
  <div class="app-root h-full overflow-hidden bg-gray-50 flex flex-col md:flex-row">
    <!-- 桌面端: 左侧固定侧栏 -->
    <aside v-if="!isMobileRoute" class="sidebar hidden md:flex w-56 shrink-0 flex-col">
      <div class="flex items-center gap-2 px-5 h-14">
        <el-icon class="text-xl text-white"><EditPen /></el-icon>
        <span class="text-base font-semibold text-white tracking-wide">小说智能体平台</span>
      </div>
      <el-menu
        :default-active="route.path"
        router
        class="sidebar-menu flex-1 !border-r-0"
      >
        <el-menu-item index="/novel">
          <el-icon><EditPen /></el-icon>
          <span>小说工坊</span>
        </el-menu-item>
        <el-menu-item index="/models">
          <el-icon><Setting /></el-icon>
          <span>模型配置</span>
        </el-menu-item>
        <el-menu-item index="/bindings">
          <el-icon><Connection /></el-icon>
          <span>角色-模型绑定</span>
        </el-menu-item>
      </el-menu>
      <div class="px-5 py-4 text-xs text-white/50 border-t border-white/10">
        novel-agent-platform · v1.0
      </div>
    </aside>

    <!-- 主内容区: 桌面用 el-main / 移动端自己的容器 -->
    <el-main v-if="!isMobileRoute" class="hidden md:flex flex-1 overflow-auto p-4 bg-gray-50">
      <router-view />
    </el-main>

    <!-- 移动端: 顶栏 + 独立滚动区 -->
    <div v-if="!isMobileRoute" class="md:hidden flex flex-col h-full">
      <header class="mobile-header flex items-center gap-3 px-3 h-14 bg-gradient-to-r from-blue-900 to-blue-700 text-white shadow shrink-0">
        <el-icon class="text-xl cursor-pointer" @click="drawerVisible = true">
          <Menu />
        </el-icon>
        <el-icon class="text-xl"><EditPen /></el-icon>
        <span class="text-base font-semibold tracking-wide">小说智能体平台</span>
        <span class="ml-auto text-xs opacity-80">{{ mobileTitle }}</span>
      </header>
      <div class="flex-1 overflow-auto p-3">
        <router-view />
      </div>
    </div>

    <!-- 移动端侧栏抽屉 -->
    <el-drawer
      v-if="!isMobileRoute"
      v-model="drawerVisible"
      direction="ltr"
      :size="260"
      :with-header="false"
      class="sidebar-drawer"
    >
      <div class="flex items-center gap-2 px-5 h-14">
        <el-icon class="text-xl text-white"><EditPen /></el-icon>
        <span class="text-base font-semibold text-white tracking-wide">小说智能体平台</span>
      </div>
      <el-menu
        :default-active="route.path"
        router
        class="sidebar-menu flex-1 !border-r-0"
        @select="drawerVisible = false"
      >
        <el-menu-item index="/novel">
          <el-icon><EditPen /></el-icon>
          <span>小说工坊</span>
        </el-menu-item>
        <el-menu-item index="/models">
          <el-icon><Setting /></el-icon>
          <span>模型配置</span>
        </el-menu-item>
        <el-menu-item index="/bindings">
          <el-icon><Connection /></el-icon>
          <span>角色-模型绑定</span>
        </el-menu-item>
      </el-menu>
      <div class="px-5 py-4 text-xs text-white/50 border-t border-white/10">
        novel-agent-platform · v1.0
      </div>
    </el-drawer>

    <!-- 移动端独立页面: 自带底部 Tab, 不显示桌面侧栏与汉堡 Header -->
    <div v-else class="h-full">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { EditPen, Menu, Setting, Connection } from '@element-plus/icons-vue'

const route = useRoute()
const drawerVisible = ref(false)

const MOBILE_TITLES: Record<string, string> = {
  novel: '小说工坊',
  models: '模型配置',
  bindings: '角色绑定',
}
const mobileTitle = computed(() => MOBILE_TITLES[(route.name as string) || ''] || '')
const isMobileRoute = computed(() => route.path.startsWith('/m'))

// 监听 md 断点: 从移动端切到桌面端时关闭抽屉, 避免遮罩残留
let mq: MediaQueryList | null = null

onMounted(() => {
  mq = window.matchMedia('(min-width: 768px)')
  const handler = (e: MediaQueryListEvent) => {
    if (e.matches) drawerVisible.value = false
  }
  mq.addEventListener('change', handler)
})

onUnmounted(() => {
  if (mq) mq.removeEventListener('change', () => {})
})
</script>

<style scoped>
.sidebar,
.sidebar-drawer :deep(.el-drawer__body) {
  background: linear-gradient(180deg, #1e3a8a 0%, #1e40af 100%);
}
.sidebar-drawer :deep(.el-drawer__body) {
  padding: 0;
  display: flex;
  flex-direction: column;
}
.sidebar-menu {
  background: transparent;
}
.sidebar-menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.75);
  margin: 4px 10px;
  border-radius: 8px;
  height: 44px;
}
.sidebar-menu :deep(.el-menu-item:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.sidebar-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: rgba(59, 130, 246, 0.55);
}
</style>
