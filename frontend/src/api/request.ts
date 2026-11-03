import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from '@/types'

const request = axios.create({
  baseURL: '/api',
  timeout: 60_000,
})

// 请求拦截器: 注入演示身份头 (后端 RagController 读取)
request.interceptors.request.use((config) => {
  const userId = localStorage.getItem('demo:userId') || 'demo-user'
  const roles = localStorage.getItem('demo:userRoles') || 'admin'
  config.headers['X-User-Id'] = userId
  config.headers['X-User-Roles'] = roles
  return config
})

// 响应拦截器: 统一解包 Result<T>, 错误统一弹窗
// 后端 Result 的字段是 message, 历史版本用过 msg, 这里两个都兼容
request.interceptors.response.use(
  (res) => {
    const body = res.data as Result<any>
    if (body.code !== 200 && body.code !== 0) {
      const msg = body.message || body.msg || '请求失败'
      ElMessage.error(msg)
      return Promise.reject(new Error(msg))
    }
    return body.data as any
  },
  (err) => {
    const msg = err.response?.data?.message || err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  },
)

export default request
