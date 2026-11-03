import { createRouter, createWebHashHistory } from 'vue-router'
import NovelView from '@/views/NovelView.vue'
import ModelConfigView from '@/views/ModelConfigView.vue'
import RoleBindingView from '@/views/RoleBindingView.vue'
import MobileShell from '@/views/m/MobileShell.vue'
import MobileHome from '@/views/m/HomeView.vue'
import MobileWorkshop from '@/views/m/WorkshopView.vue'
import MobileMine from '@/views/m/MineView.vue'
import MobileCreate from '@/views/m/CreateView.vue'
import MobileDetail from '@/views/m/DetailView.vue'
import MobileChapter from '@/views/m/ChapterView.vue'

/** 是否手机端访问: UA 命中移动设备, 或视口宽度小于 768px */
function isMobileClient(): boolean {
  if (typeof window === 'undefined') return false
  const mobileUa = /Android|iPhone|iPad|iPod|Mobile|HarmonyOS/i.test(navigator.userAgent)
  return mobileUa || window.matchMedia('(max-width: 767px)').matches
}

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/novel' },
    { path: '/novel', name: 'novel', component: NovelView },
    { path: '/models', name: 'models', component: ModelConfigView },
    { path: '/bindings', name: 'bindings', component: RoleBindingView },
    // 移动端独立页面: 与桌面端 UI 完全隔离, 只保留小程序同款 6 个功能
    {
      path: '/m',
      component: MobileShell,
      children: [
        { path: '', redirect: '/m/home' },
        { path: 'home', name: 'm-home', component: MobileHome, meta: { title: '小说智能体' } },
        { path: 'workshop', name: 'm-workshop', component: MobileWorkshop, meta: { title: '小说工坊' } },
        { path: 'mine', name: 'm-mine', component: MobileMine, meta: { title: '我的' } },
        { path: 'create', name: 'm-create', component: MobileCreate, meta: { title: '创建作品' } },
        { path: 'detail/:id', name: 'm-detail', component: MobileDetail, meta: { title: '作品详情' } },
        { path: 'chapter/:id', name: 'm-chapter', component: MobileChapter, meta: { title: '章节复审' } },
      ],
    },
  ],
})

// 手机访问桌面端入口时自动进入移动端页面
router.beforeEach((to) => {
  if (to.path === '/novel' && isMobileClient()) {
    return '/m/home'
  }
  return true
})

export default router
