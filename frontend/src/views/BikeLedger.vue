<template>
  <div class="page">
    <!-- 工具条：这一页不摆表格，车按状态分四列摆成卡片墙 -->
    <div class="bar">
      <div class="bar-left">
        <h2>车辆台账</h2>
        <span class="tip">四列 = 四个状态，卡片上有快捷动作按钮；点卡片开右侧抽屉改局部字段</span>
      </div>
      <div class="bar-right">
        <el-input
          v-model="keyword"
          class="kw"
          placeholder="按车辆编号 / 车型过滤"
          clearable
          @keyup.enter="load"
          @clear="load"
        />
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">+ 新车上账</el-button>
      </div>
    </div>

    <div v-if="focusCode" class="focus">
      正在直查车辆 <b>{{ focusCode }}</b>
      <el-button link type="primary" @click="clearFocus">取消高亮</el-button>
    </div>

    <div class="board" v-loading="loading">
      <section v-for="col in columns" :key="col.value" class="col">
        <header class="col-head" :class="'col-head--' + col.value.toLowerCase()">
          <span class="col-name">{{ col.label }}</span>
          <span class="col-count">{{ grouped[col.value] ? grouped[col.value].length : 0 }}</span>
        </header>
        <div class="col-body">
          <el-empty v-if="!grouped[col.value] || grouped[col.value].length === 0" description="这一列没有车" :image-size="60" />
          <article
            v-for="bike in grouped[col.value]"
            :key="bike.id"
            class="bike-card"
            :class="[`st-${bike.status.toLowerCase()}`, { 'bike-card--focus': bike.code === focusCode }]"
            @click="openEdit(bike)"
          >
            <div class="bc-top">
              <span class="bc-code">{{ bike.code }}</span>
              <el-tag size="small" :type="tagType(bike.status)" effect="plain">{{ bike.statusName }}</el-tag>
            </div>
            <div class="bc-model">{{ bike.model }}</div>
            <div class="bc-loc">
              <template v-if="bike.pileNo">
                停在 {{ bike.stationName }} · {{ bike.pileNo }}
              </template>
              <template v-else-if="bike.stationName">在 {{ bike.stationName }} 维修区（未占桩）</template>
              <template v-else>不在桩位上</template>
            </div>
            <div class="bc-foot">
              <span class="bc-rides">累计骑行 {{ bike.totalRides }} 次</span>
              <div class="bc-acts" @click.stop>
                <el-button v-if="bike.status === 'PILE'" size="small" @click="sendRepair(bike)">送修</el-button>
                <el-button v-if="bike.status === 'REPAIR'" size="small" type="primary" @click="openEdit(bike)">回桩</el-button>
                <el-button
                  v-if="bike.status !== 'SCRAPPED'"
                  size="small"
                  type="danger"
                  plain
                  @click="scrap(bike)"
                >报废</el-button>
                <span v-if="bike.status === 'SCRAPPED'" class="bc-scrap">{{ bike.scrapReason || '已报废' }}</span>
                <span v-if="bike.status === 'RIDING'" class="bc-hint">骑行中 · 去「租还与计费」页还车</span>
              </div>
            </div>
          </article>
        </div>
      </section>
    </div>

    <!-- 新车上账 -->
    <el-dialog v-model="createVisible" title="新车上账" width="520">
      <el-form label-width="88px">
        <el-form-item label="车辆编号">
          <el-input v-model="createForm.code" placeholder="BK-1001（BK- 加 4 位数字，全仓唯一）" />
        </el-form-item>
        <el-form-item label="车型">
          <el-input v-model="createForm.model" placeholder="例如 青桔经典款" />
        </el-form-item>
        <el-form-item label="直接上桩">
          <el-select v-model="createForm.pileId" clearable placeholder="不选就先不上桩（状态仍是在桩，桩位为空）" style="width: 100%">
            <el-option
              v-for="p in freePiles"
              :key="p.pileId"
              :label="`${p.stationName} · ${p.pileNo}`"
              :value="p.pileId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">入账</el-button>
      </template>
    </el-dialog>

    <!-- 车辆详情 / 局部更新 -->
    <el-drawer v-model="editVisible" :title="`车辆 ${editForm.code || ''}`" size="440px">
      <el-form label-width="88px">
        <el-form-item label="车型">
          <el-input v-model="editForm.model" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status" style="width: 100%">
            <el-option label="在桩" value="PILE" />
            <el-option label="维修中" value="REPAIR" />
            <el-option label="骑行中（由借车产生）" value="RIDING" disabled />
            <el-option label="报废（终态）" value="SCRAPPED" disabled />
          </el-select>
          <div class="form-tip">
            只能做「在桩 ↔ 维修中」的人工迁移；「骑行中」由借车产生、「报废」请用卡片上的报废按钮。
          </div>
        </el-form-item>
        <el-form-item v-if="editForm.status === 'PILE'" label="桩位">
          <el-select v-model="editForm.pileId" clearable placeholder="选一个空闲桩位" style="width: 100%">
            <el-option
              v-for="p in freePiles"
              :key="p.pileId"
              :label="`${p.stationName} · ${p.pileNo}`"
              :value="p.pileId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.scrapReason" type="textarea" :rows="2" placeholder="报废原因 / 备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存（局部更新）</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bikeApi, stationApi } from '../api'

