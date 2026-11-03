import React, { useEffect, useState } from 'react'
import { View, Text, ScrollView, Input, Textarea } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { novelApi } from '@/services/api'
import type { NovelGenreOption } from '@/types/novel'
import styles from './index.module.scss'

function CreatePage() {
  const [title, setTitle] = useState('')
  const [idea, setIdea] = useState('')
  const [chapters, setChapters] = useState(20)
  // 自定义章节数的输入框内容 (空字符串表示未使用自定义, 此时以 chip 选择为准)
  const [customChapters, setCustomChapters] = useState('')
  const [genres, setGenres] = useState<NovelGenreOption[]>([])
  const [genre, setGenre] = useState<string>('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    novelApi.genres().then(setGenres).catch(() => {
      // 后端挂了也给几个默认值
      setGenres([
        { code: 'FANTASY', label: '玄幻奇幻', description: '' },
        { code: 'URBAN', label: '都市', description: '' },
        { code: 'SCI_FI', label: '科幻', description: '' },
        { code: 'HISTORY', label: '历史', description: '' },
        { code: 'ROMANCE', label: '言情', description: '' },
        { code: 'MYSTERY', label: '悬疑推理', description: '' },
      ])
    })
  }, [])

  const disabled = !title.trim() || !genre || submitting

  /** 自定义章节数输入: 合法数字钳位到 1-200, 非法/清空时保持当前值 */
  const onCustomChapters = (value: string) => {
    setCustomChapters(value)
    const n = parseInt(value, 10)
    if (!Number.isNaN(n)) {
      setChapters(Math.max(1, Math.min(200, n)))
    }
  }

  /** 点击常用章节数 chip 时清空自定义输入, 恢复 chip 高亮 */
  const onPickChapters = (n: number) => {
    setChapters(n)
    setCustomChapters('')
  }

  const onSubmit = async () => {
    if (disabled) return
    setSubmitting(true)
    try {
      const res = await novelApi.create({
        title: title.trim(),
        genre,
        userIdea: idea.trim() || undefined,
        totalChapters: chapters,
      })
      Taro.showToast({ title: '已启动生成', icon: 'success' })
      // 跳转到详情页
      setTimeout(() => {
        Taro.redirectTo({ url: `/pages/detail/index?id=${res.projectId}` })
      }, 600)
    } catch (err) {
      console.error('[Create] submit fail', err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <View className={styles.page}>
      <ScrollView scrollY>
        <View className={styles.card}>
          <Text className={styles.fieldLabel}>作品名称</Text>
          <Input
            className={styles.input}
            placeholder="例: 长安十二时辰 · 续"
            value={title}
            maxlength={40}
            onInput={(e) => setTitle(e.detail.value)}
          />
        </View>

        <View className={styles.card}>
          <Text className={styles.fieldLabel}>题材类型</Text>
          <View className={styles.chipGrid}>
            {genres.map((g) => (
              <View
                key={g.code}
                className={`${styles.chip} ${genre === g.code ? styles.active : ''}`}
                onClick={() => setGenre(g.code)}
              >
                <Text>{g.label}</Text>
              </View>
            ))}
          </View>
        </View>

        <View className={styles.card}>
          <Text className={styles.fieldLabel}>章节总数</Text>
          <View className={styles.chipGrid}>
            {[10, 20, 30, 50, 80].map((n) => (
              <View
                key={n}
                className={`${styles.chip} ${chapters === n && !customChapters ? styles.active : ''}`}
                onClick={() => onPickChapters(n)}
              >
                <Text>{n} 章</Text>
              </View>
            ))}
          </View>
          <View className={styles.customRow}>
            <Text className={styles.customLabel}>自定义</Text>
            <Input
              className={styles.customInput}
              type='number'
              value={customChapters}
              placeholder='章节数'
              onInput={(e) => onCustomChapters(e.detail.value)}
            />
            <Text className={styles.customLabel}>章 (1-200, 当前 {chapters} 章)</Text>
          </View>
          <Text className={styles.hint}>建议 20-30 章, 多智能体大概 10-20 分钟完成</Text>
        </View>

        <View className={styles.card}>
          <Text className={styles.fieldLabel}>你的创意 (可选)</Text>
          <Textarea
            className={styles.textarea}
            placeholder="比如: 主角是一个能听到古董低语的修复师, 每件文物背后都有一段被遗忘的往事..."
            value={idea}
            maxlength={500}
            onInput={(e) => setIdea(e.detail.value)}
          />
        </View>
      </ScrollView>

      <View className={styles.footerBar}>
        <View
          className={`${styles.submitBtn} ${disabled ? styles.disabled : ''}`}
          hoverClass={styles.tap}
          onClick={onSubmit}
        >
          <Text>{submitting ? '正在创建...' : '✨ 启动生成'}</Text>
        </View>
      </View>
    </View>
  )
}

export default CreatePage
