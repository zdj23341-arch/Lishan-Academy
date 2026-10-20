<script setup lang="ts">
import { uiStatus } from './ui/labels';
import { computed, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { ElButton, ElCheckbox, ElDatePicker } from 'element-plus';
import {
  analyticsDates,
  ApiError,
  defaultAnalyticsDates,
  formatFen,
  hasPermission,
  type AnalyticsOverview,
  type AnalyticsTrend,
  type ExportTask,
} from '@jxsc/shared';
import { adminOperationsApi, forgetExportTask } from '../api/operations';
import { session } from '../api/session';
import { environment } from '../api/environment';
import { fetchPrivateExport } from '../api/export-download';
import { canExport } from '../api/export-history';

defineProps<{ compact?: boolean }>();
const login = shallowRef(session.get());
const allowed = computed(() => hasPermission(login.value?.permissions, 'ANALYTICS_READ'));
const exportAllowed = computed(() => canExport(login.value?.permissions, 'ANALYTICS'));
const dateRange = ref<[string, string] | undefined>(defaultAnalyticsDates());
const includeDevChannel = ref(false);
const canIncludeDev = ['development', 'test'].includes(environment.mode);
const loading = ref(false);
const overview = shallowRef<AnalyticsOverview | null>(null);
const trend = shallowRef<AnalyticsTrend | null>(null);
const error = ref('');
const trendError = ref('');
const exporting = ref(false);
const exportTask = shallowRef<ExportTask | null>(null);
const exportError = ref('');
const downloading = ref(false);
let requestSequence = 0;
let exportSequence = 0;
let disposed = false;

function clear() {
  requestSequence++;
  exportSequence++;
  overview.value = null;
  trend.value = null;
  exportTask.value = null;
  error.value = '';
  trendError.value = '';
  exportError.value = '';
  loading.value = false;
  exporting.value = false;
  downloading.value = false;
}
const unsubscribe = session.subscribe((value) => {
  clear();
  login.value = value;
});
const emptyPeriod = computed(
  () => overview.value && Object.values(overview.value.periodMetrics).every((v) => v === 0),
);
const periodMetrics = computed(() => {
  const m = overview.value?.periodMetrics;
  return m
    ? [
        ['创建订单', String(m.orderCreatedCount)],
        ['支付订单', String(m.paidOrderCount)],
        ['支付用户', String(m.paidUserCount)],
        ['实收金额', `¥${formatFen(m.receivedAmountFen)}`],
        ['退款金额', `¥${formatFen(m.refundedAmountFen)}`],
        ['实收净额', `¥${formatFen(m.netCashAmountFen)}`],
        ['课程销量', String(m.courseSoldQuantity)],
        ['图书销量', String(m.bookSoldQuantity)],
        ['区间新购会员', String(m.membershipNewPurchaseCount)],
        ['区间续费会员', String(m.membershipRenewalCount)],
        ['新增佣金', `¥${formatFen(m.commissionEarnedFen)}`],
        ['冲回佣金', `¥${formatFen(m.commissionReversedFen)}`],
        ['已成功提现', `¥${formatFen(m.withdrawalSucceededFen)}`],
      ]
    : [];
});
const summaryMetrics = computed(() => {
  const value = overview.value;
  if (!value) return [];
  return [
    ['销售额', '¥' + formatFen(value.periodMetrics.receivedAmountFen), '已支付订单口径'],
    ['支付订单', String(value.periodMetrics.paidOrderCount), '最近7天'],
    ['退款金额', '¥' + formatFen(value.periodMetrics.refundedAmountFen), '已退款金额'],
    ['新增会员', String(value.periodMetrics.membershipNewPurchaseCount), '最近7天'],
  ];
});
const currentMetrics = computed(() => {
  const m = overview.value?.currentMetrics;
  return m
    ? [
        ['当前有效会员', String(m.activeMemberCount)],
        ['当前待发货订单', String(m.waitingShipmentOrderCount)],
        ['待结算佣金', `¥${formatFen(m.pendingCommissionFen)}`],
        ['可提现佣金', `¥${formatFen(m.availableCommissionFen)}`],
        ['提现中', `¥${formatFen(m.withdrawingFen)}`],
        ['欠款', `¥${formatFen(m.debtFen)}`],
      ]
    : [];
});
const details = computed(() => {
  const m = overview.value;
  if (!m) return [];
  return [
    ['试卷下载授权次数', m.downloads],
    ['试卷下载授权用户', m.downloadUsers],
    ['预览授权次数', m.previews],
    [
      '商品退款额（元）',
      m.goodsRefundsFen === undefined ? undefined : formatFen(m.goodsRefundsFen),
    ],
    ['退货数量', m.returnedUnits],
    ['财务冻结订单', m.financialHoldOrders],
    ['未分类授权记录', m.unclassifiedAuthorizations],
  ].filter(([, value]) => value !== undefined);
});
const trendMaxReceived = computed(() =>
  Math.max(0, ...(trend.value?.items.map((item) => item.receivedAmountFen) ?? [])),
);
function trendBarHeight(receivedAmountFen: number): number {
  if (!trendMaxReceived.value || receivedAmountFen <= 0) return 0;
  return Math.max(6, Math.round((receivedAmountFen / trendMaxReceived.value) * 150));
}
function compactDate(value: string): string {
  return value.slice(5);
}
const exportDownloadUrl = computed(() => {
  const task = exportTask.value;
  if (
    task?.status !== 'SUCCEEDED' ||
    !task.downloadUrl ||
    !task.expiresAt ||
    Date.parse(task.expiresAt) <= Date.now()
  )
    return null;
  try {
    const url = new URL(task.downloadUrl, new URL(environment.apiBaseUrl, window.location.href));
    return ['https:', 'http:'].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
});
function query() {
  if (!dateRange.value) throw new Error('请选择完整的开始和结束日期');
  const [startDate, endDate] = dateRange.value;
  analyticsDates(startDate, endDate);
  return { startDate, endDate, includeDevChannel: canIncludeDev && includeDevChannel.value };
}
function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '加载统计失败，请重试';
}
function generatedAt(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(new Date(value));
}
async function load() {
  if (!allowed.value || disposed) return;
  let params;
  try {
    params = query();
  } catch (cause) {
    error.value = message(cause);
    return;
  }
  const sequence = ++requestSequence;
  const generation = session.getGeneration();
  loading.value = true;
  error.value = '';
  trendError.value = '';
  const [summary, daily] = await Promise.allSettled([
    adminOperationsApi.getAnalyticsOverview(params),
    adminOperationsApi.getAnalyticsTrend(params),
  ]);
  if (disposed || sequence !== requestSequence || generation !== session.getGeneration()) return;
  if (summary.status === 'fulfilled') overview.value = summary.value;
  else error.value = message(summary.reason);
  if (daily.status === 'fulfilled') trend.value = daily.value;
  else trendError.value = message(daily.reason);
  loading.value = false;
}
async function exportReport(refresh = false) {
  if (!exportAllowed.value || exporting.value || disposed) return;
  const sequence = ++exportSequence;
  downloading.value = false;
  const generation = session.getGeneration();
  exporting.value = true;
  exportError.value = '';
  try {
    let task: ExportTask;
    if (refresh && exportTask.value)
      task = await adminOperationsApi.getExportTask(exportTask.value.id);
    else {
      const params = query();
      if (params.includeDevChannel)
        throw new Error('导出默认排除开发模拟，请取消包含开发交易后再导出');
      task = await adminOperationsApi.createExport('ANALYTICS', {
        startDate: params.startDate,
        endDate: params.endDate,
      });
    }
    if (!disposed && sequence === exportSequence && generation === session.getGeneration()) {
      if (exportTask.value?.id !== task.id) downloading.value = false;
      exportTask.value = task;
    }
  } catch (cause) {
    if (!disposed && sequence === exportSequence && generation === session.getGeneration())
      exportError.value = message(cause);
  } finally {
    if (sequence === exportSequence) exporting.value = false;
  }
}
async function downloadReport(event: MouseEvent) {
  event.preventDefault();
  const task = exportTask.value;
  if (!task || !exportDownloadUrl.value || !exportAllowed.value) {
    event.preventDefault();
    return;
  }
  if (!task.expiresAt || Date.parse(task.expiresAt) <= Date.now()) {
    event.preventDefault();
    exportError.value = '下载链接已过期，请重新生成任务';
    return;
  }
  if (downloading.value) return;
  const current = session.get();
  const generation = session.getGeneration();
  const sequence = exportSequence;
  const active = () =>
    !disposed &&
    generation === session.getGeneration() &&
    sequence === exportSequence &&
    exportTask.value?.id === task.id;
  downloading.value = true;
  exportError.value = '';
  try {
    const blob = await fetchPrivateExport(task, {
      apiBaseUrl: environment.apiBaseUrl,
      accessToken: current?.accessToken ?? '',
    });
    if (!active()) return;
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = 'analytics.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  } catch (cause) {
    if (active()) {
      exportError.value = message(cause);
      if (cause instanceof ApiError && [401, 403, 404].includes(cause.status)) {
        forgetExportTask(task.id);
        exportTask.value = null;
        if (cause.status === 401) session.clear();
      }
    }
  } finally {
    if (active()) downloading.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  disposed = true;
  clear();
  unsubscribe();
});
</script>

<template>
  <section
    class="analytics-panel"
    :class="{ 'analytics-panel--compact': compact }"
    :aria-busy="loading"
  >
    <div v-if="!allowed" class="admin-card state-message" role="alert">
      <h2>无权限访问</h2>
      <p>查看经营数据需要经营统计查看权限。</p>
    </div>
    <template v-else>
      <div v-if="!compact" class="admin-card filter-card">
        <div>
          <h2>{{ compact ? '近7天经营摘要' : '经营统计' }}</h2>
          <p>
            {{
              compact
                ? '区间金额与支付订单按北京时间统计；会员、待发货为当前快照。'
                : '统计日期按北京时间，包含开始与结束日，最多366天。'
            }}
          </p>
          <label v-if="!compact" for="analytics-date-range">统计日期</label>
          <el-date-picker
            v-if="!compact"
            :id="['analytics-date-range', 'analytics-end-date']"
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
          <el-checkbox v-if="canIncludeDev && !compact" v-model="includeDevChannel"
            >包含开发交易（仅联调用）</el-checkbox
          >
        </div>
        <div class="actions">
          <router-link v-if="compact" to="/analytics">查看完整经营数据</router-link>
          <el-button type="primary" :loading="loading" @click="load">查询更新</el-button>
          <el-button
            v-if="!compact"
            :loading="exporting"
            :disabled="!exportAllowed || includeDevChannel || loading"
            @click="exportReport()"
            >导出报表</el-button
          >
        </div>
      </div>
      <p v-if="environment.provider === 'mock' && compact" class="notice">
        开发演示数据，正式经营数据以真实接口为准。
      </p>
      <p v-if="environment.provider === 'mock' && !compact" class="notice">
        开发演示数据 · 本地
        模拟数据，不能用于经营结算或正式验收。示例交易在2026-10-06至07，勾选包含开发交易可查看。
      </p>
      <p v-if="loading" role="status">
        正在加载经营数据<span v-if="overview">，下方暂保留上次成功结果</span>…
      </p>
      <div v-if="error || trendError" class="admin-card state-message" role="alert">
        <p v-if="error">概览未更新：{{ error }}</p>
        <p v-if="trendError">趋势未更新：{{ trendError }}</p>
        <p v-if="overview || trend">已显示的结果保留各自统计日期，请核对后使用。</p>
        <el-button :loading="loading" @click="load">重试</el-button>
      </div>
      <template v-if="overview">
        <p class="notice">
          {{ { dev: '开发环境', test: '测试环境', prod: '生产环境' }[overview.environment] }} ·
          {{ overview.includesDevChannel ? '包含开发模拟交易' : '排除开发模拟交易' }} · 生成时间
          {{ generatedAt(overview.generatedAt) }}（北京时间）
        </p>
        <dl class="summary-grid" :aria-label="compact ? '近7天经营摘要' : '所选区间经营摘要'">
          <div v-for="[label, value, hint] in summaryMetrics" :key="label" class="summary-card">
            <dt>{{ label }}</dt>
            <dd>{{ value }}</dd>
            <small>{{ compact ? hint : '所选统计区间' }}</small>
          </div>
        </dl>
        <section v-if="!compact && trend" class="admin-card overview-chart">
          <div class="admin-card__header">
            <h3>销售趋势</h3>
            <span class="page-desc"
              >{{ trend.period.startDate }} 至 {{ trend.period.endDate }}</span
            >
          </div>
          <ol v-if="trend.items.length" class="trend-bars" aria-label="每日实收金额">
            <li v-for="item in trend.items" :key="item.date">
              <span class="trend-bar-value">¥{{ formatFen(item.receivedAmountFen) }}</span>
              <span
                class="trend-bar-track"
                role="img"
                :aria-label="`${item.date} 实收 ¥${formatFen(item.receivedAmountFen)}`"
              >
                <span
                  class="trend-bar"
                  :style="{ height: `${trendBarHeight(item.receivedAmountFen)}px` }"
                />
              </span>
              <span class="trend-bar-date">{{ compactDate(item.date) }}</span>
            </li>
          </ol>
          <p v-if="!trend.items.length || !trendMaxReceived" class="trend-empty">
            所选区间暂无实收金额。
          </p>
        </section>
        <section v-if="!compact" class="admin-card">
          <h3>区间经营指标 · {{ overview.period.startDate }} 至 {{ overview.period.endDate }}</h3>
          <p v-if="!compact">
            创建与支付订单分别统计；实收净额为实收减退款，可为负数，不代表利润。
          </p>
          <p v-if="emptyPeriod" class="notice" role="status">
            所选区间暂无交易，以下为接口返回的真实零值。
          </p>
          <dl class="metrics-grid" data-testid="period-metrics">
            <div
              v-for="[label, value] in compact ? summaryMetrics : periodMetrics"
              :key="label"
              class="metric-card"
            >
              <dt>{{ label }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
        </section>
        <section v-if="!compact" class="admin-card">
          <h3>当前状态快照</h3>
          <p>截至概览生成时点，不受所选历史区间限制。提现中资金与待结算佣金分开展示。</p>
          <dl class="metrics-grid">
            <div v-for="[label, value] in currentMetrics" :key="label" class="metric-card">
              <dt>{{ label }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
        </section>
        <section v-if="!compact && details.length" class="admin-card">
          <h3>下载、退款与异常记录</h3>
          <p>下载/预览统计表示成功签发授权，不表示文件已保存；财务冻结为当前状态。</p>
          <dl class="metrics-grid">
            <div v-for="[label, value] in details" :key="String(label)" class="metric-card">
              <dt>{{ label }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
        </section>
      </template>
      <div
        v-if="
          compact &&
          overview &&
          trend &&
          (trend.items.length || hasPermission(login?.permissions, 'ORDER_OPERATE'))
        "
        class="dashboard-panels"
        :class="{
          'dashboard-panels--solo':
            !trend.items.length || !hasPermission(login?.permissions, 'ORDER_OPERATE'),
        }"
      >
        <section v-if="trend.items.length" class="admin-card dashboard-trend">
          <h3>
            销售趋势 <span>{{ trend.period.startDate }} 至 {{ trend.period.endDate }}</span>
          </h3>
          <ol class="trend-bars" aria-label="每日实收金额">
            <li v-for="item in trend.items" :key="item.date">
              <span class="trend-bar-value">¥{{ formatFen(item.receivedAmountFen) }}</span>
              <span
                class="trend-bar-track"
                role="img"
                :aria-label="`${item.date} 实收 ¥${formatFen(item.receivedAmountFen)}`"
              >
                <span
                  class="trend-bar"
                  :style="{ height: `${trendBarHeight(item.receivedAmountFen)}px` }"
                />
              </span>
              <span class="trend-bar-date">{{ compactDate(item.date) }}</span>
            </li>
          </ol>
          <p v-if="!trendMaxReceived" class="trend-empty">最近7天暂无实收金额。</p>
        </section>
        <section
          v-if="hasPermission(login?.permissions, 'ORDER_OPERATE')"
          class="admin-card dashboard-todo"
        >
          <h3>待办事项</h3>
          <router-link to="/commerce/orders" class="todo-link">
            <span>当前待发货订单</span>
            <strong>{{ overview.currentMetrics.waitingShipmentOrderCount }}</strong>
            <span class="todo-action">查看订单</span>
          </router-link>
          <p>待发货数量按当前订单状态统计。</p>
        </section>
      </div>
      <section v-if="!compact && trend" class="admin-card">
        <h3>按日趋势 · {{ trend.period.startDate }} 至 {{ trend.period.endDate }}</h3>
        <p>
          {{ trend.includesDevChannel ? '包含开发模拟交易' : '排除开发模拟交易' }} · 生成时间
          {{ generatedAt(trend.generatedAt) }}（北京时间）
        </p>
        <div class="table-scroll" tabindex="0" aria-label="按日经营趋势，可横向滚动">
          <table class="admin-table">
            <caption>
              订单与资金趋势，金额单位：元
            </caption>
            <thead>
              <tr>
                <th scope="col">日期</th>
                <th scope="col">创建订单</th>
                <th scope="col">支付订单</th>
                <th scope="col">实收</th>
                <th scope="col">退款</th>
                <th scope="col">实收净额</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in trend.items" :key="item.date">
                <th scope="row">{{ item.date }}</th>
                <td>{{ item.orderCreatedCount }}</td>
                <td>{{ item.paidOrderCount }}</td>
                <td>{{ formatFen(item.receivedAmountFen) }}</td>
                <td>{{ formatFen(item.refundedAmountFen) }}</td>
                <td>{{ formatFen(item.netCashAmountFen) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <div v-if="exportError" role="alert">{{ exportError }}</div>
      <section v-if="exportTask" class="admin-card">
        <h3>报表导出任务</h3>
        <p>任务 {{ exportTask.id }} · {{ uiStatus(exportTask.status) }} · 默认排除开发模拟</p>
        <p v-if="exportTask.status === 'FAILED'" role="alert">
          导出失败：{{ exportTask.errorCode || exportTask.errorReason || '请重新生成任务' }}
        </p>
        <el-button :loading="exporting" @click="exportReport(true)">刷新任务状态</el-button>
        <a
          v-if="exportDownloadUrl"
          :href="exportDownloadUrl"
          download
          target="_blank"
          rel="noopener noreferrer"
          :aria-disabled="downloading"
          @click="downloadReport"
          >{{ downloading ? '下载中…' : '下载 CSV' }}</a
        >
        <p v-else-if="exportTask.status === 'SUCCEEDED'">下载链接无效或已过期，请重新生成任务。</p>
      </section>
      <p v-if="!compact" class="scope-note">访问人数、访问次数、新访问用户请在微信公众平台查看。</p>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.analytics-panel {
  h2,
  h3 {
    margin: 0 0 12px;
  }
  p {
    color: var(--el-text-color-regular);
    line-height: 1.6;
  }
  .admin-card {
    padding: 20px;
    margin-bottom: 20px;
  }
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 18px;
}
.summary-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 128px;
  padding: 17px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: #fff;
}
.summary-card dt {
  font-size: 12px;
}
.summary-card dd {
  margin: 8px 0 0;
  color: var(--el-text-color-primary);
  font-size: 24px;
  font-weight: 700;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.summary-card small {
  margin-top: auto;
  padding-top: 8px;
  color: var(--el-color-success);
  font-size: 12px;
}
.dashboard-panels {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(250px, 1fr);
  align-items: stretch;
  gap: 16px;
  margin-bottom: 18px;
  .admin-card {
    min-width: 0;
    margin: 0;
    padding: 18px;
  }
  h3 {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    margin: 0 0 18px;
    font-size: 16px;
  }
  h3 span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-weight: 400;
  }
}
.dashboard-panels--solo {
  grid-template-columns: minmax(0, 1fr);
}
.trend-bars {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  align-items: end;
  gap: 8px;
  min-height: 180px;
  margin: 0;
  padding: 0 0 4px;
  border-bottom: 1px solid var(--el-border-color);
  list-style: none;
}
.trend-bars li {
  display: flex;
  min-width: 0;
  height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
}
.overview-chart .trend-bars {
  display: flex;
  overflow-x: auto;
}
.overview-chart .trend-bars li {
  flex: 1 0 48px;
}
.overview-chart h3 {
  margin-bottom: 0;
}
.trend-bar-value {
  max-width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trend-bar-track {
  display: flex;
  width: min(38px, 70%);
  height: 150px;
  align-items: flex-end;
  justify-content: center;
}
.trend-bar {
  display: block;
  width: 100%;
  max-height: 150px;
  border-radius: 4px 4px 0 0;
  background: #5578c4;
}
.trend-bar-date {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.trend-empty {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.dashboard-todo .todo-link {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  border-bottom: 1px solid var(--el-border-color);
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.todo-link strong {
  margin-left: auto;
  color: var(--el-color-primary);
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}
.todo-link .todo-action {
  color: var(--el-color-primary);
  font-size: 12px;
  white-space: nowrap;
}
.dashboard-todo p {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.filter-card {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.filter-card label {
  margin-right: 12px;
}
.actions {
  display: flex;
  gap: 8px;
}
.notice {
  padding: 12px 16px;
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin: 16px 0 0;
}
.metric-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 16px;
}
dt {
  color: var(--el-text-color-regular);
  font-size: 14px;
}
dd {
  margin: 10px 0 0;
  color: var(--el-text-color-primary);
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  white-space: nowrap;
}
caption {
  text-align: left;
  margin-bottom: 12px;
}
th,
td {
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color);
  text-align: right;
}
th:first-child {
  text-align: left;
}
.state-message {
  border: 1px solid var(--el-color-danger);
}
.scope-note {
  font-size: 14px;
}
a {
  color: var(--el-color-primary);
  margin-left: 12px;
}
@media (max-width: 640px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .dashboard-panels {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
