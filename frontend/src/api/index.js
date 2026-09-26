import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端的 /api：
 *   - 成功：后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   - 失败：后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

export const bikeApi = {
  list: (params) => http.get('/bikes', { params }),
  stats: () => http.get('/bikes/stats'),
  create: (data) => http.post('/bikes', data),
  update: (id, data) => http.put(`/bikes/${id}`, data),
  scrap: (id, reason) => http.post(`/bikes/${id}/scrap`, { reason })
}

export const stationApi = {
  list: (params) => http.get('/stations', { params }),
  create: (data) => http.post('/stations', data),
  setCapacity: (id, capacity) => http.put(`/stations/${id}/capacity`, { capacity }),
  setStatus: (id, status) => http.post(`/stations/${id}/status`, { status }),
  addPile: (id, pileNo) => http.post(`/stations/${id}/piles`, { pileNo }),
  /** 拖拽调拨：toPileId 指到具体桩位；只给 toStationId 时后端自动挑第一个空桩 */
  transfer: (bikeId, toPileId, toStationId) =>
    http.post('/stations/transfer', { bikeId, toPileId, toStationId })
}

export const rentalApi = {
  list: (params) => http.get('/rentals', { params }),
  active: () => http.get('/rentals/active'),
  users: () => http.get('/rentals/users'),
  borrow: (userId, pileId) => http.post('/rentals/borrow', { userId, pileId }),
  giveBack: (id, pileId) => http.post(`/rentals/${id}/return`, { pileId })
}

export const workOrderApi = {
  list: (params) => http.get('/work-orders', { params }),
  workers: () => http.get('/work-orders/workers'),
  report: (bikeId, faultType, level) => http.post('/work-orders', { bikeId, faultType, level }),
  assign: (id, workerId, planStart, planEnd) =>
    http.post(`/work-orders/${id}/assign`, { workerId, planStart, planEnd }),
  fix: (id, fixNote) => http.post(`/work-orders/${id}/fix`, { fixNote }),
  close: (id) => http.post(`/work-orders/${id}/close`)
}

export const clearanceApi = {
  list: (params) => http.get('/clearances', { params }),
  detail: (id) => http.get(`/clearances/${id}`),
  preview: (sourceStationId, receiverStationIds) =>
    http.post('/clearances/preview', { sourceStationId, receiverStationIds }),
  create: (sourceStationId, receiverStationIds) =>
    http.post('/clearances', { sourceStationId, receiverStationIds }),
  adjust: (id, itemId, payload) =>
    http.post(`/clearances/${id}/items/${itemId}/adjust`, payload),
  execute: (id) => http.post(`/clearances/${id}/execute`),
  cancel: (id) => http.post(`/clearances/${id}/cancel`)
}

export default http
