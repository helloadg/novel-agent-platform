<template>
  <div class="h-full overflow-y-auto px-4" :style="{ paddingTop: 'env(safe-area-inset-top, 0px)' }">
    <!-- 个人卡 -->
    <div class="mt-6 mb-4 p-6 rounded-lg flex items-center gap-4 text-white bg-gradient-to-br from-[#3b82f6] to-[#1e3a8a]">
      <div class="w-[60px] h-[60px] shrink-0 rounded-full bg-white/20 flex items-center justify-center text-[28px] font-semibold">
        AI
      </div>
      <div class="flex-1 min-w-0">
        <div class="text-[18px] font-semibold mb-1">小说智能体用户</div>
        <div class="text-[12px] text-white/85">多 Agent 协同创作助手</div>
      </div>
    </div>

    <!-- 菜单 -->
    <div class="bg-white rounded-lg px-4 shadow-[0_1px_6px_rgba(0,0,0,0.08)] mb-3">
      <button
        v-for="menu in MENUS"
        :key="menu.label"
        type="button"
        class="w-full flex items-center justify-between py-3.5 border-b border-[#f2f3f5] last:border-b-0 active:opacity-60"
        @click="onMenu"
      >
        <div class="flex items-center gap-3">
          <div class="w-7 h-7 rounded-md bg-[#eff6ff] text-[16px] flex items-center justify-center">
            {{ menu.icon }}
          </div>
          <span class="text-[14px] text-[#1d2129]">{{ menu.label }}</span>
        </div>
        <span class="text-[18px] text-[#86909c]">›</span>
      </button>
    </div>

    <div class="text-center text-[12px] text-[#86909c] mt-8">
      novel-agent-platform · v1.0.0
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'

const MENUS = [
  { icon: '📖', label: '模型配置' },
  { icon: '🎭', label: '角色-模型绑定' },
  { icon: '⚙️', label: '后端服务地址' },
  { icon: '💡', label: '使用帮助' },
  { icon: 'ℹ️', label: '关于' },
]

function onMenu() {
  ElMessage.info('功能开发中')
}
</script>
