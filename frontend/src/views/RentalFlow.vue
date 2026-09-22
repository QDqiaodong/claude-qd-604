<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>租还与计费</h2>
        <span class="tip">左栏点「借车」把车骑走，右栏点「还车」选桩位结算 —— 一张单只能走一次单向流程</span>
      </div>
      <div class="bar-right">
        <span class="rider-label">借车人</span>
        <el-select v-model="currentUserId" class="rider" @change="onRiderChange">
          <el-option v-for="u in users" :key="u.id" :label="`${u.nickname}（${u.phone}）`" :value="u.id" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <div class="wallet" v-if="currentUser">
      <div class="wallet-main">
        <span class="wallet-name">{{ currentUser.nickname }}</span>
        <span class="wallet-balance">¥ {{ currentUser.balance }}</span>
      </div>
      <div class="wallet-side">
        <el-tag size="small" :type="currentUser.status === 'NORMAL' ? 'success' : 'danger'" effect="dark">
          {{ currentUser.status === 'NORMAL' ? '账号正常' : '账号已冻结' }}
        </el-tag>
        <span>手机号 {{ currentUser.phone }}</span>
      </div>
    </div>

    <div class="cols">
      <!-- 左：可借车辆 -->
      <section class="panel">
        <header class="panel-head">
          <span>可借车辆（在桩）</span>
          <em>{{ pileBikes.length }} 辆</em>
        </header>
        <div class="panel-body">
          <el-empty v-if="pileBikes.length === 0" description="当前没有在桩车辆" :image-size="70" />
          <article
            v-for="bike in pileBikes"
            :key="bike.id"
            class="loan-card"
            :class="{ 'loan-card--focus': bike.code === focusCode }"
          >
            <div class="lc-main">
              <div class="lc-code">{{ bike.code }}</div>
              <div class="lc-where">{{ bike.stationName }} · {{ bike.pileNo }}</div>
              <div class="lc-model">{{ bike.model }} · 累计 {{ bike.totalRides }} 次</div>
            </div>
            <el-button type="primary" size="small" @click="borrow(bike)">借车</el-button>
          </article>
        </div>
      </section>

      <!-- 右：在途订单 -->
      <section class="panel">
        <header class="panel-head">
          <span>骑行中订单</span>
          <em>{{ activeRentals.length }} 单</em>
        </header>
        <div class="panel-body">
          <el-empty v-if="activeRentals.length === 0" description="没有在途订单" :image-size="70" />
          <article v-for="r in activeRentals" :key="r.id" class="ride-card">
            <div class="rc-top">
              <span class="rc-no">{{ r.orderNo }}</span>
              <el-tag size="small" type="primary" effect="plain">骑行中</el-tag>
            </div>
            <div class="rc-line">
              <b>{{ r.bikeCode }}</b> · {{ r.userNickname }}
            </div>
            <div class="rc-line rc-line--sm">
              借自 {{ r.startStationName }} · {{ r.startPileNo }} ，{{ fmt(r.startTime) }}
            </div>
            <div class="rc-act">
              <el-button type="primary" size="small" @click="openReturn(r)">还车并结算</el-button>
            </div>
          </article>
        </div>
      </section>
    </div>

    <!-- 计费规则 + 历史单 -->
    <div class="cols">
      <section class="panel">
        <header class="panel-head">
          <span>计费规则</span>
          <em>后端 BikeDict 里的参数</em>
        </header>
        <div class="rule">
          <div class="rule-row"><span>起步价</span><b>¥ 1.50</b><em>含 60 分钟</em></div>
          <div class="rule-row"><span>超时加收</span><b>¥ 1.00 / 30 分钟</b><em>不足 30 分钟按 30 分钟计</em></div>
          <div class="rule-row"><span>单次封顶</span><b>¥ 20.00</b><em>封顶后不再累加</em></div>
          <div class="rule-row"><span>余额不足</span><b>直接拒绝</b><em>借车看起步价，还车看本次应付</em></div>
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <span>订单流水</span>
          <em>{{ rentals.length }} 单</em>
        </header>
        <div class="panel-body">
          <el-timeline class="tl">
            <el-timeline-item
              v-for="r in rentals"
              :key="r.id"
              :timestamp="fmt(r.startTime)"
              :type="r.status === 'RIDING' ? 'primary' : 'success'"
              placement="top"
            >
              <div class="tl-card" :class="{ 'loan-card--focus': r.orderNo === focusCode }">
                <div class="tl-top">
                  <span class="tl-no">{{ r.orderNo }}</span>
                  <el-tag size="small" :type="r.status === 'RIDING' ? 'primary' : 'success'" effect="plain">
                    {{ r.statusName }}
                  </el-tag>
                </div>
                <div class="tl-line">
                  {{ r.bikeCode }} · {{ r.userNickname }} ｜
                  {{ r.startStationName }}/{{ r.startPileNo }}
                  <template v-if="r.endStationId"> → {{ r.endStationName }}/{{ r.endPileNo }}</template>
                </div>
                <div class="tl-line tl-line--fee">
                  骑行 {{ r.durationMinutes == null ? '进行中' : r.durationMinutes + ' 分钟' }} ·
                  <b>{{ r.amount == null ? '待结算' : '¥ ' + r.amount }}</b>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </section>
    </div>

    <!-- 还车选桩位 -->
    <el-dialog v-model="returnVisible" title="还车并结算" width="520">
      <div v-if="returning" class="ret-info">
        订单 <b>{{ returning.orderNo }}</b> ｜ 车辆 <b>{{ returning.bikeCode }}</b>
        <br />
        借自 {{ returning.startStationName }} · {{ returning.startPileNo }}，{{ fmt(returning.startTime) }}
      </div>
      <el-select v-model="returnPileId" clearable placeholder="选一个空闲桩位" style="width: 100%">
        <el-option
          v-for="p in freePiles"
          :key="p.pileId"
          :label="`${p.stationName} · ${p.pileNo}`"
          :value="p.pileId"
        />
      </el-select>
      <p class="ret-tip">只有「运营中」的站点且空闲的桩位才会出现在这里；站点已满、已停用、桩位被占都会被后端拒绝。</p>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReturn">确认还车</el-button>
      </template>
    </el-dialog>

    <!-- 结算单 -->
    <el-dialog v-model="receiptVisible" title="结算单" width="420" align-center>
      <div v-if="receipt" class="receipt">
        <div class="rc-row"><span>车辆</span><b>{{ receipt.bikeCode }}</b></div>
        <div class="rc-row"><span>还到</span><b>{{ receipt.stationName }} · {{ receipt.pileNo }}</b></div>
        <div class="rc-row"><span>骑行时长</span><b>{{ receipt.durationMinutes }} 分钟</b></div>
        <div class="rc-row rc-row--big"><span>本次费用</span><b>¥ {{ receipt.amount }}</b></div>
        <div class="rc-row"><span>账户余额</span><b>¥ {{ receipt.balanceAfter }}</b></div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bikeApi, rentalApi, stationApi } from '../api'

