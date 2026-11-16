<template>
  <div>
    <!-- 通知列表与评估触发 -->
    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>供应商维修通知（供应商条件 + 目标时间窗）</b>
          <el-tag type="info" size="small">产出的是“通知资格”，不是受影响快照；不会把历史快照里出现过的设备全部通知</el-tag>
        </div>
      </template>
      <el-table :data="notices" stripe size="small" @row-click="selectNotice" highlight-current-row>
        <el-table-column prop="notice_code" label="通知号" width="150" class-name="mono" />
        <el-table-column prop="title" label="维修通知" min-width="220" />
        <el-table-column prop="supplier_name" label="供应商" width="180" />
        <el-table-column prop="target_model_code" label="目标型号" width="150" class-name="mono" />
        <el-table-column label="目标时间窗" width="320">
          <template #default="{ row }">
            <span class="mono">{{ fmt(row.window_start) }} ~ {{ fmt(row.window_end) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <el-button size="small" type="primary" :disabled="!auth.canWrite" @click.stop="evaluate(row, null)">
              全量评估
            </el-button>
            <el-button size="small" :disabled="!auth.canWrite" @click.stop="openBoundary(row)">
              按认知边界评估
            </el-button>
            <el-button size="small" @click.stop="loadRuns(row)">查看评估批次</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 认知边界（模拟迟到凭证未接收）对话框 -->
    <el-dialog v-model="boundaryDlg" title="按认知边界评估（迟到凭证不参与，只新增补充批次）" width="460px">
      <el-alert type="warning" :closable="false" class="tip"
        title="历史评估批次保留不改写；纳入迟到凭证后再次评估，只会产生补充名单。" />
      <el-date-picker v-model="asOf" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        placeholder="选择认知边界（留空=当前全部已知）" style="width:100%" />
      <template #footer>
        <el-button @click="boundaryDlg = false">取消</el-button>
        <el-button type="primary" @click="confirmBoundary">开始评估</el-button>
      </template>
    </el-dialog>

    <!-- 即时试算（不落库） -->
    <el-card class="page-card">
      <template #header><b>即时试算（不落库，直接给供应商条件 + 时间窗）</b></template>
      <el-form :inline="true">
        <el-form-item label="目标型号">
          <el-select v-model="adhoc.targetModelId" placeholder="选择部件型号" style="width:240px" filterable>
            <el-option v-for="m in models" :key="m.id"
              :label="`${m.entity_code} ${m.display_name}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商（可选）">
          <el-select v-model="adhoc.supplierId" placeholder="全部供应商" clearable style="width:200px">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.display_name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间窗">
          <el-date-picker v-model="adhoc.range" type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss[Z]" start-placeholder="窗起" end-placeholder="窗止" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="adhocLoading" @click="runAdhoc">试算资格</el-button>
        </el-form-item>
      </el-form>
      <div v-if="adhocResult">
        <el-row :gutter="12" class="stat-row">
          <el-col :span="4" v-for="q in qualOrder" :key="q">
            <el-card shadow="never" class="mini-stat">
              <el-tag :type="qualType(q)" size="small">{{ qualText(q) }}</el-tag>
              <div class="mini-num">{{ adhocResult.counts[q] || 0 }}</div>
            </el-card>
          </el-col>
        </el-row>
        <el-table :data="adhocRows" stripe size="small" class="tip">
          <el-table-column prop="equipmentId" label="设备ID" width="100" />
          <el-table-column label="资格" width="120">
            <template #default="{ row }">
              <el-tag :type="qualType(row.qualification)" size="small">{{ row.qualificationLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reasoning" label="资格解释" min-width="320" show-overflow-tooltip />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" @click="openAdhocDetail(row)">证据</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 评估批次列表 -->
    <el-card v-if="runs.length" class="page-card">
      <template #header><b>评估批次（每次评估独立留痕，历史批次不可变）— {{ currentNotice?.notice_code }}</b></template>
      <el-table :data="runs" stripe size="small">
        <el-table-column prop="run_no" label="批次号" width="170" class-name="mono" />
        <el-table-column label="认知边界" width="200">
          <template #default="{ row }">
            <el-tag size="small" :type="row.as_of_received ? 'warning' : 'success'">
              {{ row.as_of_received ? fmt(row.as_of_received) : '全部已知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="必然" width="80"><template #default="{ row }">{{ row.certain_count }}</template></el-table-column>
        <el-table-column label="可能" width="80"><template #default="{ row }">{{ row.possible_count }}</template></el-table-column>
        <el-table-column label="证据不足" width="90"><template #default="{ row }">{{ row.insufficient_count }}</template></el-table-column>
        <el-table-column label="可排除" width="80"><template #default="{ row }">{{ row.excluded_count }}</template></el-table-column>
        <el-table-column label="待核" width="80">
          <template #default="{ row }"><el-tag type="danger" size="small">{{ row.review_count }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openRun(row)">设备集合/证据</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 批次详情抽屉：四档集合 + 最小支持材料 -->
    <el-drawer v-model="runDrawer" size="72%" :title="`通知资格设备集合 ${runDetail?.run?.run_no || ''}`">
      <div v-if="runDetail">
        <el-descriptions :column="3" border size="small" class="tip">
          <el-descriptions-item label="评估指纹"><span class="mono">{{ runDetail.run.run_digest }}</span></el-descriptions-item>
          <el-descriptions-item label="认知边界">
            {{ runDetail.run.as_of_received ? fmt(runDetail.run.as_of_received) : '当前全部已知凭证' }}
          </el-descriptions-item>
          <el-descriptions-item label="论证边数">{{ runDetail.edges.length }}</el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="activeTab" class="tip">
          <el-tab-pane v-for="q in qualOrder" :key="q"
            :name="q" :label="`${qualText(q)}（${grouped[q]?.length || 0}）`">
            <el-table :data="grouped[q] || []" stripe size="small">
              <el-table-column prop="equipment_code" label="设备" width="180" class-name="mono" />
              <el-table-column prop="equipment_name" label="名称" width="160" />
              <el-table-column label="必要安装区间（窗内交集）" min-width="240">
                <template #default="{ row }">
                  <span v-if="!row.necessary_intervals.length" class="muted">—</span>
                  <el-tag v-for="(iv, i) in row.necessary_intervals" :key="'n'+i" type="success" size="small" class="iv-tag">
                    {{ fmt(iv.start) }} → {{ iv.openEnd ? '开放端' : fmt(iv.end) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="可能安装区间（窗内交集）" min-width="240">
                <template #default="{ row }">
                  <span v-if="!row.possible_intervals.length" class="muted">—</span>
                  <el-tag v-for="(iv, i) in row.possible_intervals" :key="'p'+i" type="warning" size="small" class="iv-tag">
                    {{ fmt(iv.start) }} → {{ iv.openEnd ? '开放端' : fmt(iv.end) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reasoning" label="资格解释" min-width="280" show-overflow-tooltip />
              <el-table-column label="证据" width="90">
                <template #default="{ row }">
                  <el-button size="small" @click="openEligibility(row, runDetail.supportPaths, runDetail.edges)">
                    最小材料
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <!-- 最小支持材料 / 论证图抽屉 -->
    <el-drawer v-model="evDrawer" size="56%" :title="`最小支持材料 — ${evTarget?.equipment_code || '试算设备 ' + evTarget?.equipmentId}`">
      <div v-if="evTarget">
        <el-alert v-if="evTarget.contradictions?.length" type="error" :closable="false" class="tip"
          title="证据相互矛盾，该设备只能待核，不纳入必然通知名单" />
        <div v-for="(c, i) in evTarget.contradictions || []" :key="'c'+i" class="contradiction">
          <div class="ct-title">矛盾 {{ i + 1 }}：{{ c.detail }}</div>
          <div v-if="c.presence" class="ct-line">在装主张：{{ evidenceLabel(c.presence) }}</div>
          <div v-if="c.absence" class="ct-line">相反凭证：{{ evidenceLabel(c.absence) }}</div>
        </div>

        <h4>最小支持材料（独立支持路径上的凭证）</h4>
        <el-table :data="evTarget.minimal_evidence" size="small" border>
          <el-table-column label="凭证类型" width="130">
            <template #default="{ row }">
              <el-tag size="small">{{ evidenceNodeText(row.nodeType) }}</el-tag>
              <el-tag v-if="row.assemblyDerived" type="warning" size="small" style="margin-left:4px">总成派生</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceRef" label="来源/凭证号" min-width="200" class-name="mono" show-overflow-tooltip />
          <el-table-column label="断言区间" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ fmt(row.claimFrom) }} → {{ openEnd(row.claimTo) ? '开放端' : fmt(row.claimTo) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="确定性" width="100">
            <template #default="{ row }">
              <el-tag :type="row.certainty === 'NECESSARY' ? 'success' : 'warning'" size="small">
                {{ row.certainty === 'NECESSARY' ? '必要' : '可能' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="trustScore" label="可信度" width="80" />
        </el-table>

        <h4>独立支持路径（一条归属结论至少一条独立路径）</h4>
        <el-timeline v-if="evPaths.length">
          <el-timeline-item v-for="(p, i) in evPaths" :key="i" :timestamp="p.path_no" placement="top" type="primary">
            <div class="mono path-line">{{ (p.node_codes || []).join('  →  ') }}</div>
            <div class="muted">{{ p.path_summary }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-alert v-else type="info" :closable="false" title="该结论无独立支持路径（证据不足/可排除时可能为空）" />

        <template v-if="evEdges.length">
          <h4>论证边（总成派生关系，内部件随父总成转装、不复制为两处）</h4>
          <el-table :data="evEdges" size="small" border>
            <el-table-column prop="from_code" label="父/源节点" min-width="220" class-name="mono" show-overflow-tooltip />
            <el-table-column label="关系" width="140">
              <template #default="{ row }">
                <el-tag size="small" type="warning">{{ edgeText(row.edge_type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="to_code" label="子/目标节点" min-width="220" class-name="mono" show-overflow-tooltip />
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { qualText, qualType, evidenceNodeText } from '../utils/status'

const auth = useAuthStore()
const qualOrder = ['CERTAIN', 'POSSIBLE', 'INSUFFICIENT', 'EXCLUDED', 'PENDING_REVIEW']

const notices = ref([])
const models = ref([])
const suppliers = ref([])
const runs = ref([])
const currentNotice = ref(null)
const boundaryDlg = ref(false)
const asOf = ref('')
const runDrawer = ref(false)
const activeTab = ref('CERTAIN')
const runDetail = ref(null)
const evDrawer = ref(false)
const evTarget = ref(null)
const evPaths = ref([])
const evEdges = ref([])

const adhoc = reactive({ targetModelId: null, supplierId: null, range: [] })
const adhocLoading = ref(false)
const adhocResult = ref(null)
const adhocRows = computed(() => adhocResult.value?.eligibilities || [])

const grouped = computed(() => {
  const g = {}
  for (const q of qualOrder) g[q] = []
  for (const el of runDetail.value?.eligibilities || []) {
    ;(g[el.qualification] || (g[el.qualification] = [])).push(el)
  }
  return g
})

const fmt = (v) => v ? String(v).replace('T', ' ').replace('Z', '').slice(0, 16) : ''
const openEnd = (v) => !v || String(v).startsWith('2099')
const edgeText = (t) => ({ SUPPORTS: '支持', CONTRADICTS: '矛盾', DERIVED_ASSEMBLY: '总成派生', BOUNDS: '界定边界' }[t] || t)
function evidenceLabel(a) {
  return `${evidenceNodeText(a.nodeType)} ${a.sourceRef || ''}（${fmt(a.claimFrom)}，${a.observed === 'ABSENT' ? '缺席' : '在装'}）`
}

async function load() {
  notices.value = (await http.get('/maintenance/notices')).data
  models.value = (await http.get('/entities', { params: { entityType: 'PART_MODEL' } })).data
  suppliers.value = (await http.get('/entities', { params: { entityType: 'SUPPLIER' } })).data
}
function selectNotice(row) { currentNotice.value = row }

async function evaluate(row, boundary) {
  const body = boundary ? { asOfReceived: boundary } : {}
  const r = await http.post(`/maintenance/notices/${row.id}/evaluate`, body)
  ElMessage.success(`已生成评估批次 ${r.data.run.run_no}`)
  currentNotice.value = row
  await loadRuns(row)
  runDetail.value = r.data
  activeTab.value = 'CERTAIN'
  runDrawer.value = true
}
function openBoundary(row) { currentNotice.value = row; asOf.value = ''; boundaryDlg.value = true }
function confirmBoundary() {
  boundaryDlg.value = false
  evaluate(currentNotice.value, asOf.value || null)
}
async function loadRuns(row) {
  currentNotice.value = row
  runs.value = (await http.get(`/maintenance/notices/${row.id}/runs`)).data
}
async function openRun(row) {
  runDetail.value = (await http.get(`/maintenance/runs/${row.id}`)).data
  activeTab.value = 'CERTAIN'
  runDrawer.value = true
}
function openEligibility(row, allPaths, allEdges) {
  // 落库资格行用 snake_case，统一归一化成抽屉所需字段
  evTarget.value = {
    equipment_code: row.equipment_code,
    equipmentId: row.equipment_id,
    minimal_evidence: row.minimal_evidence || [],
    contradictions: row.contradiction || row.contradictions || []
  }
  evPaths.value = (allPaths || []).filter(p => p.eligibility_id === row.id)
  // 只展示与该设备实物相关的总成派生边
  const codes = new Set((evTarget.value.minimal_evidence || []).map(e => e.nodeCode))
  evEdges.value = (allEdges || []).filter(e => codes.has(e.from_code) || codes.has(e.to_code))
  evDrawer.value = true
}
function openAdhocDetail(row) {
  // 即时试算行用 camelCase，同样归一化
  evTarget.value = {
    equipment_code: null,
    equipmentId: row.equipmentId,
    minimal_evidence: row.minimalEvidence || [],
    contradictions: row.contradictions || []
  }
  evPaths.value = (row.supportPaths || []).map((codes, i) => ({
    path_no: `PATH-${String(i + 1).padStart(2, '0')}`,
    node_codes: codes,
    path_summary: '试算独立支持路径'
  }))
  evEdges.value = []
  evDrawer.value = true
}
async function runAdhoc() {
  if (!adhoc.targetModelId || !adhoc.range?.length) {
    ElMessage.warning('请选择目标型号和时间窗'); return
  }
  adhocLoading.value = true
  try {
    const payload = {
      targetModelId: adhoc.targetModelId,
      supplierId: adhoc.supplierId || null,
      windowStart: adhoc.range[0],
      windowEnd: adhoc.range[1]
    }
    adhocResult.value = (await http.post('/maintenance/evaluate-adhoc', payload)).data
  } finally { adhocLoading.value = false }
}

onMounted(load)
</script>
<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.tip { margin-top: 12px; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.iv-tag { margin: 2px 4px 2px 0; }
.mini-stat { text-align: center; }
.mini-num { font-size: 26px; font-weight: 700; margin-top: 6px; }
.stat-row { margin-bottom: 10px; }
.contradiction { background: #fef2f2; border: 1px solid #fecaca; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
.ct-title { font-weight: 600; color: #b91c1c; margin-bottom: 6px; }
.ct-line { font-size: 13px; color: #7f1d1d; margin: 3px 0; }
.path-line { margin-bottom: 2px; }
h4 { margin: 16px 0 8px; }
</style>
