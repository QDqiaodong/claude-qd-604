import { createRouter, createWebHistory } from 'vue-router'

/**
 * 顶部四个胶囊对应的四个模块页。图标用 emoji，避免再引图标库。
 */
export const navItems = [
  {
    key: 'bikes',
    path: '/bikes',
    code: '01',
    label: '车辆台账',
    icon: '🚲',
    hint: '按编号直查 BK-1001'
  },
  {
    key: 'stations',
    path: '/stations',
    code: '02',
    label: '站点与桩位',
    icon: '🅿️',
    hint: '按编号直查 S001'
  },
  {
    key: 'rentals',
    path: '/rentals',
    code: '03',
    label: '租还与计费',
    icon: '🧾',
    hint: '按订单号直查 R2026...'
  },
  {
    key: 'work-orders',
    path: '/work-orders',
    code: '04',
    label: '运维工单',
    icon: '🛠️',
    hint: '按工单号直查 WO2026...'
  },
  {
    key: 'clearances',
    path: '/clearances',
    code: '05',
    label: '应急清站',
    icon: '🚚',
    hint: '按清站单号直查 CS2026...'
  }
]

const routes = [
  { path: '/', redirect: '/bikes' },
  { path: '/bikes', name: 'bikes', component: () => import('../views/BikeLedger.vue') },
  { path: '/stations', name: 'stations', component: () => import('../views/StationBoard.vue') },
  { path: '/rentals', name: 'rentals', component: () => import('../views/RentalFlow.vue') },
  { path: '/work-orders', name: 'work-orders', component: () => import('../views/WorkOrderFlow.vue') },
  { path: '/clearances', name: 'clearances', component: () => import('../views/ClearanceFlow.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/bikes' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