const route = useRoute()
const users = ref([])
const currentUserId = ref(null)
const bikes = ref([])
const rentals = ref([])
const activeRentals = ref([])
const stations = ref([])

const returnVisible = ref(false)
const returning = ref(null)
const returnPileId = ref(null)

const receiptVisible = ref(false)
const receipt = ref(null)

const focusCode = ref((route.query.code || '').toString().toUpperCase())

const currentUser = computed(() => users.value.find((u) => u.id === currentUserId.value) || null)

const pileBikes = computed(() =>
  bikes.value
    .filter((b) => b.status === 'PILE' && b.pileId)
    .sort((a, b) => (a.code > b.code ? 1 : -1))
)

const freePiles = computed(() => {
  const out = []
  for (const st of stations.value) {
    if (st.status !== 'OPEN') continue
    for (const p of st.piles) {
      if (p.status === 'IDLE' && !p.bikeId) {
        out.push({ pileId: p.id, pileNo: p.pileNo, stationName: st.name })
      }
    }
  }
  return out
})

function fmt(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

async function load() {
  const [userList, bikeList, rentalList, activeList, stationList] = await Promise.all([
    rentalApi.users(),
    bikeApi.list(),
    rentalApi.list(),
    rentalApi.active(),
    stationApi.list()
  ])
  users.value = userList
  bikes.value = bikeList
  rentals.value = rentalList
  activeRentals.value = activeList
  stations.value = stationList
  if (!currentUserId.value && userList.length) {
    currentUserId.value = userList[0].id
  }
}

function onRiderChange() {
  /* 换借车人只是切换显示，不需要重新拉数据 */
}

async function borrow(bike) {
  if (!currentUserId.value) {
    ElMessage.warning('请先选择借车人')
    return
  }
  try {
    await ElMessageBox.confirm(
      `${currentUser.value.nickname} 现在把 ${bike.code}（${bike.stationName} · ${bike.pileNo}）骑走？`,
      '确认借车',
      { type: 'info' }
    )
  } catch (e) {
    return
  }
  const res = await rentalApi.borrow(currentUserId.value, bike.pileId)
  ElMessage.success(res.message)
  await load()
}

function openReturn(r) {
  returning.value = r
  returnPileId.value = null
  returnVisible.value = true
}

async function submitReturn() {
  if (!returnPileId.value) {
    ElMessage.warning('请先选一个还车桩位')
    return
  }
  try {
    const res = await rentalApi.giveBack(returning.value.id, returnPileId.value)
    returnVisible.value = false
    receipt.value = res
    receiptVisible.value = true
    await load()
  } catch (e) {
    await load()
  }
}

watch(
  () => route.query.code,
  (val) => {
    focusCode.value = (val || '').toString().toUpperCase()
    if (!focusCode.value) load()
  }
)

onMounted(load)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.bar h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: #2f3b22;
}

