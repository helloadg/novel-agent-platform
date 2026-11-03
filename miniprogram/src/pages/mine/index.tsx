import React from 'react'
import { View, Text, ScrollView } from '@tarojs/components'
import Taro from '@tarojs/taro'
import styles from './index.module.scss'

const MENUS = [
  { icon: '📖', label: '模型配置', path: '' },
  { icon: '🎭', label: '角色-模型绑定', path: '' },
  { icon: '⚙️', label: '后端服务地址', path: '' },
  { icon: '💡', label: '使用帮助', path: '' },
  { icon: 'ℹ️', label: '关于', path: '' },
]

function MinePage() {
  return (
    <ScrollView scrollY className={styles.page}>
      <View className={styles.profile}>
        <View className={styles.avatar}>AI</View>
        <View className={styles.profileInfo}>
          <Text className={styles.profileName}>小说智能体用户</Text>
          <Text className={styles.profileDesc}>多 Agent 协同创作助手</Text>
        </View>
      </View>

      <View className={styles.menuCard}>
        {MENUS.map((m) => (
          <View
            key={m.label}
            className={styles.menuItem}
            hoverClass={styles.tap}
            onClick={() => {
              if (!m.path) {
                Taro.showToast({ title: '功能开发中', icon: 'none' })
              }
            }}
          >
            <View className={styles.menuLeft}>
              <View className={styles.menuIcon}>
                <Text>{m.icon}</Text>
              </View>
              <Text className={styles.menuText}>{m.label}</Text>
            </View>
            <Text className={styles.menuArrow}>›</Text>
          </View>
        ))}
      </View>

      <Text className={styles.version}>novel-agent-platform · v1.0.0</Text>
    </ScrollView>
  )
}

export default MinePage
