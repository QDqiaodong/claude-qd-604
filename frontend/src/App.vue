<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">驿</span>
        <div class="brand-text">
          <strong>单车驿站</strong>
          <em>共享单车 · 运营管理台</em>
        </div>
      </div>

      <!-- 导航形态：一排圆角胶囊切模块（不是侧边菜单，也不是面包屑） -->
      <nav class="pillbar">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="pill"
          :class="{ 'pill--on': activeKey === item.key }"
          type="button"
          @click="go(item)"
        >
          <i class="pill-code">{{ item.code }}</i>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <!-- 右边一个搜索框：按编号直查，自动判断是哪类编号并跳到对应模块 -->
      <div class="finder">
        <el-input
          v-model="keyword"
          class="finder-input"
          placeholder="输入编号直查：BK-1001 / S001 / R2026… / WO2026… / CS2026…"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button type="primary" @click="doSearch">直查</el-button>
          </template>
        </el-input>
      </div>
    </header>

    <!-- 常驻概览条：不管切到哪个模块都看得见全局数字 -->
    <section class="overview">
      <div v-for="card in overviewCards" :key="card.label" class="ov-card">
        <span class="ov-num">{{ card.value }}</span>
        <span class="ov-label">{{ card.label }}</span>
      </div>
    </section>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import router, { navItems } from './router'
import { bikeApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没有装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const keyword = ref('')
const stats = ref(null)

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'bikes'
})

const overviewCards = computed(() => {
  const s = stats.value
  if (!s) {
    return [
      { label: '车辆总数', value: '—' },
      { label: '在桩', value: '—' },
      { label: '骑行中', value: '—' },
      { label: '维修中', value: '—' },
      { label: '报废', value: '—' },
      { label: '站点 / 运营中', value: '—' },
      { label: '桩位总数', value: '—' },
      { label: '骑行中订单', value: '—' },
      { label: '清站进行中 / 受阻', value: '—' }
    ]
  }
  return [
    { label: '车辆总数', value: s.bikeTotal },
    { label: '在桩', value: s.bikePile },
    { label: '骑行中', value: s.bikeRiding },
    { label: '维修中', value: s.bikeRepair },
    { label: '报废', value: s.bikeScrapped },
    { label: '站点 / 运营中', value: `${s.stationTotal} / ${s.stationOpen}` },
    { label: '桩位总数', value: s.pileTotal },
    { label: '骑行中订单', value: s.rentalRiding },
    { label: '清站进行中 / 受阻', value: `${s.clearanceActive} / ${s.clearanceBlocked}` }
  ]
})

function go(item) {
  keyword.value = ''
  nav.push({ path: item.path })
}

/** 按编号直查：识别前缀 → 跳到对应模块并把编号带过去高亮 */
function doSearch() {
  const code = keyword.value.trim()
  if (!code) {
    ElMessage.warning('请先输入要直查的编号')
    return
  }
  const upper = code.toUpperCase()
  let target = null
  if (/^BK-\d+$/.test(upper)) {
    target = '/bikes'
  } else if (/^S\d+$/.test(upper)) {
    target = '/stations'
  } else if (/^WO\d+$/.test(upper)) {
    target = '/work-orders'
  } else if (/^R\d+$/.test(upper)) {
    target = '/rentals'
  } else if (/^CS\d+$/.test(upper)) {
    target = '/clearances'
  }
  if (!target) {
    ElMessage.warning('认不出这个编号，请用 BK-1001（车辆）/ S001（站点）/ R…（订单）/ WO…（工单）/ CS…（清站单）的形式')
    return
  }
  nav.push({ path: target, query: { code: upper } })
}

async function loadStats() {
  try {
    stats.value = await bikeApi.stats()
  } catch (e) {
    /* 概览条失败不打断页面 */
  }
}

onMounted(loadStats)
</script>

<style scoped>
.shell {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 14px 26px;
  background: #ffffff;
  border-bottom: 3px solid var(--el-color-primary);
  box-shadow: 0 2px 10px rgba(85, 139, 47, 0.12);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.brand-mark {
  width: 38px;
  height: 38px;
  border-radius: 12px 12px 12px 4px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-text strong {
  font-size: 17px;
  letter-spacing: 2px;
  color: #2f3b22;
}

.brand-text em {
  font-style: normal;
  font-size: 11px;
  color: #8b9a7c;
}

/* 胶囊切换 */
.pillbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px;
  background: var(--el-color-primary-light-9);
  border-radius: 999px;
  flex: 0 0 auto;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #4b6b32;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
}

.pill:hover {
  background: var(--el-color-primary-light-8);
}

.pill--on {
  background: var(--el-color-primary);
  color: #fff;
  box-shadow: 0 3px 10px rgba(85, 139, 47, 0.35);
}

.pill-code {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  opacity: 0.7;
}

.finder {
  margin-left: auto;
  width: 380px;
  flex: 0 1 380px;
}

.finder-input :deep(.el-input__wrapper) {
  border-radius: 999px 0 0 999px;
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5) inset;
}

.finder-input :deep(.el-input-group__append) {
  border-radius: 0 999px 999px 0;
  overflow: hidden;
}

.overview {
  display: flex;
  gap: 12px;
  padding: 14px 26px 0;
  flex-wrap: wrap;
}

.ov-card {
  flex: 1 1 120px;
  background: #fff;
  border-left: 4px solid var(--el-color-primary);
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  box-shadow: 0 1px 4px rgba(15, 30, 10, 0.06);
}

.ov-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-primary-dark-2);
  line-height: 1.1;
}

.ov-label {
  font-size: 12px;
  color: #8b9a7c;
}

.stage {
  flex: 1;
  padding: 16px 26px 40px;
}
</style>
