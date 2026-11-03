import { useEffect, useState } from 'react'
import { View, Text, ScrollView, Textarea } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import { novelApi } from '@/services/api'
import type { NovelChapter } from '@/types/novel'
import styles from './index.module.scss'

function ChapterPage() {
  const router = useRouter()
  const chapterId = Number(router.params.id)
  const [chapter, setChapter] = useState<NovelChapter | null>(null)
  const [bodyDraft, setBodyDraft] = useState('')
  const [hint, setHint] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!chapterId) {
      Taro.showToast({ title: '参数错误', icon: 'none' })
      return
    }
    novelApi
      .chapter(chapterId)
      .then((c) => {
        setChapter(c)
        setBodyDraft(c.content || '')
        Taro.setNavigationBarTitle({
          title: `第 ${c.chapterNo} 章 · ${c.title || '复审'}`,
        })
      })
      .catch((err) => {
        console.error('[Chapter] load fail', err)
      })
  }, [chapterId])

  if (!chapter) {
    return (
      <View className={styles.page}>
        <View className={styles.empty}>
          <Text>加载中...</Text>
        </View>
      </View>
    )
  }

  // 仅保存正文
  const onSaveDraft = async () => {
    if (saving) return
    setSaving(true)
    try {
      await novelApi.saveChapterDraft(chapterId, bodyDraft)
      Taro.showToast({ title: '已保存正文', icon: 'success' })
    } catch (err) {
      console.error('[Chapter] save draft fail', err)
    } finally {
      setSaving(false)
    }
  }

  // 复审通过, 继续写作
  const onApprove = async () => {
    if (saving) return
    setSaving(true)
    try {
      // 先保存草稿再通过, 避免覆盖人工编辑
      if (bodyDraft !== chapter.content) {
        await novelApi.saveChapterDraft(chapterId, bodyDraft)
      }
      await novelApi.manualApprove(chapter.projectId)
      Taro.showToast({ title: '已放行', icon: 'success' })
      setTimeout(() => Taro.navigateBack(), 600)
    } catch (err) {
      console.error('[Chapter] approve fail', err)
    } finally {
      setSaving(false)
    }
  }

  // 按意见重写
  const onRerun = async () => {
    if (saving) return
    if (!hint.trim()) {
      Taro.showToast({ title: '请填写修改意见', icon: 'none' })
      return
    }
    setSaving(true)
    try {
      // 先保存草稿
      if (bodyDraft !== chapter.content) {
        await novelApi.saveChapterDraft(chapterId, bodyDraft)
      }
      await novelApi.manualRerun(chapter.projectId, hint.trim())
      Taro.showToast({ title: '已提交重写', icon: 'success' })
      setTimeout(() => Taro.navigateBack(), 600)
    } catch (err) {
      console.error('[Chapter] rerun fail', err)
    } finally {
      setSaving(false)
    }
  }

  const reviewDone = chapter.reviewPassed === true

  return (
    <View className={styles.page}>
      <ScrollView scrollY>
        {/* Header */}
        <View className={styles.card}>
          <View className={styles.cardHeader}>
            <Text className={styles.cardTitle}>
              第 {chapter.chapterNo} 章 {chapter.title || '(未命名)'}
            </Text>
            {chapter.reviewPassed && (
              <Text className={`${styles.badge} ${styles.pass}`}>✅ 通过</Text>
            )}
            {chapter.needsReview && !chapter.reviewPassed && (
              <Text className={`${styles.badge} ${styles.pending}`}>⚠️ 待复核</Text>
            )}
            {chapter.reviewPassed === false && !chapter.needsReview && (
              <Text className={`${styles.badge} ${styles.fail}`}>❌ 未通过</Text>
            )}
          </View>
          <Text className={styles.metaText}>
            {chapter.wordCount || 0} 字 · 重写 {chapter.rewriteRounds || 0} 轮
          </Text>
        </View>

        {/* 审核问题 */}
        {chapter.reviewIssues && (
          <View className={styles.card}>
            <Text className={styles.label}>🔍 AI 审核发现的问题</Text>
            <View className={styles.issueBox}>{chapter.reviewIssues}</View>
          </View>
        )}

        {/* 摘要 */}
        {chapter.summary && (
          <View className={styles.card}>
            <Text className={styles.label}>📝 本章摘要</Text>
            <View className={styles.issueBox} style={{ background: '#eff6ff', borderColor: '#bfdbfe', color: '#1e40af' }}>
              {chapter.summary}
            </View>
          </View>
        )}

        {/* 正文编辑 */}
        <View className={styles.card}>
          <Text className={styles.label}>✍️ 章节正文 (可直接修改)</Text>
          <Textarea
            className={styles.bodyEditor}
            value={bodyDraft}
            autoHeight
            onInput={(e) => setBodyDraft(e.detail.value)}
            disabled={reviewDone}
          />
          <Text className={styles.tip}>
            修改后请选择底部按钮: 仅保存正文 / 放行 / 重写
          </Text>
        </View>

        {/* 修改意见 (仅在未通过复审时显示) */}
        {!reviewDone && (
          <View className={styles.card}>
            <Text className={styles.label}>💬 修改意见 (仅在"按意见重写"时使用)</Text>
            <Textarea
              className={styles.hintEditor}
              placeholder="例: 这段比喻太多删掉; 人物对话更口语化; 增加环境描写"
              value={hint}
              maxlength={500}
              onInput={(e) => setHint(e.detail.value)}
            />
            <Text className={styles.tip}>
              意见会传递给写手 Agent, 作为重写本章的指令
            </Text>
          </View>
        )}

        {reviewDone && (
          <View className={styles.empty}>
            <Text>本章已通过审核</Text>
          </View>
        )}
      </ScrollView>

      {/* 底部固定按钮 */}
      {!reviewDone && (
        <View className={styles.btnGroup}>
          <View
            className={`${styles.btn} ${styles.ghost} ${saving ? styles.disabled : ''}`}
            onClick={onSaveDraft}
          >
            <Text>{saving ? '处理中' : '仅保存正文'}</Text>
          </View>
          <View
            className={`${styles.btn} ${styles.warning} ${saving ? styles.disabled : ''}`}
            onClick={onRerun}
          >
            <Text>{saving ? '处理中' : '🔄 按意见重写'}</Text>
          </View>
          <View
            className={`${styles.btn} ${styles.success} ${saving ? styles.disabled : ''}`}
            onClick={onApprove}
          >
            <Text>{saving ? '处理中' : '✅ 放行, 继续'}</Text>
          </View>
        </View>
      )}
    </View>
  )
}

export default ChapterPage
