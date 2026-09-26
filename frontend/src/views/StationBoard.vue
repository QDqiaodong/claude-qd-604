<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>站点与桩位</h2>
        <span class="tip">
          站点是卡片、桩位是格子、车是格子里可以<b>拖动</b>的小方块 ——
          把车从 A 站拖到 B 站的空格子里就是一次调拨
        </span>
      </div>
      <div class="bar-right">
        <el-input v-model="keyword" class="kw" placeholder="按站点编号 / 名称 / 行政区过滤" clearable @keyup.enter="load" @clear="load" />
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="createVisible = true">+ 开通新站点</el-button>
      </div>
    </div>

    <div class="hint">
      <span class="legend"><i class="dot dot--open"></i>运营中</span>
      <span class="legend"><i class="dot dot--closed"></i>已停用（不能借还、不能调入）</span>
      <span class="legend"><i class="dot dot--disabled"></i>桩位停用</span>
      <span class="legend"><i class="dot dot--drag"></i>可拖动</span>
      <span v-if="dragging" class="dragging">
        正在拖 <b>{{ dragging.bikeCode }}</b>（来自 {{ dragging.fromPileNo }}）—— 松手放到目标格子里
      </span>
    </div>

    <div class="grid" v-loading="loading">
      <section
        v-for="st in stations"
        :key="st.id"
        class="station"
        :class="[st.status === 'CLOSED' ? 'station--closed' : '', { 'station--focus': st.code === focusCode, 'station--over': overStationId === st.id }]"
        @dragover.prevent="onDragOverStation(st)"
        @dragleave="onDragLeaveStation(st)"
        @drop.prevent="dropOnStation(st)"
      >
        <header class="st-head">
          <div class="st-title">
            <span class="st-code">{{ st.code }}</span>
            <span class="st-name">{{ st.name }}</span>
          </div>
          <el-tag size="small" :type="st.status === 'OPEN' ? 'success' : 'info'" effect="dark">
            {{ st.statusName }}
          </el-tag>
        </header>

        <div class="st-meta">
          <span>{{ st.district }}</span>
          <span class="st-cap">
            车辆 {{ st.bikeCount }} / 容量 {{ st.capacity }}
            <em>· 空桩 {{ st.freePileCount }}</em>
          </span>
        </div>

        <div v-if="st.activeClearance || st.lastClearance" class="clearance-row">
          <el-tag
            v-if="st.activeClearance"
            size="small"
            :type="st.activeClearance.blockedCount > 0 ? 'danger' : 'warning'"
            effect="dark"
            class="clear-tag"
            @click="goClearance(st.activeClearance.clearanceId)"
          >
            🚚 正在清站 {{ st.activeClearance.migratedCount }}/{{ st.activeClearance.totalCount }}
            <template v-if="st.activeClearance.blockedCount > 0"> · 受阻 {{ st.activeClearance.blockedCount }}</template>
            <span class="clear-link">查看</span>
          </el-tag>
          <el-tag
            v-else-if="st.lastClearance && st.status === 'CLOSED'"
            size="small"
            type="success"
            effect="plain"
            class="clear-tag"
            @click="goClearance(st.lastClearance.clearanceId)"
          >
            ✅ 清站完成（{{ st.lastClearance.orderNo }}）<span class="clear-link">查看</span>
          </el-tag>
        </div>

        <div class="cap-bar">
          <div class="cap-fill" :style="{ width: capPercent(st) + '%' }"></div>
        </div>

        <div class="piles">
          <div
            v-for="pile in st.piles"
            :key="pile.id"
            class="pile"
            :class="{
              'pile--idle': pile.status === 'IDLE',
              'pile--occupied': pile.status === 'OCCUPIED',
              'pile--disabled': pile.status === 'DISABLED',
              'pile--over': overPileId === pile.id
            }"
            @dragover.prevent="onDragOverPile(pile)"
            @dragleave="onDragLeavePile(pile)"
            @drop.prevent="dropOnPile(st, pile)"
          >
            <span class="pile-no">{{ pile.pileNo }}</span>

            <div
              v-if="pile.bikeId"
              class="bike-chip"
              draggable="true"
              :class="{ 'bike-chip--dragging': dragging && dragging.bikeId === pile.bikeId }"
              @dragstart="onDragStart(pile, $event)"
              @dragend="onDragEnd"
              @click.stop="pickBike(pile)"
            >
              <span class="chip-code">{{ pile.bikeCode }}</span>
              <span class="chip-model">{{ pile.bikeModel }}</span>
            </div>

            <span v-else-if="pile.status === 'DISABLED'" class="pile-empty">桩位停用</span>
            <span v-else class="pile-empty">空桩</span>
          </div>
        </div>

        <footer class="st-foot">
          <el-button size="small" @click="addPile(st)">加桩位</el-button>
          <el-button size="small" @click="changeCapacity(st)">改容量</el-button>
          <el-button
            size="small"
            :type="st.status === 'OPEN' ? 'warning' : 'success'"
            plain
            @click="toggleStatus(st)"
          >{{ st.status === 'OPEN' ? '停用' : '启用' }}</el-button>
        </footer>
      </section>
    </div>

    <el-dialog v-model="createVisible" title="开通新站点" width="520">
      <el-form label-width="96px">
        <el-form-item label="站点编号">
          <el-input v-model="createForm.code" placeholder="S006（S 加 3 位数字，全仓唯一）" />
        </el-form-item>
        <el-form-item label="站点名称">
          <el-input v-model="createForm.name" placeholder="例如 学院路驿站" />
        </el-form-item>
        <el-form-item label="行政区">
          <el-input v-model="createForm.district" placeholder="例如 海淀区" />
        </el-form-item>
        <el-form-item label="容量">
          <el-input-number v-model="createForm.capacity" :min="1" :max="60" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">开通</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pickVisible" title="这辆车要去哪儿" width="440" align-center>
      <p class="pick-line" v-if="picked">
        车辆 <b>{{ picked.bikeCode }}</b> 当前停在 <b>{{ picked.stationName }} · {{ picked.pileNo }}</b>
      </p>
      <p class="pick-tip">拖动不方便时，也可以在这里用下拉框选目标桩位完成调拨。</p>
      <el-select v-model="pickedTarget" clearable placeholder="选目标桩位" style="width: 100%">
        <el-option
          v-for="p in otherPiles"
          :key="p.id"
          :label="`${p.stationName} · ${p.pileNo}`"
          :value="p.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="pickVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmPick">调拨过去</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { stationApi } from '../api'

