<template>
  <div class="page">
    <!-- ================= 列表 ================= -->
    <template v-if="mode === 'list'">
      <div class="bar">
        <div class="bar-left">
          <h2>应急清站</h2>
          <span class="tip">
            停用仍有车的站点前先建清站单：故障车优先送有在岗维修工的站点、普通车就近；
            接收能力不足会明确列出「暂无处安置」的车，<b>绝不伪造完整方案、绝不提前标空</b>
          </span>
        </div>
        <div class="bar-right">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" @click="openCreate">+ 新建清站单</el-button>
        </div>
      </div>

      <el-table :data="orders" v-loading="loading" stripe class="tbl">
        <el-table-column label="清站单号" min-width="170">
          <template #default="{ row }">
            <el-link type="primary" @click="openDetail(row.id)">{{ row.orderNo }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="源站" min-width="150">
          <template #default="{ row }">
            {{ row.sourceStationCode }} · {{ row.sourceStationName }}
            <el-tag v-if="row.sourceStationStatus === 'CLOSED'" size="small" type="info" effect="plain">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="dark" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="迁出进度" width="150">
          <template #default="{ row }">
            <el-progress :percentage="row.totalCount ? Math.round((row.movedCount / row.totalCount) * 100) : 0" :stroke-width="10" />
            <span class="prog-text">{{ row.movedCount }}/{{ row.totalCount }}<em v-if="row.blockedCount"> · 受阻{{ row.blockedCount }}</em></span>
          </template>
        </el-table-column>
        <el-table-column label="暂无处安置" width="100">
          <template #default="{ row }">
            <el-badge v-if="row.unplacedCount" :value="row.unplacedCount" type="warning" />
            <span v-else class="muted">0</span>
          </template>
        </el-table-column>
        <el-table-column label="未完成原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="blocker">{{ row.finishBlocker || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ================= 创建向导 ================= -->
    <template v-if="mode === 'create'">
      <div class="bar">
        <div class="bar-left">
          <el-button text @click="backList">← 返回列表</el-button>
          <h2>新建清站单</h2>
        </div>
      </div>

      <el-steps :active="stepActive" finish-status="success" align-center class="steps">
        <el-step title="选择源站" />
        <el-step title="选择接收站" />
        <el-step title="确认迁移方案" />
      </el-steps>

      <!-- 第1步：源站 -->
      <div v-if="step === 1" class="panel">
        <p class="panel-tip">只有<b>运营中且仍停有在桩车辆</b>的站点需要清站。已停用或已清空的站点不必建单。</p>
        <el-radio-group v-model="createForm.sourceStationId" @change="onPickSource">
          <div class="source-grid">
            <el-radio-button
              v-for="s in sourceChoices"
              :key="s.id"
              :value="s.id"
              class="source-radio"
              :disabled="s.bikeCount === 0"
            >
              <div class="source-card-inner">
                <strong>{{ s.code }} · {{ s.name }}</strong>
                <span>在桩 {{ s.bikeCount }} 辆 · 空桩 {{ s.freePileCount }} · 容量 {{ s.capacity }}</span>
              </div>
            </el-radio-button>
          </div>
        </el-radio-group>
        <div class="panel-foot">
          <el-button @click="backList">取消</el-button>
          <el-button type="primary" :disabled="!createForm.sourceStationId" @click="goStep2">下一步：看接收站实时能力</el-button>
        </div>
      </div>

      <!-- 第2步：接收站 -->
      <div v-if="step === 2" class="panel">
        <el-alert type="info" :closable="false" show-icon class="step-alert"
          title="下面是此刻各接收站的实时剩余容量、可用桩位和维修能力。容量与桩位在执行时还会再实时校验一次。" />
        <el-table :data="receivers" v-loading="loadingReceivers" @selection-change="onSelectReceivers"
          @row-click="(row) => toggleReceiver(row)" ref="receiverTable" class="tbl">
          <el-table-column type="selection" width="46" reserve-selection />
          <el-table-column label="站点" min-width="170">
            <template #default="{ row }">
              <strong>{{ row.stationCode }} · {{ row.stationName }}</strong>
              <span class="muted">（{{ row.district }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="剩余容量" width="100">
            <template #default="{ row }">
              <span :class="{ 'cap-zero': row.remainCapacity === 0 }">{{ row.remainCapacity }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可用空桩" width="100">
            <template #default="{ row }">
              <span :class="{ 'cap-zero': row.freePileCount === 0 }">{{ row.freePileCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="故障车接收" min-width="240">
            <template #default="{ row }">
              <el-tag :type="row.faultCapable ? 'success' : 'info'" size="small" effect="plain">
                {{ row.faultCapable ? '可接故障车' : '不能接故障车' }}
              </el-tag>
              <span class="worker-sum">{{ row.workerSummary }}</span>
            </template>
          </el-table-column>
          <el-table-column label="距离" width="110">
            <template #default="{ row }">{{ row.distanceKm == null ? '—' : row.distanceKm + ' km' }}</template>
          </el-table-column>
        </el-table>
        <div class="panel-foot">
          <el-button @click="step = 1">上一步</el-button>
          <el-button type="primary" :disabled="selectedReceivers.length === 0" @click="goStep3">
            下一步：生成迁移方案（已选 {{ selectedReceivers.length }} 个站）
          </el-button>
        </div>
      </div>

      <!-- 第3步：方案确认（后端实时生成，可人工调整） -->
      <div v-if="step === 3" class="panel">
        <el-alert type="warning" :closable="false" show-icon class="step-alert"
          title="排不进接收站的车会明确标成「暂无处安置」，单据仍可保存为待执行，但源站绝不会因此被标成已清空。" />
        <div class="plan-summary" v-if="previewRows.length">
          <span>共 <b>{{ previewData.totalCount }}</b> 辆待迁车</span>
          <span>故障车 <b class="fault">{{ previewData.faultCount }}</b></span>
          <span>已有去向 <b class="ok">{{ previewData.plannedCount }}</b></span>
          <span class="warn">暂无处安置 {{ previewData.unplacedCount }}</span>
        </div>
        <el-table :data="previewRows" class="tbl" v-loading="previewLoading">
          <el-table-column label="车辆" min-width="130">
            <template #default="{ row }">
              <strong>{{ row.bikeCode }}</strong>
              <el-tag v-if="row.faultFlag" size="small" type="danger" effect="plain" class="fault-tag">故障</el-tag>
              <div class="muted">{{ row.bikeModel }}</div>
            </template>
          </el-table-column>
          <el-table-column label="分配规则" min-width="160">
            <template #default="{ row }">{{ row.faultFlag ? '优先维修能力站' : '就近安置' }}</template>
          </el-table-column>
          <el-table-column label="计划接收站（可改）" min-width="260">
            <template #default="{ row }">
              <el-select v-model="row.planStationId" size="small" style="width: 230px" clearable
                placeholder="暂无处安置">
                <el-option v-for="r in selectedReceivers" :key="r.stationId"
                  :label="`${r.stationCode} · ${r.stationName}（剩${r.remainCapacity}/桩${r.freePileCount}）`"
                  :value="r.stationId" />
              </el-select>
              <el-tag v-if="!row.planStationId" size="small" type="warning" effect="plain" class="fault-tag">暂无处安置</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-form class="remark-row">
          <el-input v-model="createForm.remark" maxlength="255" placeholder="备注（可选），例如：配合市政施工，本周五前完成搬迁" />
        </el-form>
        <div class="panel-foot">
          <el-button @click="step = 2">上一步</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreate">保存为待执行</el-button>
        </div>
      </div>
    </template>

    <!-- ================= 详情 ================= -->
    <template v-if="mode === 'detail' && detail">
      <div class="bar">
        <div class="bar-left">
          <el-button text @click="backList">← 返回列表</el-button>
          <h2>清站单 {{ detail.orderNo }}</h2>
          <el-tag :type="statusType(detail.status)" effect="dark" size="default">{{ detail.statusName }}</el-tag>
        </div>
        <div class="bar-right">
          <el-button
            v-if="canCancel"
            type="info" plain
            @click="cancelOrder"
          >撤销清站单</el-button>
          <el-button
            v-if="canExecute"
            type="primary"
            :loading="executing"
            @click="executeOrder"
          >{{ detail.status === 'PENDING' ? '开始执行迁移' : '再次执行（迁受阻/未完成车辆）' }}</el-button>
        </div>
      </div>

      <!-- 源站实时体检 -->
      <div class="health">
        <div class="health-title">源站 {{ detail.sourceStationCode }} · {{ detail.sourceStationName }}
          <el-tag size="small" :type="detail.sourceStationStatus === 'OPEN' ? 'success' : 'info'" effect="dark">
            {{ detail.sourceStationStatus === 'OPEN' ? '运营中' : '已停用' }}
          </el-tag>
        </div>
        <div class="health-grid">
          <div class="h-item" :class="{ bad: detail.sourceBikeCount > 0 }">
            <span class="h-num">{{ detail.sourceBikeCount }}</span><span class="h-lbl">站内车辆(含维修区)</span></div>
          <div class="h-item" :class="{ bad: detail.sourceActiveRentals > 0 }">
            <span class="h-num">{{ detail.sourceActiveRentals }}</span><span class="h-lbl">在途租借</span></div>
          <div class="h-item" :class="{ bad: detail.sourceUnclosedOrders > 0 }">
            <span class="h-num">{{ detail.sourceUnclosedOrders }}</span><span class="h-lbl">未关闭工单</span></div>
          <div class="h-item" :class="{ bad: detail.sourceOccupiedPiles > 0 }">
            <span class="h-num">{{ detail.sourceOccupiedPiles }}</span><span class="h-lbl">仍占用桩位</span></div>
        </div>
        <el-alert v-if="detail.finishBlocker" type="warning" :closable="false" show-icon class="health-alert"
          :title="'源站暂不能停用：' + detail.finishBlocker" />
        <el-alert v-if="detail.status === 'DONE'" type="success" :closable="false" show-icon class="health-alert"
          :title="'全部车辆已迁出，源站已于 ' + fmt(detail.finishedAt) + ' 真正停用'" />
      </div>

      <!-- 接收站 -->
      <div class="section">
        <h3>接收站（{{ detail.targets.length }}）<span class="muted">执行时以实时状态重新校验</span></h3>
        <div class="target-grid">
          <div v-for="t in detail.targets" :key="t.stationId" class="target-card"
            :class="{ closed: !t.currentlyOpen, capable: t.currentlyFaultCapable }">
            <div class="tc-head">
              <strong>{{ t.stationCode }} · {{ t.stationName }}</strong>
              <el-tag size="small" :type="t.currentlyOpen ? 'success' : 'info'" effect="dark">
                {{ t.currentlyOpen ? '运营中' : '已停用' }}
              </el-tag>
            </div>
            <div class="tc-meta">距离 {{ t.distanceKm == null ? '—' : t.distanceKm + ' km' }}</div>
            <div class="tc-meta">实时剩余容量 <b>{{ t.remainCapacity }}</b> · 可用空桩 <b>{{ t.freePileCount }}</b></div>
            <div class="tc-meta">
              维修能力：
              <el-tag size="small" :type="t.currentlyFaultCapable ? 'success' : 'info'" effect="plain">
                {{ t.currentlyFaultCapable ? '在岗无冲突' : '不可接故障车' }}
              </el-tag>
              <span v-if="t.faultCapableSnapshot !== t.currentlyFaultCapable" class="changed">
                （创建时{{ t.faultCapableSnapshot ? '可' : '不可' }}，已变化）
              </span>
            </div>
            <div class="tc-meta">本单计划入 {{ t.plannedIn }} · 实际入 {{ t.movedIn }}</div>
          </div>
        </div>

        <!-- 部分受阻后补选接收站 -->
        <div class="add-target" v-if="canAddTargets && detail.availableReceivers.length">
          <el-select v-model="newTargetIds" multiple collapse-tags collapse-tags-tooltip
            placeholder="补选仍在运营的接收站" style="width: 420px">
            <el-option v-for="r in detail.availableReceivers" :key="r.stationId"
              :label="`${r.stationCode} · ${r.stationName}（剩${r.remainCapacity}/桩${r.freePileCount}）`"
              :value="r.stationId" />
          </el-select>
          <el-button type="primary" plain :disabled="!newTargetIds.length" @click="addTargets">补充接收站并自动补分</el-button>
        </div>
      </div>

      <!-- 车辆明细 -->
      <div class="section">
        <h3>车辆明细（{{ detail.totalCount }}）
          <span class="muted">已迁出 {{ detail.movedCount }} · 受阻 {{ detail.blockedCount }} · 待执行 {{ detail.plannedCount }} · 暂无处安置 {{ detail.unplacedCount }}</span>
        </h3>
        <el-table :data="detail.items" class="tbl" row-key="itemId">
          <el-table-column label="车辆" min-width="120">
            <template #default="{ row }">
              <strong>{{ row.bikeCode }}</strong>
              <el-tag v-if="row.faultFlag" size="small" type="danger" effect="plain" class="fault-tag">故障</el-tag>
              <div class="muted">{{ row.bikeModel }}</div>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.bikeStatus === 'PILE' ? 'success' : (row.bikeStatus === 'RIDING' ? 'warning' : 'info')">
                {{ row.bikeStatusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="计划去向" min-width="210">
            <template #default="{ row }">
              <template v-if="canAdjust && row.itemStatus !== 'MOVED'">
                <el-select v-model="adjustMap[row.itemId].stationId" size="small" style="width: 190px" clearable
                  placeholder="暂无处安置" @change="onAdjustStation(row)">
                  <el-option v-for="t in detail.targets" :key="t.stationId"
                    :label="t.stationCode + ' · ' + t.stationName" :value="t.stationId" :disabled="!t.currentlyOpen" />
                </el-select>
              </template>
              <template v-else>
                <span v-if="row.planStationName">{{ row.planStationName }}<span v-if="row.planPileNo"> / {{ row.planPileNo }}</span></span>
                <el-tag v-else size="small" type="warning" effect="plain">暂无处安置</el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="实际去向" min-width="170">
            <template #default="{ row }">
              <span v-if="row.actualStationName" class="actual">
                ✔ {{ row.actualStationName }}<span v-if="row.actualPileNo"> / {{ row.actualPileNo }}</span>
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="明细状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="dark" :type="itemType(row.itemStatus)">{{ row.itemStatusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="240" show-overflow-tooltip>
            <template #default="{ row }"><span class="blocker">{{ row.failReason || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="迁出时间" width="150">
            <template #default="{ row }">{{ fmt(row.movedAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="adjust-tip" v-if="canAdjust">
          改动「计划去向」后会实时校验容量/桩位；改为空即「暂无处安置」。故障车被人工派到无维修能力站时按人工指定执行（只卡硬约束）。
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { evacuationApi, stationApi } from '../api'

const route = useRoute()
const mode = ref('list')
const loading = ref(false)
const orders = ref([])
const statusFilter = ref('')
const statusOptions = [
  { value: 'PENDING', label: '待执行' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'PARTIAL', label: '部分受阻' },
  { value: 'DONE', label: '已完成' },
  { value: 'CANCELLED', label: '已撤销' }
]

// ---------- 创建向导 ----------
const step = ref(1)
const createForm = reactive({ sourceStationId: null, remark: '' })
const sourceChoices = ref([])
const receivers = ref([])
const selectedReceivers = ref([])
const loadingReceivers = ref(false)
const receiverTable = ref(null)
const previewRows = ref([])
const previewData = ref({ totalCount: 0, plannedCount: 0, unplacedCount: 0, faultCount: 0 })
const previewLoading = ref(false)
const creating = ref(false)

const stepActive = computed(() => step.value - 1)

// ---------- 详情 ----------
const detailId = ref(null)
const detail = ref(null)
const executing = ref(false)
const newTargetIds = ref([])
const adjustMap = reactive({})

const canExecute = computed(() => detail.value && ['PENDING', 'RUNNING', 'PARTIAL'].includes(detail.value.status))
const canCancel = computed(() => detail.value && detail.value.status === 'PENDING' && detail.value.movedCount === 0)
const canAdjust = computed(() => detail.value && ['PENDING', 'RUNNING', 'PARTIAL'].includes(detail.value.status))
const canAddTargets = computed(() => detail.value && ['PENDING', 'RUNNING', 'PARTIAL'].includes(detail.value.status))

function statusType(s) {
  return { PENDING: 'info', RUNNING: 'primary', PARTIAL: 'warning', DONE: 'success', CANCELLED: 'info' }[s] || 'info'
}
function itemType(s) {
  return { PLANNED: 'info', MOVED: 'success', BLOCKED: 'danger' }[s] || 'info'
}
function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
}

async function load() {
  loading.value = true
  try {
    orders.value = await evacuationApi.list(statusFilter.value || undefined)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  mode.value = 'create'
  step.value = 1
  createForm.sourceStationId = null
  createForm.remark = ''
  selectedReceivers.value = []
  previewRows.value = []
  previewData.value = { totalCount: 0, plannedCount: 0, unplacedCount: 0, faultCount: 0 }
  loadSources()
}

async function loadSources() {
  loading.value = true
  try {
    sourceChoices.value = await stationApi.list()
  } finally {
    loading.value = false
  }
}

async function onPickSource() {
  receivers.value = []
  selectedReceivers.value = []
}

async function goStep2() {
  step.value = 2
  loadingReceivers.value = true
  try {
    receivers.value = await evacuationApi.receivers(createForm.sourceStationId)
  } finally {
    loadingReceivers.value = false
  }
}

function onSelectReceivers(rows) {
  selectedReceivers.value = rows
}

function toggleReceiver(row) {
  receiverTable.value && receiverTable.value.toggleRowSelection(row)
}

async function goStep3() {
  step.value = 3
  previewLoading.value = true
  try {
    const data = await evacuationApi.preview({
      sourceStationId: createForm.sourceStationId,
      targetStationIds: selectedReceivers.value.map((r) => r.stationId),
      faultBikeIds: null
    })
    previewData.value = data
    previewRows.value = data.items
  } finally {
    previewLoading.value = false
  }
}

async function submitCreate() {
  creating.value = true
  try {
    // 只把运营人员改过的行作为 overrides 传给后端；其余沿用后端自动方案
    const overrides = previewRows.value.map((r) => ({
      bikeId: r.bikeId,
      planStationId: r.planStationId ?? null,
      planPileId: null
    }))
    const created = await evacuationApi.create({
      sourceStationId: createForm.sourceStationId,
      targetStationIds: selectedReceivers.value.map((r) => r.stationId),
      remark: createForm.remark || null,
      faultBikeIds: null,
      overrides
    })
    ElMessage.success('清站单已保存为待执行')
    await openDetailData(created.id)
  } catch (e) {
    /* 全局拦截器已提示 */
  } finally {
    creating.value = false
  }
}

function backList() {
  mode.value = 'list'
  detail.value = null
  load()
}

async function openDetail(id) {
  await openDetailData(id)
}

async function openDetailData(id) {
  detailId.value = id
  mode.value = 'detail'
  await loadDetail()
}

async function loadDetail() {
  const d = await evacuationApi.detail(detailId.value)
  detail.value = d
  newTargetIds.value = []
  Object.keys(adjustMap).forEach((k) => delete adjustMap[k])
  for (const it of d.items) {
    adjustMap[it.itemId] = { stationId: it.planStationId }
  }
}

async function onAdjustStation(row) {
  const stationId = adjustMap[row.itemId].stationId
  try {
    await evacuationApi.adjustItem(detailId.value, row.itemId, stationId || null, null)
    ElMessage.success(stationId ? `已把 ${row.bikeCode} 的计划去向改为新接收站` : `已把 ${row.bikeCode} 改为暂无处安置`)
    await loadDetail()
  } catch (e) {
    await loadDetail()
  }
}

async function executeOrder() {
  executing.value = true
  try {
    const res = await evacuationApi.execute(detailId.value)
    if (res.sourceClosed) {
      ElMessage.success(res.message)
    } else if (res.blockedThisRound > 0) {
      ElMessage.warning(res.message)
    } else {
      ElMessage.info(res.message)
    }
    await loadDetail()
    // 刷新页面不丢进度：数据全部来自后端持久化结果，重新打开仍是同一状态
  } finally {
    executing.value = false
  }
}

async function cancelOrder() {
  try {
    await ElMessageBox.confirm(
      '撤销只允许发生在尚未迁出任何车辆时。撤销后这张清站单作废，源站维持运营中。确认撤销？',
      '撤销清站单',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await evacuationApi.cancel(detailId.value)
    ElMessage.success('清站单已撤销')
    await loadDetail()
  } catch (e) {
    /* keep */
  }
}

async function addTargets() {
  try {
    await evacuationApi.addTargets(detailId.value, newTargetIds.value)
    ElMessage.success('接收站已补充，暂无处安置的车辆已按规则自动补分')
    await loadDetail()
  } catch (e) {
    /* keep */
  }
}

onMounted(async () => {
  const q = route.query
  if (q.code && /^CL\d+$/i.test(String(q.code))) {
    // 直查清站单号：先查出 id
    try {
      const all = await evacuationApi.list()
      const hit = all.find((o) => o.orderNo === String(q.code).toUpperCase())
      if (hit) {
        await openDetailData(hit.id)
        return
      }
    } catch (e) {
      /* ignore */
    }
  }
  load()
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.bar h2 { margin: 0 8px 4px 0; font-size: 18px; color: #2f3b22; display: inline-flex; align-items: center; gap: 10px; }
.tip { font-size: 12px; color: #93a386; }
.tip b { color: var(--el-color-primary-dark-2); }
.bar-right { display: flex; gap: 8px; align-items: center; }
.muted { color: #a5b09b; font-size: 12px; font-weight: 400; }
.blocker { color: #b56a1f; font-size: 12px; }
.prog-text { font-size: 11px; color: #6f7f62; }
.prog-text em { font-style: normal; color: #d9534f; margin-left: 4px; }
.tbl { background: #fff; border-radius: 10px; }

.steps { max-width: 720px; margin: 6px auto 4px; }
.panel { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 1px 4px rgba(15,30,10,.06); }
.panel-tip { color: #6f7f62; font-size: 13px; margin: 0 0 14px; }
.panel-foot { margin-top: 18px; display: flex; justify-content: flex-end; gap: 10px; }
.step-alert { margin-bottom: 14px; }
.source-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.source-radio { width: 100%; height: auto; margin: 0; border-radius: 10px; padding: 0; }
.source-card-inner { display: flex; flex-direction: column; gap: 4px; padding: 12px 14px; text-align: left; }
.source-card-inner strong { color: #2f3b22; }
.source-card-inner span { font-size: 12px; color: #7e8c72; }
.cap-zero { color: #d9534f; font-weight: 700; }
.worker-sum { margin-left: 8px; font-size: 11px; color: #93a386; }
.plan-summary { display: flex; gap: 20px; align-items: center; background: var(--el-color-primary-light-9);
  border-radius: 8px; padding: 10px 14px; margin-bottom: 12px; font-size: 13px; color: #4b6b32; }
.plan-summary .ok { color: #3f8a2c; }
.plan-summary .fault { color: #c0392b; }
.plan-summary .warn { color: #b56a1f; font-weight: 700; margin-left: auto; }
.remark-row { margin-top: 12px; }
.fault-tag { margin-left: 6px; }

.health { background: #fff; border-radius: 12px; padding: 14px 18px; box-shadow: 0 1px 4px rgba(15,30,10,.06); }
.health-title { font-weight: 700; color: #2f3b22; margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
.health-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.h-item { background: #f4f8f0; border-radius: 10px; padding: 10px 14px; display: flex; flex-direction: column;
  border-left: 4px solid var(--el-color-primary); }
.h-item.bad { background: #fdf3f3; border-left-color: #d9534f; }
.h-num { font-size: 22px; font-weight: 700; color: var(--el-color-primary-dark-2); }
.h-item.bad .h-num { color: #c0392b; }
.h-lbl { font-size: 12px; color: #7e8c72; }
.health-alert { margin-top: 12px; }

.section { background: #fff; border-radius: 12px; padding: 14px 18px; box-shadow: 0 1px 4px rgba(15,30,10,.06); }
.section h3 { margin: 0 0 12px; font-size: 15px; color: #2f3b22; display: flex; gap: 10px; align-items: baseline; }
.target-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.target-card { border: 1.5px solid #dfe7d8; border-radius: 10px; padding: 11px 13px; display: flex; flex-direction: column; gap: 5px; }
.target-card.capable { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.target-card.closed { opacity: .65; background: #f6f6f6; }
.tc-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.tc-meta { font-size: 12px; color: #6f7f62; }
.changed { color: #b56a1f; }
.add-target { margin-top: 14px; display: flex; gap: 10px; align-items: center; }
.actual { color: #3f8a2c; font-weight: 600; font-size: 13px; }
.adjust-tip { margin-top: 8px; font-size: 11px; color: #a5b09b; }
</style>
