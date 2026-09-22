<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>运维工单</h2>
        <span class="tip">
          四列 = 上报 → 派单 → 修复 → 关闭，每张卡上只出现「下一步」那一个按钮，跳级和回退都会被后端挡下
        </span>
      </div>
      <div class="bar-right">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openReport">+ 上报故障</el-button>
      </div>
    </div>

    <div class="board" v-loading="loading">
      <section v-for="col in columns" :key="col.value" class="col">
        <header class="col-head" :class="'col-head--' + col.value.toLowerCase()">
          <span class="col-name">{{ col.label }}</span>
          <span class="col-count">{{ grouped[col.value] ? grouped[col.value].length : 0 }}</span>
        </header>
        <div class="col-body">
          <el-empty v-if="!grouped[col.value] || grouped[col.value].length === 0" description="没有工单" :image-size="60" />
          <article
            v-for="o in grouped[col.value]"
            :key="o.id"
            class="wo-card"
            :class="[`lv-${o.level.toLowerCase()}`, { 'wo-card--focus': o.orderNo === focusCode }]"
          >
            <div class="wo-top">
              <span class="wo-no">{{ o.orderNo }}</span>
              <el-tag size="small" :type="levelType(o.level)" effect="plain">{{ o.levelName }}危</el-tag>
            </div>
            <div class="wo-bike">{{ o.bikeCode }}</div>
            <div class="wo-fault">{{ o.faultType }}</div>
            <dl class="wo-meta">
              <div><dt>上报</dt><dd>{{ fmt(o.reportTime) }}</dd></div>
              <div><dt>维修工</dt><dd>{{ o.workerName || '未派单' }}</dd></div>
              <div v-if="o.planStart"><dt>时段</dt><dd>{{ fmt(o.planStart) }} ~ {{ fmt(o.planEnd) }}</dd></div>
              <div v-if="o.fixNote"><dt>说明</dt><dd>{{ o.fixNote }}</dd></div>
            </dl>
            <div class="wo-act">
              <el-button v-if="o.status === 'REPORTED'" size="small" type="primary" @click="openAssign(o)">派单</el-button>
              <el-button v-if="o.status === 'ASSIGNED'" size="small" type="primary" @click="openFix(o)">修复</el-button>
              <el-button v-if="o.status === 'FIXED'" size="small" type="success" @click="closeOrder(o)">关闭</el-button>
              <span v-if="o.status === 'CLOSED'" class="wo-done">流程已走完</span>
            </div>
          </article>
        </div>
      </section>
    </div>

    <!-- 上报故障：只能选骑行中的车 -->
    <el-dialog v-model="reportVisible" title="上报故障" width="560">
      <el-alert
        class="rep-alert"
        type="info"
        :closable="false"
        show-icon
        title="只有「骑行中」的车辆能上报故障；在桩、维修中、已报废的车，以及已经挂着未完工工单的车，都会被后端拒绝。"
      />
      <el-form label-width="96px">
        <el-form-item label="故障车辆">
          <el-select v-model="reportForm.bikeId" filterable clearable placeholder="选一辆骑行中的车" style="width: 100%">
            <el-option
              v-for="b in ridingBikes"
              :key="b.id"
              :label="`${b.code} · ${b.model}（累计 ${b.totalRides} 次）`"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="故障类型">
          <el-select v-model="reportForm.faultType" allow-create filterable default-first-option style="width: 100%">
            <el-option v-for="t in faultTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="紧急程度">
          <el-radio-group v-model="reportForm.level">
            <el-radio-button value="LOW">低</el-radio-button>
            <el-radio-button value="MID">中</el-radio-button>
            <el-radio-button value="HIGH">高</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReport">上报</el-button>
      </template>
    </el-dialog>

    <!-- 派单 -->
    <el-dialog v-model="assignVisible" title="派单" width="560">
      <div v-if="assigning" class="assign-info">
        工单 <b>{{ assigning.orderNo }}</b> ｜ 车辆 <b>{{ assigning.bikeCode }}</b> ｜ {{ assigning.faultType }}
      </div>
      <el-form label-width="96px">
        <el-form-item label="维修工">
          <el-select v-model="assignForm.workerId" clearable placeholder="选一位在岗维修工" style="width: 100%">
            <el-option
              v-for="w in workers"
              :key="w.id"
              :label="`${w.name} · ${w.skill}（${w.status === 'ON_DUTY' ? '在岗' : '休息'}）`"
              :value="w.id"
              :disabled="w.status !== 'ON_DUTY'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划开始">
          <el-date-picker
            v-model="assignForm.planStart"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="计划结束">
          <el-date-picker
            v-model="assignForm.planEnd"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择结束时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <p class="assign-tip">
        同一个维修工的在途工单时段不能重叠（只有「已修复」「已关闭」的工单不再占用时段）。
        种子数据里张师傅 09-22 09:00~11:00 已有一单，可以拿它试重叠拦截。
      </p>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAssign">派单</el-button>
      </template>
    </el-dialog>

    <!-- 修复 -->
    <el-dialog v-model="fixVisible" title="登记修复" width="520">
      <div v-if="fixing" class="assign-info">
        工单 <b>{{ fixing.orderNo }}</b> ｜ 车辆 <b>{{ fixing.bikeCode }}</b> ｜ 维修工 {{ fixing.workerName }}
      </div>
      <el-input v-model="fixNote" type="textarea" :rows="4" placeholder="写清换了什么件、怎么处理的（不能为空）" />
      <template #footer>
        <el-button @click="fixVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFix">确认修复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bikeApi, workOrderApi } from '../api'

const columns = [
  { value: 'REPORTED', label: '已上报' },
  { value: 'ASSIGNED', label: '已派单' },
  { value: 'FIXED', label: '已修复' },
  { value: 'CLOSED', label: '已关闭' }
]

