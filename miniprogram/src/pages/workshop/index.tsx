import React, { useEffect, useState } from 'react'
import { View, Text, ScrollView } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { novelApi } from '@/services/api'
import type { NovelProjectBrief } from '@/types/novel'
import styles from './index.module.scss'

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'running', label: '进行中' },
  { key: 'done', label: '已完成' },
  { key: 'failed', label: '异常' },
]

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  OUTLINING: '大纲生成中',
  OUTLINE_READY: '待审核大纲',
  WRITING: '写作中',
  COMPLETED: '已完成',
  FAILED: '异常',
}

function tagClass(s: string) {
  if (s === 'WRITING' || s === 'OUTLINING') return styles.running
  if (s === 'COMPLETED') return styles.done
  if (s === 'FAILED') return styles.failed
  if (s === 'OUTLINE_READY') return styles.outline
  return styles.draft
}

function progress(p: NovelProjectBrief): number {
  if (p.status === 'COMPLETED') return 100
  if (!p.totalChapters) return 10
  return Math.min(98, Math.round(((p.currentChapter ?? 0) / p.totalChapters) * 100))
}

function filterMatch(status: string, key: string) {
  if (key === 'all') return true
  if (key === 'running') return status === 'WRITING' || status === 'OUTLINING' || status === 'OUTLINE_READY'
  if (key === 'done') return status === 'COMPLETED'
  if (key === 'failed') return status === 'FAILED'
  return true
}

function WorkshopPage() {
  const [items, setItems] = useState<NovelProjectBrief[]>([])
  const [filter, setFilter] = useState('all')
  const [loading, setLoading] = useState(true)

  const load = () => {
    novelApi
      .pageProjects(1, 50)
      .then(setItems)
      .catch((err) => console.error('[Workshop] load fail', err))
      .finally(() => setLoading(false))
  }

  useDidShow(() => load())

  const filtered = items.filter((p) => filterMatch(p.status, filter))

  return (
    <View className={styles.page}>
      <View className={styles.toolbar}>
        {FILTERS.map((f) => (
          <View
            key={f.key}
            className={`${styles.chip} ${filter === f.key ? styles.active : ''}`}
            onClick={() => setFilter(f.key)}
          >
            <Text>{f.label}</Text>
          </View>
        ))}
      </View>

      <ScrollView scrollY className={styles.list} enableFlex>
        {filtered.map((p) => (
          <View
            key={p.id}
            className={styles.card}
            hoverClass={styles.tap}
            onClick={() =>
              Taro.navigateTo({ url: `/pages/detail/index?id=${p.id}` })
            }
          >
            <View className={styles.row}>
              <Text className={styles.title}>{p.title}</Text>
              <Text className={`${styles.tag} ${tagClass(p.status)}`}>
                {STATUS_LABEL[p.status] || p.status}
              </Text>
            </View>
            <View className={styles.meta}>
              <Text>{p.genre}</Text>
              <Text>共 {p.totalChapters} 章</Text>
              <Text>
                {p.createTime ? p.createTime.slice(0, 10) : ''}
              </Text>
            </View>
            <View className={styles.footer}>
              <View className={styles.progress}>
                <View className={styles.progressTrack}>
                  <View
                    className={styles.progressFill}
                    style={{ width: `${progress(p)}%` }}
                  />
                </View>
                <Text className={styles.progressText}>
                  {p.currentChapter ?? 0}/{p.totalChapters}
                </Text>
              </View>
            </View>
          </View>
        ))}

        {!loading && filtered.length === 0 && (
          <View className={styles.empty}>
            <Text>这里空空如也</Text>
          </View>
        )}
      </ScrollView>

      <View
        className={styles.fab}
        hoverClass={styles.tap}
        onClick={() => Taro.navigateTo({ url: '/pages/create/index' })}
      >
        <Text>+</Text>
      </View>
    </View>
  )
}

export default WorkshopPage
