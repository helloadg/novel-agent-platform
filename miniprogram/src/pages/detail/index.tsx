import React, { useEffect, useRef, useState } from 'react'
import { View, Text, ScrollView, Textarea } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { novelApi } from '@/services/api'
import type {
  NovelProjectDetail,
  NovelStatusVO,
  NovelAgentLog,
} from '@/types/novel'
import styles from './index.module.scss'

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  OUTLINING: '大纲生成中',
  OUTLINE_READY: '待审核大纲',
  WRITING: '写作中',
  COMPLETED: '已完成',
  FAILED: '异常',
}

function statusClass(s: string) {
  if (s === 'WRITING' || s === 'OUTLINING') return styles.running
  if (s === 'COMPLETED') return styles.done
  if (s === 'FAILED') return styles.failed
  if (s === 'OUTLINE_READY') return styles.outline
  return ''
}

// 章节级人工复审中断点的阶段关键词
const MANUAL_STAGE_KEYWORDS = [
  '等待人工干预',
  '重写超限',
  '标记待人工',
  '待人工复核',
  '人工放行',
  '人工触发',
  '人工干预',
  '人工复审',
  '等待人工',
]

function DetailPage() {
  const router = useRouter()
  const projectId = Number(router.params.id)
  const [detail, setDetail] = useState<NovelProjectDetail | null>(null)
  const [status, setStatus] = useState<NovelStatusVO | null>(null)
  const [logs, setLogs] = useState<NovelAgentLog[]>([])
  const [loading, setLoading] = useState(true)

  // 复审状态
  const [worldHint, setWorldHint] = useState('')
  const [worldSubmitting, setWorldSubmitting] = useState(false)
  const [volumeHint, setVolumeHint] = useState('')
  const [volumeSubmitting, setVolumeSubmitting] = useState(false)

  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)

  const loadDetail = async () => {
    try {
      const d = await novelApi.detail(projectId)
      setDetail(d)
      Taro.setNavigationBarTitle({ title: d.title || '作品详情' })
    } catch (err) {
      console.error('[Detail] load detail fail', err)
    } finally {
      setLoading(false)
    }
  }

  const pollStatus = async () => {
    try {
      const s = await novelApi.status(projectId)
      setStatus(s)
      // 进度时间线保留最新 30 条
      setLogs((prev) => {
        const merged = [...s.logs, ...prev]
        const seen = new Set<number>()
        const dedup = merged.filter((l) => {
          if (seen.has(l.id)) return false
          seen.add(l.id)
          return true
        })
        return dedup.slice(0, 30)
      })
    } catch (err) {
      console.error('[Detail] poll fail', err)
    }
  }

  useEffect(() => {
    if (!projectId) {
      Taro.showToast({ title: '参数错误', icon: 'none' })
      return
    }
    loadDetail()
    pollStatus()
    pollTimer.current = setInterval(pollStatus, 3000)
    return () => {
      if (pollTimer.current) clearInterval(pollTimer.current)
    }
  }, [projectId])

  const isRunning =
    status?.status === 'WRITING' || status?.status === 'OUTLINING'

  // ---- 世界观复审 ----
  const onWorldApprove = async () => {
    if (worldSubmitting) return
    setWorldSubmitting(true)
    try {
      await novelApi.worldApprove(projectId)
      Taro.showToast({ title: '已放行', icon: 'success' })
      loadDetail()
      pollStatus()
    } catch (err) {
      console.error('[Detail] world approve fail', err)
    } finally {
      setWorldSubmitting(false)
    }
  }
  const onWorldRerun = async () => {
    if (worldSubmitting) return
    setWorldSubmitting(true)
    try {
      await novelApi.worldRerun(projectId, worldHint.trim() || undefined)
      Taro.showToast({ title: '已驳回重生成', icon: 'success' })
      setWorldHint('')
      loadDetail()
      pollStatus()
    } catch (err) {
      console.error('[Detail] world rerun fail', err)
    } finally {
      setWorldSubmitting(false)
    }
  }

  // ---- 分卷大纲复审 ----
  const onVolumeApprove = async () => {
    if (volumeSubmitting) return
    setVolumeSubmitting(true)
    try {
      await novelApi.volumeApprove(projectId)
      Taro.showToast({ title: '已放行', icon: 'success' })
      loadDetail()
      pollStatus()
    } catch (err) {
      console.error('[Detail] volume approve fail', err)
    } finally {
      setVolumeSubmitting(false)
    }
  }
  const onVolumeRerun = async () => {
    if (volumeSubmitting) return
    setVolumeSubmitting(true)
    try {
      await novelApi.volumeRerun(projectId, volumeHint.trim() || undefined)
      Taro.showToast({ title: '已驳回重生成', icon: 'success' })
      setVolumeHint('')
      loadDetail()
      pollStatus()
    } catch (err) {
      console.error('[Detail] volume rerun fail', err)
    } finally {
      setVolumeSubmitting(false)
    }
  }

  // ---- 章节跳转 ----
  const openChapter = (chapterId: number) => {
    Taro.navigateTo({ url: `/pages/chapter/index?id=${chapterId}&projectId=${projectId}` })
  }

  if (loading || !detail) {
    return (
      <View className={styles.page}>
        <View className={styles.emptyBox}>
          <Text>加载中...</Text>
        </View>
      </View>
    )
  }

  // 后端 stage 是中文文案, 用关键词匹配人工复审中断点
  const stage = status?.stage || ''
  const showWorldReview =
    status?.status === 'OUTLINING' &&
    stage.includes('世界观') &&
    (stage.includes('人工复审') || stage.includes('等待人工'))
  const showVolumeReview =
    status?.status === 'WRITING' &&
    stage.includes('卷大纲') &&
    stage.includes('人工复审')
  // 章节级人工复审 (排除世界观/卷大纲复审)
  const awaitingChapterManual =
    !!stage &&
    !stage.includes('世界观') &&
    !stage.includes('卷大纲') &&
    MANUAL_STAGE_KEYWORDS.some((k) => stage.includes(k))
  const manualChapter = awaitingChapterManual
    ? detail.chapters.find((c) => c.chapterNo === status?.currentChapter)
    : undefined
  // 处于人工复审中断点时后台并没有在写作
  const isPaused = showWorldReview || showVolumeReview || awaitingChapterManual
  const isActive = isRunning && !isPaused

  return (
    <ScrollView scrollY className={styles.page}>
      {/* Header */}
      <Text className={styles.title}>{detail.title}</Text>
      <View className={styles.metaRow}>
        <Text className={`${styles.statusChip} ${statusClass(detail.status)}`}>
          {STATUS_LABEL[detail.status] || detail.status}
          {isActive ? ' · 运行中' : isPaused ? ' · 等待人工' : ''}
        </Text>
        <Text className={styles.metaText}>{detail.genreLabel}</Text>
        <Text className={styles.metaText}>
          {status?.currentChapter ?? 0} / {detail.totalChapters} 章
        </Text>
      </View>

      {/* 章节人工复审提示: 写作已暂停, 引导用户进入复审 */}
      {manualChapter && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>
              🔍 第 {manualChapter.chapterNo} 章人工复审
            </Text>
          </View>
          <Text className={styles.bannerText}>
            本章 AI 审核已完成, 写作已暂停。复审放行或按意见重写后, 才会继续后续章节。
          </Text>
          <View className={styles.btnRow}>
            <View
              className={`${styles.btn} ${styles.primary}`}
              onClick={() => openChapter(manualChapter.id)}
            >
              <Text>进入章节复审</Text>
            </View>
          </View>
        </View>
      )}

      {/* 进度时间线 */}
      {logs.length > 0 && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>🧠 进度日志</Text>
            {isActive && (
              <Text className={styles.metaText}>自动刷新中</Text>
            )}
            {isPaused && (
              <Text className={styles.pausedText}>等待人工处理</Text>
            )}
          </View>
          <ScrollView scrollY className={styles.timeline}>
            {logs.map((log) => (
              <View key={log.id} className={styles.logItem}>
                <View className={styles.logDot} />
                <View className={styles.logBody}>
                  <Text className={styles.logAgent}>
                    {log.agentName}
                    {log.chapterNo ? ` · 第 ${log.chapterNo} 章` : ''}
                  </Text>
                  {log.outputText && (
                    <Text className={styles.logMsg}>{log.outputText}</Text>
                  )}
                  <Text className={styles.logTime}>
                    {log.createTime.slice(11, 19)}
                  </Text>
                </View>
              </View>
            ))}
          </ScrollView>
        </View>
      )}

      {/* 世界观 & 人物 */}
      {detail.worldview && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>🌍 世界观</Text>
          </View>
          <View className={styles.contentBox}>{detail.worldview}</View>
        </View>
      )}
      {detail.characters && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>👥 人物设定</Text>
          </View>
          <View className={styles.contentBox}>{detail.characters}</View>
        </View>
      )}

      {/* 世界观复审按钮 */}
      {showWorldReview && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>🔍 世界观人工复审</Text>
          </View>
          <Textarea
            className={styles.editor}
            placeholder="填修改意见 (可选), 写手将据此调整世界观..."
            value={worldHint}
            maxlength={300}
            onInput={(e) => setWorldHint(e.detail.value)}
          />
          <View className={styles.btnRow}>
            <View
              className={`${styles.btn} ${styles.success} ${worldSubmitting ? styles.disabled : ''}`}
              onClick={onWorldApprove}
            >
              <Text>{worldSubmitting ? '处理中...' : '✅ 复审通过, 生成大纲'}</Text>
            </View>
            <View
              className={`${styles.btn} ${styles.warning} ${worldSubmitting ? styles.disabled : ''}`}
              onClick={onWorldRerun}
            >
              <Text>{worldSubmitting ? '处理中...' : '🔄 按意见重生成'}</Text>
            </View>
          </View>
        </View>
      )}

      {/* 大纲 */}
      {detail.outline && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>📋 分卷大纲</Text>
          </View>
          <View className={styles.contentBox}>{detail.outline}</View>
        </View>
      )}

      {/* 分卷大纲复审按钮 */}
      {showVolumeReview && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>🔍 分卷大纲人工复审</Text>
          </View>
          <Textarea
            className={styles.editor}
            placeholder="填修改意见 (可选), 大纲师将据此调整..."
            value={volumeHint}
            maxlength={300}
            onInput={(e) => setVolumeHint(e.detail.value)}
          />
          <View className={styles.btnRow}>
            <View
              className={`${styles.btn} ${styles.success} ${volumeSubmitting ? styles.disabled : ''}`}
              onClick={onVolumeApprove}
            >
              <Text>{volumeSubmitting ? '处理中...' : '✅ 放行, 继续写作'}</Text>
            </View>
            <View
              className={`${styles.btn} ${styles.warning} ${volumeSubmitting ? styles.disabled : ''}`}
              onClick={onVolumeRerun}
            >
              <Text>{volumeSubmitting ? '处理中...' : '🔄 驳回重生成'}</Text>
            </View>
          </View>
        </View>
      )}

      {/* 章节列表 */}
      {detail.chapters.length > 0 && (
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>📖 章节 ({detail.chapters.length})</Text>
          </View>
          {detail.chapters.map((c) => (
            <View
              key={c.id}
              className={styles.chapterItem}
              hoverClass={styles.tap}
              onClick={() => openChapter(c.id)}
            >
              <View className={styles.chapterInfo}>
                <Text className={styles.chapterTitle}>
                  第 {c.chapterNo} 章 {c.title || '(生成中...)'}
                </Text>
                <Text className={styles.chapterMeta}>
                  {c.summary ? c.summary.slice(0, 30) : '—'}
                  {' · '}
                  {c.wordCount || 0} 字 · 重写 {c.rewriteRounds || 0} 轮
                </Text>
              </View>
              {manualChapter?.id === c.id ? (
                <Text className={`${styles.badge} ${styles.pending}`}>待复核</Text>
              ) : (
                <>
                  {c.reviewPassed && (
                    <Text className={`${styles.badge} ${styles.pass}`}>通过</Text>
                  )}
                  {c.needsReview && !c.reviewPassed && (
                    <Text className={`${styles.badge} ${styles.pending}`}>待复核</Text>
                  )}
                  {c.reviewPassed === false && !c.needsReview && (
                    <Text className={`${styles.badge} ${styles.fail}`}>未通过</Text>
                  )}
                </>
              )}
            </View>
          ))}
        </View>
      )}

      {detail.status === 'COMPLETED' && (
        <View className={styles.emptyBox}>
          <Text>🎉 整本小说已完成创作</Text>
        </View>
      )}
    </ScrollView>
  )
}

export default DetailPage
