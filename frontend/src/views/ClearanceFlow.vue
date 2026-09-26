<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>应急清站</h2>
        <span class="tip">
          停用仍有车的站点前先建清站单：故障车优先送往<b>有在岗维修工且无工单冲突</b>的站点，
          普通车就近安置；接收能力不足时如实标注「无处安置」，<b>绝不伪造完整方案、绝不提前清空源站</b>。
        </span>
      </div>
      <div class="bar-right">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="待执行" value="PENDING" />
          <el-option label="执行中" value="RUNNING" />
          <el-option label="部分受阻" value="PARTIALLY_BLOCKED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已撤销" value="CANCELLED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">+ 新建清站单</el-button>
      </div>
    </div>

    <!-- 列表 -->
    <el-table :data="orders" v-loading="loading" stripe>
      <el-table-column prop="orderNo" label="清站单号" width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row.id)">{{ row.orderNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="源站" min-width="170">
        <template #default="{ row }">
          <span>{{ row.sourceStationCode }} · {{ row.sourceStationName }}</span>
          <el-tag size="small" :type="row.sourceStationStatus === 'OPEN' ? 'success' : 'info'" class="ml6">
            {{ row.sourceStationStatus === 'OPEN' ? '运营中' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="接收站" min-width="200">
        <template #default="{ row }">{{ (row.receiverStationNames || []).join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="dark" size="small">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度（已迁/总数）" width="140">
        <template #default="{ row }">
          <span :class="{ 'num-warn': row.blockedCount > 0 }">{{ row.migratedCount }} / {{ row.totalCount }}</span>
          <el-tag v-if="row.blockedCount > 0" size="small" type="danger" effect="plain" class="ml6">
            受阻 {{ row.blockedCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastExecuteResult" label="最近执行结果" min-width="260" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
    </el-table>

    <!-- ================= 新建清站单（含方案预览） ================= -->
    <el-drawer v-model="createVisible" title="新建清站单" size="72%">
      <div class="create-wrap">
        <el-form label-width="92px" class="create-form">
          <el-form-item label="源站">
            <el-select
              v-model="form.sourceStationId"
              placeholder="选择要停用/清空的源站（必须运营中、站内有车）"
              filterable
              style="width: 420px"
              @change="onSourceChange"
            >
              <el-option
                v-for="s in sourceOptions"
                :key="s.id"
                :label="`${s.code} · ${s.name}（${s.bikeCount}/${s.capacity}）`"
                :value="s.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="接收站">
            <el-select
              v-model="form.receiverStationIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择一个或多个运营中的接收站（按住可多选）"
              style="width: 420px"
              @change="schedulePreview"
            >
              <el-option
                v-for="s in receiverOptions"
                :key="s.id"
                :label="`${s.code} · ${s.name}（容量余 ${Math.max(s.capacity - s.bikeCount, 0)}，空桩 ${s.freePileCount}）`"
                :value="s.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :loading="previewing" :disabled="!canPreview" type="primary" plain @click="doPreview">
              生成/刷新方案
            </el-button>
            <el-button
              type="primary"
              :loading="creating"
              :disabled="!preview"
              @click="doCreate"
            >保存为待执行</el-button>
            <span class="form-hint">接收能力不足时也允许保存，但源站不会被标成已清空。</span>
          </el-form-item>
        </el-form>

        <div v-if="previewError" class="preview-error">{{ previewError }}</div>

        <template v-if="preview">
          <div class="plan-summary">
            <span>源站待迁 <b>{{ preview.waitingCount }}</b> 辆</span>
            <span class="ok">已安排 <b>{{ preview.plannedCount }}</b> 辆</span>
            <span :class="{ warn: preview.unplacedCount > 0 }">
              无处安置 <b>{{ preview.unplacedCount }}</b> 辆
            </span>
          </div>
          <el-alert :title="preview.planRemark" :type="preview.unplacedCount > 0 ? 'warning' : 'success'"
                    :closable="false" show-icon class="plan-alert" />

          <h4>各接收站实时能力</h4>
          <div class="recv-grid">
            <div v-for="r in preview.receivers" :key="r.stationId" class="recv-card">
              <div class="recv-head">
                <b>{{ r.stationCode }} · {{ r.stationName }}</b>
                <el-tag size="small" :type="r.stationStatus === 'OPEN' ? 'success' : 'info'">
                  {{ r.stationStatus === 'OPEN' ? '运营中' : '已停用' }}
                </el-tag>
              </div>
              <div class="recv-meta">
                容量 {{ r.bikeCount }}/{{ r.capacity }}（余 {{ Math.max(r.capacity - r.bikeCount, 0) }}）
                · 空桩 {{ r.freePileCount }}
                <template v-if="r.distanceKm != null"> · 距源站 {{ r.distanceKm.toFixed(1) }} km</template>
              </div>
              <div class="recv-worker">
                <el-tag size="small" :type="r.faultCapable ? 'success' : 'danger'" effect="plain">
                  {{ r.faultCapable ? '可接故障车' : '不能接故障车' }}
                </el-tag>
                <span v-if="r.faultCapable" class="worker-names">空闲维修工：{{ r.freeWorkerNames.join('、') }}</span>
                <span v-else class="worker-names warn-text">{{ r.faultBlockReason }}</span>
              </div>
            </div>
          </div>

          <h4>车辆迁移方案（可在创建后逐辆人工调整）</h4>
          <el-table :data="preview.items" size="small" max-height="320">
            <el-table-column label="车辆" min-width="150">
              <template #default="{ row }">
                {{ row.bikeCode }}
                <el-tag size="small" :type="row.bikeKind === 'FAULT' ? 'danger' : 'info'" effect="plain" class="ml6">
                  {{ row.bikeKindName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="bikeStatusName" label="当前状态" width="90" />
            <el-table-column label="计划去向" min-width="180">
              <template #default="{ row }">
                <span v-if="row.planStationName" class="ok">{{ row.planStationName }} · {{ row.planPileNo || '维修区' }}</span>
                <span v-else class="warn-text">暂时无处安置</span>
              </template>
            </el-table-column>
            <el-table-column prop="failReason" label="说明" min-width="220" show-overflow-tooltip />
          </el-table>
        </template>
      </div>
    </el-drawer>

    <!-- ================= 清站单详情 ================= -->
    <el-drawer v-model="detailVisible" :title="detail ? `清站单 ${detail.orderNo}` : '清站单详情'" size="78%">
      <div v-if="detail" class="detail-wrap" v-loading="detailLoading">
        <div class="detail-head">
          <div>
            <h3>
              {{ detail.sourceStationCode }} · {{ detail.sourceStationName }}
              <el-tag :type="statusType(detail.status)" effect="dark" class="ml6">{{ detail.statusName }}</el-tag>
            </h3>
            <div class="sub">
              源站当前：<b :class="detail.sourceStationStatus === 'OPEN' ? 'ok' : 'muted'">
                {{ detail.sourceStationStatus === 'OPEN' ? '运营中（未清空）' : '已停用' }}
              </b>
              · 已迁出 {{ detail.migratedCount }}/{{ detail.totalCount }}
              <el-tag v-if="detail.blockedCount > 0" size="small" type="danger" effect="plain" class="ml6">
                受阻/无处安置 {{ detail.blockedCount }}
              </el-tag>
            </div>
            <div v-if="detail.planRemark" class="sub muted">{{ detail.planRemark }}</div>
            <div v-if="detail.lastExecuteResult" class="sub result">最近执行：{{ detail.lastExecuteResult }}</div>
          </div>
          <div class="detail-actions">
            <el-button
              type="primary"
              :loading="executing"
              :disabled="!canExecute"
              @click="doExecute"
            >{{ detail.status === 'PENDING' ? '开始执行' : '继续执行' }}</el-button>
            <el-button
              type="warning"
              plain
              :disabled="!canCancel"
              @click="doCancel"
            >撤销清站单</el-button>
          </div>
        </div>

        <!-- 完成闸门 -->
        <div class="gate">
          <span class="gate-title">完成闸门（四项全部为 0 才能完成并停用源站）：</span>
          <el-tag :type="gateTag(detail.gate.bikesAtStation)" size="small">在站车辆 {{ detail.gate.bikesAtStation }}</el-tag>
          <el-tag :type="gateTag(detail.gate.ridingRentals)" size="small">在途租借 {{ detail.gate.ridingRentals }}</el-tag>
          <el-tag :type="gateTag(detail.gate.openWorkOrders)" size="small">未关闭工单 {{ detail.gate.openWorkOrders }}</el-tag>
          <el-tag :type="gateTag(detail.gate.occupiedPiles)" size="small">占用桩位 {{ detail.gate.occupiedPiles }}</el-tag>
        </div>

        <h4>接收站实时能力</h4>
        <div class="recv-grid">
          <div v-for="r in detail.receivers" :key="r.stationId" class="recv-card">
            <div class="recv-head">
              <b>{{ r.stationCode }} · {{ r.stationName }}</b>
              <el-tag size="small" :type="r.stationStatus === 'OPEN' ? 'success' : 'info'">
                {{ r.stationStatus === 'OPEN' ? '运营中' : '已停用' }}
              </el-tag>
            </div>
            <div class="recv-meta">
              容量 {{ r.bikeCount }}/{{ r.capacity }}（余 {{ Math.max(r.capacity - r.bikeCount, 0) }}）
              · 空桩 {{ r.freePileCount }}
              <template v-if="r.distanceKm != null"> · 距源站 {{ r.distanceKm.toFixed(1) }} km</template>
            </div>
            <div class="recv-worker">
              <el-tag size="small" :type="r.faultCapable ? 'success' : 'danger'" effect="plain">
                {{ r.faultCapable ? '可接故障车' : '不能接故障车' }}
              </el-tag>
              <span v-if="r.faultCapable" class="worker-names">空闲维修工：{{ r.freeWorkerNames.join('、') }}</span>
              <span v-else class="worker-names warn-text">{{ r.faultBlockReason }}</span>
            </div>
          </div>
        </div>

        <h4>车辆明细（计划去向 / 实际去向 / 失败原因）</h4>
        <el-table :data="detail.items" size="small">
          <el-table-column label="车辆" width="160">
            <template #default="{ row }">
              {{ row.bikeCode }}
              <el-tag size="small" :type="row.bikeKind === 'FAULT' ? 'danger' : 'info'" effect="plain" class="ml6">
                {{ row.bikeKindName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="bikeStatusName" label="车当前状态" width="100" />
          <el-table-column label="明细状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="itemStatusType(row.itemStatus)" effect="dark">
                {{ row.itemStatusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="计划去向" min-width="160">
            <template #default="{ row }">
              <span v-if="row.planStationName">{{ row.planStationName }} · {{ row.planPileNo || '维修区' }}</span>
              <span v-else class="warn-text">无处安置</span>
            </template>
          </el-table-column>
          <el-table-column label="实际去向" min-width="160">
            <template #default="{ row }">
              <span v-if="row.actualStationName" class="ok">
                {{ row.actualStationName }} · {{ row.actualPileNo || '维修区' }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="failReason" label="受阻/说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small" link type="primary"
                :disabled="!canAdjust(row)"
                @click="openAdjust(row)"
              >调整去向</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 人工调整去向 -->
    <el-dialog v-model="adjustVisible" title="调整车辆计划去向" width="480px">
      <div v-if="adjustingItem" class="adjust-box">
        <p>车辆 <b>{{ adjustingItem.bikeCode }}</b>（{{ adjustingItem.bikeKindName }}）</p>
        <el-radio-group v-model="adjustForm.mode" class="adjust-mode">
          <el-radio value="station">改派到接收站</el-radio>
          <el-radio value="unplace">标记为暂时无处安置</el-radio>
        </el-radio-group>
        <template v-if="adjustForm.mode === 'station'">
          <el-select v-model="adjustForm.stationId" placeholder="选择接收站" filterable style="width: 100%">
            <el-option
              v-for="r in detail.receivers"
              :key="r.stationId"
              :label="`${r.stationCode} · ${r.stationName}（容量余 ${Math.max(r.capacity - r.bikeCount, 0)}，空桩 ${r.freePileCount}）`"
              :value="r.stationId"
            />
          </el-select>
          <p class="form-hint">
            在桩车辆由后端自动挑选当前空闲桩位；若该车辆是故障车而目标站不具备维修条件，
            系统会记录提示，但仍按你的决定执行。
          </p>
        </template>
      </div>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="doAdjust">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearanceApi, stationApi } from '../api'

const route = useRoute()

const loading = ref(false)
const orders = ref([])
const statusFilter = ref('')

const createVisible = ref(false)
const previewing = ref(false)
const creating = ref(false)
const preview = ref(null)
const previewError = ref('')
const stations = ref([])
const form = reactive({ sourceStationId: null, receiverStationIds: [] })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const executing = ref(false)

const adjustVisible = ref(false)
const adjusting = ref(false)
const adjustingItem = ref(null)
const adjustForm = reactive({ mode: 'station', stationId: null })

const sourceOptions = computed(() =>
  stations.value.filter((s) => s.status === 'OPEN' && s.bikeCount > 0)
)
const receiverOptions = computed(() =>
  stations.value.filter((s) => s.status === 'OPEN' && s.id !== form.sourceStationId)
)
const canPreview = computed(() => form.sourceStationId && form.receiverStationIds.length > 0)

const canExecute = computed(() =>
  detail.value && ['PENDING', 'RUNNING', 'PARTIALLY_BLOCKED'].includes(detail.value.status)
)
const canCancel = computed(() =>
  detail.value && ['PENDING', 'RUNNING', 'PARTIALLY_BLOCKED'].includes(detail.value.status)
    && detail.value.migratedCount === 0
)

function statusType(status) {
  return {
    PENDING: 'info',
    RUNNING: 'warning',
    PARTIALLY_BLOCKED: 'danger',
    COMPLETED: 'success',
    CANCELLED: 'info'
  }[status] || 'info'
}

function itemStatusType(status) {
  return {
    PLANNED: 'warning',
    UNPLACED: 'info',
    MIGRATED: 'success',
    BLOCKED: 'danger'
  }[status] || 'info'
}

function gateTag(n) {
  return n === 0 ? 'success' : 'danger'
}

function canAdjust(row) {
  if (!detail.value) return false
  if (!['PENDING', 'RUNNING', 'PARTIALLY_BLOCKED'].includes(detail.value.status)) return false
  return row.itemStatus === 'PLANNED' || row.itemStatus === 'UNPLACED' || row.itemStatus === 'BLOCKED'
}

async function load() {
  loading.value = true
  try {
    orders.value = await clearanceApi.list({ status: statusFilter.value || undefined })
  } finally {
    loading.value = false
  }
}

async function loadStations() {
  if (!stations.value.length) {
    stations.value = await stationApi.list()
  }
}

function openCreate() {
  preview.value = null
  previewError.value = ''
  form.sourceStationId = null
  form.receiverStationIds = []
  createVisible.value = true
  loadStations()
}

function onSourceChange() {
  form.receiverStationIds = form.receiverStationIds.filter((id) => id !== form.sourceStationId)
  preview.value = null
  schedulePreview()
}

let previewTimer = null
function schedulePreview() {
  previewError.value = ''
  clearTimeout(previewTimer)
  if (!canPreview.value) return
  previewTimer = setTimeout(doPreview, 250)
}

async function doPreview() {
  if (!canPreview.value) {
    ElMessage.warning('请先选择源站和至少一个接收站')
    return
  }
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await clearanceApi.preview(form.sourceStationId, [...form.receiverStationIds])
  } catch (e) {
    preview.value = null
    previewError.value = e.message
  } finally {
    previewing.value = false
  }
}

async function doCreate() {
  if (!preview.value) return
  creating.value = true
  try {
    const created = await clearanceApi.create(form.sourceStationId, [...form.receiverStationIds])
    ElMessage.success('清站单已保存为待执行')
    createVisible.value = false
    await load()
    await openDetail(created.id)
  } finally {
    creating.value = false
  }
}

async function openDetail(id) {
  detailVisible.value = true
  await refreshDetail(id)
}

async function refreshDetail(id) {
  const targetId = id || (detail.value && detail.value.id)
  detailLoading.value = true
  try {
    detail.value = await clearanceApi.detail(targetId)
  } finally {
    detailLoading.value = false
  }
}

async function doExecute() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      '执行将逐辆迁移可迁出的车辆；被借走、桩位被占或接收站停用的车辆会留在单内标为受阻，其它车辆照常迁出、整单不会回滚。确定执行？',
      '执行清站',
      { type: 'warning', confirmButtonText: '执行' }
    )
  } catch (e) {
    return
  }
  executing.value = true
  try {
    const res = await clearanceApi.execute(detail.value.id)
    detail.value = res
    if (res.status === 'COMPLETED') {
      ElMessage.success('清站完成，源站已停用')
    } else if (res.executingAt) {
      ElMessage.info('另一个执行请求正在处理中，这里显示的是最新进度')
    } else {
      ElMessage.warning(res.lastExecuteResult || '部分车辆未迁出')
    }
    await load()
    // 刷新站点看板依赖的缓存数据
    stations.value = await stationApi.list()
  } finally {
    executing.value = false
  }
}

async function doCancel() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      '撤销只允许发生在尚未迁出任何车辆时。撤销后该单不再占用源站。确定撤销？',
      '撤销清站单',
      { type: 'warning', confirmButtonText: '撤销' }
    )
  } catch (e) {
    return
  }
  detail.value = await clearanceApi.cancel(detail.value.id)
  ElMessage.success('清站单已撤销')
  await load()
}

function openAdjust(row) {
  adjustingItem.value = row
  adjustForm.mode = row.planStationId ? 'station' : 'unplace'
  adjustForm.stationId = row.planStationId || (detail.value.receivers[0] && detail.value.receivers[0].stationId)
  adjustVisible.value = true
}

async function doAdjust() {
  if (!adjustingItem.value) return
  const payload = adjustForm.mode === 'unplace'
    ? { unplace: true }
    : { targetStationId: adjustForm.stationId }
  if (adjustForm.mode === 'station' && !adjustForm.stationId) {
    ElMessage.warning('请选择接收站')
    return
  }
  adjusting.value = true
  try {
    detail.value = await clearanceApi.adjust(detail.value.id, adjustingItem.value.itemId, payload)
    ElMessage.success('方案已调整')
    adjustVisible.value = false
  } finally {
    adjusting.value = false
  }
}

onMounted(async () => {
  await load()
  // 从站点看板的「正在清站 / 清站完成」角标跳来时自动打开详情
  if (route.query.id) {
    openDetail(Number(route.query.id))
  }
})
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

.tip b {
  color: var(--el-color-primary-dark-2);
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ml6 {
  margin-left: 6px;
}

.num-warn {
  color: var(--el-color-danger);
  font-weight: 700;
}

.create-wrap,
.detail-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 8px;
}

.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #93a386;
}

.preview-error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
}

.plan-summary {
  display: flex;
  gap: 20px;
  background: #fff;
  border-radius: 8px;
  padding: 10px 14px;
  font-size: 14px;
}

.plan-summary .ok {
  color: var(--el-color-success-dark-2);
}

.plan-summary .warn,
.warn-text {
  color: var(--el-color-warning-dark-2);
}

.plan-alert {
  margin-bottom: 4px;
}

h4 {
  margin: 8px 0 0;
  color: #2f3b22;
}

.recv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}

.recv-card {
  background: #fff;
  border: 1px solid #e3e8de;
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.recv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.recv-meta {
  font-size: 12px;
  color: #8b9a7c;
}

.recv-worker {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.worker-names {
  font-size: 12px;
  color: #6f7f62;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
}

.detail-head h3 {
  margin: 0 0 6px;
  color: #2f3b22;
}

.sub {
  font-size: 13px;
  color: #6f7f62;
  margin-top: 3px;
}

.sub.result {
  color: var(--el-color-primary-dark-2);
}

.muted {
  color: #9aa591;
}

.ok {
  color: var(--el-color-success-dark-2);
}

.detail-actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.gate {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  background: var(--el-color-primary-light-9);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13px;
}

.gate-title {
  color: #4b6b32;
  font-weight: 600;
}

.adjust-box {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.adjust-mode {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