const columns = [
  { value: 'PILE', label: '在桩' },
  { value: 'RIDING', label: '骑行中' },
  { value: 'REPAIR', label: '维修中' },
  { value: 'SCRAPPED', label: '报废（终态）' }
]

const route = useRoute()
const loading = ref(false)
const keyword = ref('')
const bikes = ref([])
const stations = ref([])

const createVisible = ref(false)
const createForm = reactive({ code: '', model: '', pileId: null })

const editVisible = ref(false)
const editForm = reactive({ id: null, code: '', model: '', status: 'PILE', pileId: null, scrapReason: '' })

const focusCode = ref((route.query.code || '').toString().toUpperCase())

const grouped = computed(() => {
  const map = {}
  for (const col of columns) map[col.value] = []
  for (const b of bikes.value) {
    if (map[b.status]) map[b.status].push(b)
  }
  return map
})

/** 所有空闲桩位，摊平成「站点 · 桩位」供下拉选 */
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

function tagType(status) {
  if (status === 'PILE') return 'success'
  if (status === 'RIDING') return 'primary'
  if (status === 'REPAIR') return 'warning'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    bikes.value = await bikeApi.list({ keyword: keyword.value || undefined })
    stations.value = await stationApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.code = ''
  createForm.model = ''
  createForm.pileId = null
  createVisible.value = true
}

async function submitCreate() {
  try {
    await bikeApi.create({
      code: createForm.code,
      model: createForm.model,
      pileId: createForm.pileId || null
    })
  } catch (e) {
    return
  }
  ElMessage.success('车辆已入账')
  createVisible.value = false
  await load()
}

function openEdit(bike) {
  editForm.id = bike.id
  editForm.code = bike.code
  editForm.model = bike.model
  editForm.status = bike.status
  editForm.pileId = bike.pileId
  editForm.scrapReason = bike.scrapReason
  editVisible.value = true
}

async function submitEdit() {
  if (editForm.status === 'RIDING' || editForm.status === 'SCRAPPED') {
    ElMessage.warning('「骑行中」「报废」这两个状态不在这里改')
    return
  }
  try {
    await bikeApi.update(editForm.id, {
      model: editForm.model,
      status: editForm.status,
      pileId: editForm.status === 'PILE' ? editForm.pileId : null,
      scrapReason: editForm.scrapReason
    })
  } catch (e) {
    return
  }
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
}

async function sendRepair(bike) {
  try {
    await ElMessageBox.confirm(`把 ${bike.code} 转为「维修中」？转维修会释放它占的桩位。`, '送修', { type: 'warning' })
  } catch (e) {
    return
  }
  await bikeApi.update(bike.id, { status: 'REPAIR' })
  ElMessage.success(`${bike.code} 已转维修中`)
  await load()
}

async function scrap(bike) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `报废是不可逆的终态：${bike.code} 报废后不能再改状态、不能再上桩。请填写报废原因。`,
      '确认报废',
      { inputPlaceholder: '例如 车架断裂，判定报废', inputValidator: (v) => (v && v.trim() ? true : '报废原因不能为空') }
    )
    reason = value.trim()
  } catch (e) {
    return
  }
  await bikeApi.scrap(bike.id, reason)
  ElMessage.success(`${bike.code} 已报废`)
  await load()
}

function clearFocus() {
  focusCode.value = ''
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

.kw {
  width: 240px;
}

.focus {
  background: var(--el-color-primary-light-9);
  border: 1px dashed var(--el-color-primary-light-3);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  color: #4b6b32;
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

.col-head--pile { background: #558b2f; }
.col-head--riding { background: #4a7fb5; }
.col-head--repair { background: #c98a1b; }
.col-head--scrapped { background: #8d8d8d; }

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

.bike-card {
  background: #fff;
  border-radius: 10px;
  padding: 10px 12px;
  border-left: 5px solid var(--el-color-primary);
  box-shadow: 0 1px 4px rgba(15, 30, 10, 0.07);
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.bike-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 30, 10, 0.14);
}

.bike-card--focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.st-riding { border-left-color: #4a7fb5; }
.st-repair { border-left-color: #c98a1b; }
.st-scrapped { border-left-color: #8d8d8d; opacity: 0.85; }

.bc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.bc-code {
  font-weight: 700;
  font-size: 14px;
  color: #2f3b22;
  letter-spacing: 0.4px;
}

.bc-model {
  font-size: 12px;
  color: #6f7f62;
  margin-top: 3px;
}

.bc-loc {
  font-size: 12px;
  color: #8b9a7c;
  margin-top: 6px;
}

.bc-foot {
  margin-top: 9px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bc-rides {
  font-size: 11px;
  color: #a5b09b;
}

.bc-acts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.bc-acts :deep(.el-button + .el-button) {
  margin-left: 0;
}

.bc-scrap,
.bc-hint {
  font-size: 11px;
  color: #8d8d8d;
}

.form-tip {
  font-size: 11px;
  color: #a5b09b;
  line-height: 1.5;
  margin-top: 2px;
}
</style>