const route = useRoute()
const nav = useRouter()
const loading = ref(false)
const keyword = ref('')
const stations = ref([])

const dragging = ref(null)
const overStationId = ref(null)
const overPileId = ref(null)

const createVisible = ref(false)
const createForm = reactive({ code: '', name: '', district: '', capacity: 6 })

const pickVisible = ref(false)
const picked = ref(null)
const pickedTarget = ref(null)

const focusCode = ref((route.query.code || '').toString().toUpperCase())

const otherPiles = computed(() => {
  if (!picked.value) return []
  const out = []
  for (const st of stations.value) {
    if (st.status !== 'OPEN') continue
    for (const p of st.piles) {
      if (p.status === 'DISABLED') continue
      if (p.id === picked.value.pileId) continue
      if (p.bikeId && p.bikeId !== picked.value.bikeId) continue
      out.push({ id: p.id, pileNo: p.pileNo, stationName: st.name })
    }
  }
  return out
})

function capPercent(st) {
  if (!st.capacity) return 0
  return Math.min(100, Math.round((st.bikeCount / st.capacity) * 100))
}

/** 跳到应急清站模块并打开对应清站单 */
function goClearance(id) {
  nav.push({ path: '/clearances', query: { id: String(id) } })
}

async function load() {
  loading.value = true
  try {
    stations.value = await stationApi.list({ keyword: keyword.value || undefined })
  } finally {
    loading.value = false
  }
}

// ---------------- 拖拽 ----------------

function onDragStart(pile, ev) {
  dragging.value = { bikeId: pile.bikeId, bikeCode: pile.bikeCode, fromPileId: pile.id, fromPileNo: pile.pileNo }
  ev.dataTransfer.effectAllowed = 'move'
  ev.dataTransfer.setData('text/plain', String(pile.bikeId))
}

function onDragEnd() {
  dragging.value = null
  overStationId.value = null
  overPileId.value = null
}