const faultTypes = ['智能锁无法上锁', '后刹车异响', '脚踏板松动', '链条脱落', '车架焊点开裂', '轮胎漏气', '电控无响应']

const route = useRoute()
const loading = ref(false)
const orders = ref([])
const bikes = ref([])
const workers = ref([])

const reportVisible = ref(false)
const reportForm = reactive({ bikeId: null, faultType: '', level: 'MID' })

const assignVisible = ref(false)
const assigning = ref(null)
const assignForm = reactive({ workerId: null, planStart: '', planEnd: '' })

const fixVisible = ref(false)
const fixing = ref(null)
const fixNote = ref('')

const focusCode = ref((route.query.code || '').toString().toUpperCase())

const grouped = computed(() => {
  const map = {}
  for (const col of columns) map[col.value] = []
  for (const o of orders.value) {
    if (map[o.status]) map[o.status].push(o)
  }
  return map
})

const ridingBikes = computed(() => bikes.value.filter((b) => b.status === 'RIDING'))

function levelType(level) {
  if (level === 'HIGH') return 'danger'
  if (level === 'MID') return 'warning'
  return 'info'
}

function fmt(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    const [orderList, bikeList, workerList] = await Promise.all([
      workOrderApi.list(),
      bikeApi.list(),
      workOrderApi.workers()
    ])
    orders.value = orderList
    bikes.value = bikeList
    workers.value = workerList
  } finally {
    loading.value = false
  }
}

function openReport() {
  reportForm.bikeId = null
  reportForm.faultType = ''
  reportForm.level = 'MID'
  reportVisible.value = true
}

async function submitReport() {
  if (!reportForm.bikeId) {
    ElMessage.warning('请先选一辆骑行中的车')
    return
  }
  if (!reportForm.faultType || !reportForm.faultType.trim()) {
    ElMessage.warning('请填写故障类型')
    return
  }
  try {
    await workOrderApi.report(reportForm.bikeId, reportForm.faultType.trim(), reportForm.level)
  } catch (e) {
    return
  }
  ElMessage.success('故障已上报')
  reportVisible.value = false
  await load()
}

function openAssign(o) {
  assigning.value = o
  assignForm.workerId = null
  assignForm.planStart = ''
  assignForm.planEnd = ''
  assignVisible.value = true
}

async function submitAssign() {
  if (!assignForm.workerId) {
    ElMessage.warning('请先选一位维修工')
    return
  }
  if (!assignForm.planStart || !assignForm.planEnd) {
    ElMessage.warning('请把计划开始与结束时间都选上')
    return
  }
  try {
    await workOrderApi.assign(assigning.value.id, assignForm.workerId, assignForm.planStart, assignForm.planEnd)
    ElMessage.success('工单已派单')
    assignVisible.value = false
    await load()
  } catch (e) {
    await load()
  }
}

function openFix(o) {
  fixing.value = o
  fixNote.value = ''
  fixVisible.value = true
}

async function submitFix() {
  if (!fixNote.value.trim()) {
    ElMessage.warning('维修说明不能为空')
    return
  }
  try {
    await workOrderApi.fix(fixing.value.id, fixNote.value.trim())
  } catch (e) {
    return
  }
  ElMessage.success('工单已修复')
  fixVisible.value = false
  await load()
}

async function closeOrder(o) {
  try {
    await ElMessageBox.confirm(`关闭工单 ${o.orderNo}？关闭后这张单就结束了。`, '关闭工单', { type: 'warning' })
  } catch (e) {
    return
  }
  await workOrderApi.close(o.id)
  ElMessage.success('工单已关闭')
  await load()
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

.board {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  align-items: start;
}

.col {
  background: #f1f4ee;
  border-radius: 12px;
  padding: 10px;
  min-height: 140px;
}

.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 9px;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
}

.col-head--reported { background: #c98a1b; }
.col-head--assigned { background: #4a7fb5; }
.col-head--fixed { background: #558b2f; }
.col-head--closed { background: #8d8d8d; }

.col-count {
  background: rgba(255, 255, 255, 0.28);
  border-radius: 999px;
  padding: 1px 10px;
  font-size: 12px;
}

.col-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.wo-card {
  background: #fff;
  border-radius: 10px;
  padding: 10px 12px;
  border-left: 5px solid #c98a1b;
  box-shadow: 0 1px 4px rgba(15, 30, 10, 0.07);
}

.lv-mid { border-left-color: #4a7fb5; }
.lv-low { border-left-color: #9fb0a0; }

.wo-card--focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.wo-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.wo-no {
  font-size: 12px;
  font-weight: 700;
  color: #3f5a29;
  letter-spacing: 0.3px;
}

.wo-bike {
  font-size: 14px;
  font-weight: 700;
  color: #2f3b22;
  margin-top: 6px;
}

.wo-fault {
  font-size: 12px;
  color: #6f7f62;
  margin-top: 2px;
}

.wo-meta {
  margin: 8px 0 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.wo-meta div {
  display: flex;
  gap: 6px;
  font-size: 11px;
}

.wo-meta dt {
  color: #a5b09b;
  flex: 0 0 40px;
}

.wo-meta dd {
  margin: 0;
  color: #6f7f62;
  word-break: break-all;
}

.wo-act {
  margin-top: 10px;
}

.wo-done {
  font-size: 11px;
  color: #a5b09b;
}

.rep-alert {
  margin-bottom: 14px;
}

.assign-info {
  font-size: 13px;
  color: #4b6b32;
  margin-bottom: 12px;
}

.assign-tip {
  font-size: 11px;
  color: #a5b09b;
  line-height: 1.7;
  margin: 6px 0 0;
}
</style>
