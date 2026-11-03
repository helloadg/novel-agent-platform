import Taro from '@tarojs/taro'
import type {
  NovelChapter,
  NovelChapterBrief,
  NovelGenreOption,
  NovelProjectBrief,
  NovelProjectDetail,
  NovelStatusVO,
  Result,
} from '@/types/novel'

// 后端地址 — 可通过环境变量 NOVEL_API_BASE 覆盖, 默认本机
// 小程序需要在「开发设置」里关闭合法域名校验, 或把 http://192.168.x.x 加到白名单
// 注意: 不能用裸 process.env, 浏览器/小程序运行时没有 process 全局对象
const ENV_BASE_URL = (globalThis as any).process?.env?.NOVEL_API_BASE as
  | string
  | undefined
const BASE_URL = ENV_BASE_URL || 'http://127.0.0.1:8080/api'

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: any
  header?: Record<string, string>
}

/** 统一的 Taro.request 封装 */
function request<T>(
  path: string,
  options: RequestOptions = {}
): Promise<T> {
  const url = path.startsWith('http') ? path : `${BASE_URL}${path}`
  return new Promise((resolve, reject) => {
    Taro.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header: {
        'Content-Type': 'application/json',
        ...(options.header || {}),
      },
      success: (res) => {
        const body = res.data as Result<T> | T
        if (res.statusCode >= 200 && res.statusCode < 300) {
          // 后端统一 Result<T> 包装
          if (body && typeof body === 'object' && 'code' in body) {
            const wrapped = body as Result<T>
            if (wrapped.code === 0 || wrapped.code === 200) {
              resolve(wrapped.data)
            } else {
              Taro.showToast({
                title: wrapped.message || wrapped.msg || '请求失败',
                icon: 'none',
              })
              reject(new Error(wrapped.message || wrapped.msg || 'request failed'))
            }
          } else {
            resolve(body as T)
          }
        } else {
          Taro.showToast({
            title: `HTTP ${res.statusCode}`,
            icon: 'none',
          })
          reject(new Error(`HTTP ${res.statusCode}`))
        }
      },
      fail: (err) => {
        console.error('[API] network fail', url, err)
        Taro.showToast({
          title: '网络错误',
          icon: 'none',
        })
        reject(err)
      },
    })
  })
}

export const novelApi = {
  genres: () => request<NovelGenreOption[]>('/novel/genres'),

  create: (payload: {
    title: string
    genre: string
    userIdea?: string
    totalChapters?: number
  }) =>
    request<{ projectId: number }>('/novel/project', {
      method: 'POST',
      data: payload,
    }),

  pageProjects: (page = 1, size = 20) =>
    request<NovelProjectBrief[]>(
      `/novel/project/page?page=${page}&size=${size}`
    ),

  detail: (projectId: number) =>
    request<NovelProjectDetail>(`/novel/project/${projectId}`),

  status: (projectId: number) =>
    request<NovelStatusVO>(`/novel/project/${projectId}/status`),

  saveWorld: (
    projectId: number,
    worldview: string,
    characters: string
  ) =>
    request<void>(`/novel/project/${projectId}/world`, {
      method: 'PUT',
      data: { worldview, characters },
    }),

  worldApprove: (projectId: number) =>
    request<void>(`/novel/project/${projectId}/world-approve`, {
      method: 'POST',
    }),

  worldRerun: (projectId: number, hint?: string) =>
    request<void>(`/novel/project/${projectId}/world-rerun`, {
      method: 'POST',
      data: hint ? { hint } : {},
    }),

  volumeApprove: (projectId: number) =>
    request<void>(`/novel/project/${projectId}/volume-approve`, {
      method: 'POST',
    }),

  volumeRerun: (projectId: number, hint?: string) =>
    request<void>(`/novel/project/${projectId}/volume-rerun`, {
      method: 'POST',
      data: hint ? { hint } : {},
    }),

  saveOutline: (projectId: number, outline: string) =>
    request<void>(`/novel/project/${projectId}/outline`, {
      method: 'PUT',
      data: { outline },
    }),

  saveChapterDraft: (chapterId: number, content: string) =>
    request<void>(`/novel/chapter/${chapterId}/draft`, {
      method: 'PUT',
      data: { content },
    }),

  chapter: (chapterId: number) =>
    request<NovelChapter>(`/novel/chapter/${chapterId}`),

  chapterList: (projectId: number) =>
    request<NovelChapterBrief[]>(`/novel/project/${projectId}/chapters`),

  manualApprove: (projectId: number) =>
    request<void>(`/novel/project/${projectId}/manual-approve`, {
      method: 'POST',
    }),

  manualRerun: (projectId: number, hint?: string) =>
    request<void>(`/novel/project/${projectId}/manual-rerun`, {
      method: 'POST',
      data: hint ? { customPrompt: hint } : {},
    }),
}