function onDragOverPile(pile) {
  if (dragging.value) {
    overPileId.value = pile.id
    overStationId.value = null
  }
}

function onDragLeavePile(pile) {
  if (overPileId.value === pile.id) overPileId.value = null
}

function onDragOverStation(st) {
  if (dragging.value) overStationId.value = st.id
}

function onDragLeaveStation(st) {
  if (overStationId.value === st.id) overStationId.value = null
}

async function dropOnPile(st, pile) {
  const moving = dragging.value
  overPileId.value = null
  overStationId.value = null
  dragging.value = null
  if (!moving) return
  if (pile.status === 'DISABLED') {
    ElMessage.warning(`桩位 ${pile.pileNo} 已停用，不能放车`)
    return
  }
  await doTransfer(moving.bikeId, pile.id)
}

/** 把车丢在站点卡片上（没瞄准具体格子）→ 后端自动挑该站第一个空桩 */
async function dropOnStation(st) {
  const moving = dragging.value
  overStationId.value = null
  dragging.value = null
  if (!moving) return
  await doTransfer(moving.bikeId, null, st.id)
}

async function doTransfer(bikeId, toPileId, toStationId) {
  try {
    const res = await stationApi.transfer(bikeId, toPileId || null, toStationId || null)
    ElMessage.success(res.message)
    await load()
  } catch (e) {
    /* 后端已经弹了中文业务提示，这里只需要保证卡片回到原样 */
    await load()
  }
}

function pickBike(pile) {
  picked.value = {
    bikeId: pile.bikeId,
    bikeCode: pile.bikeCode,
    pileId: pile.id,
    pileNo: pile.pileNo,
    stationName: stations.value.find((s) => s.piles.some((p) => p.id === pile.id))?.name || ''
  }
  pickedTarget.value = null
  pickVisible.value = true
}

async function confirmPick() {
  if (!pickedTarget.value) {
    ElMessage.warning('请先选一个目标桩位')
    return
  }
  const bikeId = picked.value.bikeId
  await doTransfer(bikeId, pickedTarget.value, null)
  pickVisible.value = false
}

// ---------------- 站点维护 ----------------

async function submitCreate() {
  try {
    await stationApi.create({
      code: createForm.code,
      name: createForm.name,
      district: createForm.district,
      capacity: createForm.capacity
    })
  } catch (e) {
    return
  }
  ElMessage.success('站点已开通')
  createVisible.value = false
  await load()
}

async function addPile(st) {
  let pileNo = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `给「${st.name}」加一个桩位。桩位号在站内唯一（当前已有 ${st.piles.length} 个桩位，站点容量 ${st.capacity} 辆）。`,
      '加桩位',
      { inputPlaceholder: '例如 P7', inputValidator: (v) => (v && v.trim() ? true : '桩位号不能为空') }
    )
    pileNo = value.trim().toUpperCase()
  } catch (e) {
    return
  }
  await stationApi.addPile(st.id, pileNo)
  ElMessage.success('桩位已新增')
  await load()
}

async function changeCapacity(st) {
  let capacity = null
  try {
    const { value } = await ElMessageBox.prompt(
      `改「${st.name}」的容量。容量不能低于站内当前车辆数 ${st.bikeCount}。`,
      '改容量',
      { inputValue: String(st.capacity), inputValidator: (v) => (/^\d+$/.test((v || '').trim()) ? true : '请输入正整数') }
    )
    capacity = Number(value.trim())
  } catch (e) {
    return
  }
  await stationApi.setCapacity(st.id, capacity)
  ElMessage.success('容量已调整')
  await load()
}