.tip {
  font-size: 12px;
  color: #93a386;
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rider-label {
  font-size: 13px;
  color: #6f7f62;
}

.rider {
  width: 210px;
}

.wallet {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: linear-gradient(120deg, var(--el-color-primary) 0%, #6da03f 100%);
  color: #fff;
  border-radius: 14px;
  padding: 14px 20px;
  box-shadow: 0 4px 14px rgba(85, 139, 47, 0.3);
  flex-wrap: wrap;
}

.wallet-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.wallet-name {
  font-size: 15px;
  letter-spacing: 1px;
}

.wallet-balance {
  font-size: 26px;
  font-weight: 700;
}

.wallet-side {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  opacity: 0.95;
}

.cols {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.panel {
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 2px 10px rgba(15, 30, 10, 0.07);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--el-color-primary-light-9);
  color: #3f5a29;
  font-size: 14px;
  font-weight: 600;
}

.panel-head em {
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: #8b9a7c;
}

.panel-body {
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  max-height: 420px;
  overflow: auto;
}

.loan-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid #e6ece1;
  border-left: 5px solid var(--el-color-primary);
  border-radius: 10px;
  padding: 9px 12px;
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.loan-card:hover {
  transform: translateX(3px);
  box-shadow: 0 4px 12px rgba(15, 30, 10, 0.1);
}

.loan-card--focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.lc-code {
  font-size: 14px;
  font-weight: 700;
  color: #2f3b22;
}

.lc-where {
  font-size: 12px;
  color: #4b6b32;
  margin-top: 2px;
}

.lc-model {
  font-size: 11px;
  color: #a5b09b;
}

.ride-card {
  border: 1px solid #dbe6ee;
  border-left: 5px solid #4a7fb5;
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfdff;
}

.rc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.rc-no {
  font-size: 13px;
  font-weight: 700;
  color: #2f527a;
}

.rc-line {
  font-size: 12px;
  color: #4b6b32;
  margin-top: 5px;
}

.rc-line--sm {
  color: #93a386;
}

.rc-act {
  margin-top: 9px;
}

.rule {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-row {
  display: grid;
  grid-template-columns: 90px 1fr;
  align-items: center;
  gap: 4px 10px;
  font-size: 13px;
  color: #6f7f62;
  border-bottom: 1px dashed #edf1ea;
  padding-bottom: 8px;
}

.rule-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.rule-row b {
  color: var(--el-color-primary-dark-2);
}

.rule-row em {
  grid-column: 2;
  font-style: normal;
  font-size: 11px;
  color: #a5b09b;
}

.tl {
  padding-left: 4px;
  margin: 0;
}

.tl-card {
  background: #fafcf8;
  border-radius: 10px;
  padding: 8px 10px;
  border: 1px solid #eef1ec;
}

.tl-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tl-no {
  font-size: 12px;
  font-weight: 700;
  color: #3f5a29;
}

.tl-line {
  font-size: 12px;
  color: #6f7f62;
  margin-top: 4px;
}

.tl-line--fee b {
  color: var(--el-color-primary-dark-2);
}

.ret-info {
  font-size: 13px;
  color: #4b6b32;
  margin-bottom: 12px;
  line-height: 1.7;
}

.ret-tip {
  font-size: 11px;
  color: #a5b09b;
  line-height: 1.6;
  margin: 10px 0 0;
}

.receipt {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #6f7f62;
}

.rc-row b {
  color: #2f3b22;
}

.rc-row--big {
  padding: 10px 12px;
  background: var(--el-color-primary-light-9);
  border-radius: 10px;
}

.rc-row--big b {
  font-size: 22px;
  color: var(--el-color-primary-dark-2);
}
</style>
