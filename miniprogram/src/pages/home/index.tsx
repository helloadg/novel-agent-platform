import React, { useEffect, useState } from 'react'
import { View, Text, ScrollView } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { novelApi } from '@/services/api'
import type { NovelProjectBrief } from '@/types/novel'
import styles from './index.module.scss'

function statusLabel(s: string) {
  switch (s) {
    case 'DRAFT': return '草稿'
    case 'OUTLINING': return '大纲生成中'
    case 'OUTLINE_READY': return '待审核大纲'
    case 'WRITING': return '写作中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '异常'
    default: return s
  }
}

function statusClass(s: string) {
  if (s === 'WRITING' || s === 'OUTLINING') return styles.statusRunning
  if (s === 'COMPLETED') return styles.statusDone
  if (s === 'FAILED') return styles.statusFailed
  if (s === 'OUTLINE_READY') return styles.statusOutline
  return ''
}

function progress(p: NovelProjectBrief): number {
  if (p.status === 'COMPLETED') return 100
  if (!p.totalChapters) return 10
  const done = p.currentChapter ?? 0
  return Math.min(98, Math.round((done / p.totalChapters) * 100))
}

function HomePage() {
  const [items, setItems] = useState<NovelProjectBrief[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    novelApi
      .pageProjects(1, 10)
      .then((list) => {
        setItems(list)
      })
      .catch((err) => console.error('[Home] load fail', err))
      .finally(() => setLoading(false))
  }, [])

  const running = items.filter(
    (p) => p.status === 'WRITING' || p.status === 'OUTLINING' || p.status === 'OUTLINE_READY'
  )
  const done = items.filter((p) => p.status === 'COMPLETED').slice(0, 3)

  const goDetail = (id: number) => {
    Taro.navigateTo({ url: `/pages/detail/index?id=${id}` })
  }

  return (
    <ScrollView scrollY className={styles.page}>
      <View className={styles.hero}>
        <Text className={styles.heroTitle}>创作你的下一本小说</Text>
        <Text className={styles.heroSubtitle}>多智能体协同 · 世界观 → 大纲 → 逐章写作</Text>
      </View>

      <View
        className={styles.ctaCard}
        hoverClass={styles.tap}
        onClick={() => Taro.navigateTo({ url: '/pages/create/index' })}
      >
        <View className={styles.ctaTextWrap}>
          <Text className={styles.ctaTitle}>✨ 启动新作品</Text>
          <Text className={styles.ctaDesc}>填写题材与想法, 后端 Agent 自动开写</Text>
        </View>
        <Text className={styles.ctaArrow}>›</Text>
      </View>

      {running.length > 0 && (
        <View>
          <View className={styles.sectionHeader}>
            <Text className={styles.sectionTitle}>🔥 进行中</Text>
          </View>
          {running.map((p) => (
            <View
              key={p.id}
              className={styles.card}
              hoverClass={styles.tap}
              onClick={() => goDetail(p.id)}
            >
              <View className={styles.cardTop}>
                <Text className={styles.cardTitle}>{p.title}</Text>
                <Text className={`${styles.statusTag} ${statusClass(p.status)}`}>
                  {statusLabel(p.status)}
                </Text>
              </View>
              <View className={styles.cardMeta}>
                <Text>{p.genre}</Text>
                <Text>{p.currentChapter ?? 0} / {p.totalChapters} 章</Text>
              </View>
              <View className={styles.progressBar}>
                <View
                  className={styles.progressFill}
                  style={{ width: `${progress(p)}%` }}
                />
              </View>
            </View>
          ))}
        </View>
      )}

      {done.length > 0 && (
        <View>
          <View className={styles.sectionHeader}>
            <Text className={styles.sectionTitle}>📚 近期完成</Text>
            <Text
              className={styles.sectionMore}
              onClick={() => Taro.switchTab({ url: '/pages/workshop/index' })}
            >
              全部作品 ›
            </Text>
          </View>
          {done.map((p) => (
            <View
              key={p.id}
              className={styles.card}
              hoverClass={styles.tap}
              onClick={() => goDetail(p.id)}
            >
              <View className={styles.cardTop}>
                <Text className={styles.cardTitle}>{p.title}</Text>
                <Text className={`${styles.statusTag} ${statusClass(p.status)}`}>
                  {statusLabel(p.status)}
                </Text>
              </View>
              <View className={styles.cardMeta}>
                <Text>{p.genre}</Text>
                <Text>共 {p.totalChapters} 章</Text>
              </View>
            </View>
          ))}
        </View>
      )}

      {!loading && items.length === 0 && (
        <View className={styles.empty}>
          <Text>还没有作品, 点上面按钮创建第一本 ✨</Text>
        </View>
      )}
    </ScrollView>
  )
}

export default HomePage