async function toggleStatus(st) {
  const target = st.status === 'OPEN' ? 'CLOSED' : 'OPEN'
  if (target === 'CLOSED') {
    try {
      await ElMessageBox.confirm(
        `停用「${st.name}」后，这个站点不能借车、不能还车、也不能调拨进来（站内已停的车不受影响）。`,
        '停用站点',
        { type: 'warning' }
      )
    } catch (e) {
      return
    }
  }
  await stationApi.setStatus(st.id, target)
  ElMessage.success(target === 'OPEN' ? '站点已启用' : '站点已停用')
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

.bar h2 + .tip {
  display: block;
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

.kw {
  width: 230px;
}

.hint {
  display: flex;
  align-items: center;
  gap: 18px;
  font-size: 12px;
  color: #6f7f62;
  background: #fff;
  border-radius: 8px;
  padding: 8px 14px;
  flex-wrap: wrap;
}

.legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.dot--open { background: var(--el-color-primary); }
.dot--closed { background: #b6bdb0; }
.dot--disabled { background: #d9534f; }
.dot--drag { background: #4a7fb5; }

.dragging {
  color: var(--el-color-primary-dark-2);
  background: var(--el-color-primary-light-9);
  border-radius: 999px;
  padding: 3px 12px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 14px;
}

.station {
  background: #fff;
  border-radius: 14px;
  padding: 14px;
  border-top: 4px solid var(--el-color-primary);
  box-shadow: 0 2px 10px rgba(15, 30, 10, 0.07);
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.station--over {
  box-shadow: 0 0 0 3px var(--el-color-primary-light-5), 0 6px 18px rgba(85, 139, 47, 0.28);
  transform: translateY(-2px);
}

.station--closed {
  border-top-color: #b6bdb0;
  background: #fafafa;
}

.station--closed .piles {
  opacity: 0.72;
}

.station--focus {
  outline: 2px dashed var(--el-color-primary);
  outline-offset: 3px;
}

.st-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.st-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.st-code {
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 6px;
  padding: 2px 7px;
  letter-spacing: 0.5px;
}

.station--closed .st-code {
  background: #b6bdb0;
}

.st-name {
  font-size: 15px;
  font-weight: 700;
  color: #2f3b22;
}

.st-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #8b9a7c;
  margin-top: 8px;
}

.st-cap em {
  font-style: normal;
  color: var(--el-color-primary);
}

.clearance-row {
  margin-top: 8px;
  display: flex;
}

.clear-tag {
  cursor: pointer;
}

.clear-link {
  text-decoration: underline;
  margin-left: 4px;
  opacity: 0.85;
}

.cap-bar {
  height: 5px;
  border-radius: 999px;
  background: #eef1ec;
  margin: 7px 0 11px;
  overflow: hidden;
}

.cap-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--el-color-primary-light-3), var(--el-color-primary));
}

.piles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
}

.pile {
  border-radius: 10px;
  padding: 7px 8px;
  min-height: 74px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  border: 1.5px dashed #cfd8c7;
  background: #fafcf8;
  transition: all 0.14s ease;
}

.pile--idle {
  border-color: var(--el-color-primary-light-5);
}

.pile--occupied {
  border-style: solid;
  border-color: var(--el-color-primary-light-7);
  background: #fff;
}

.pile--disabled {
  border-color: #e6b3b1;
  background: #fdf3f3;
}

.pile--over {
  border-color: var(--el-color-primary);
  border-style: solid;
  background: var(--el-color-primary-light-9);
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-5);
}

.pile-no {
  font-size: 11px;
  font-weight: 700;
  color: #93a386;
  letter-spacing: 0.4px;
}

.pile-empty {
  font-size: 11px;
  color: #b3bdab;
  margin: auto 0;
  text-align: center;
}

.bike-chip {
  margin-top: auto;
  background: linear-gradient(160deg, #ffffff, var(--el-color-primary-light-9));
  border: 1px solid var(--el-color-primary-light-5);
  border-left: 4px solid var(--el-color-primary);
  border-radius: 8px;
  padding: 5px 7px;
  cursor: grab;
  display: flex;
  flex-direction: column;
  gap: 1px;
  user-select: none;
}

.bike-chip:active {
  cursor: grabbing;
}

.bike-chip--dragging {
  opacity: 0.4;
  transform: scale(0.96);
}

.chip-code {
  font-size: 12px;
  font-weight: 700;
  color: var(--el-color-primary-dark-2);
}

.chip-model {
  font-size: 10px;
  color: #8b9a7c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.st-foot {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e3e8de;
}

.st-foot :deep(.el-button + .el-button) {
  margin-left: 0;
}

.pick-line {
  margin: 0 0 6px;
  font-size: 13px;
  color: #4b6b32;
}

.pick-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: #93a386;
}
</style>
