<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import UiIcon from '../../components/ui/UiIcon.vue';
import { formatShanghaiDateTime } from '@jxsc/shared';
import { computed, onUnmounted, ref, shallowRef } from 'vue';
import {
  ApiError,
  analyticsDates,
  defaultAnalyticsDates,
  type ExportTask,
  type ExportType,
} from '@jxsc/shared';
import { adminOperationsApi, exportHistory, forgetExportTask } from '../../api/operations';
import { session } from '../../api/session';
import { environment } from '../../api/environment';
import { canExport, type ExportHistoryItem } from '../../api/export-history';
import { fetchPrivateExport } from '../../api/export-download';

const login = shallowRef(session.get());
const types: { value: ExportType; label: string }[] = [
  { value: 'ORDERS', label: '交易订单' },
  { value: 'COMMISSIONS', label: '分销佣金' },
  { value: 'WITHDRAWALS', label: '提现记录' },
  { value: 'ANALYTICS', label: '经营统计' },
];
const allowedTypes = computed(() =>
  types.filter((type) => canExport(login.value?.permissions, type.value)),
);
const exportType = ref<ExportType>(allowedTypes.value[0]?.value ?? 'ORDERS');
const dateRange = ref<[string, string] | null>(defaultAnalyticsDates());
const currentTask = shallowRef<ExportTask | null>(null);
const historyTasks = ref<ExportHistoryItem[]>([]);
const queryTaskId = ref('');
const busy = ref(false);
const downloading = ref(false);
const error = ref('');
let sequence = 0;
let disposed = false;
function loadHistory() {
  historyTasks.value = (exportHistory()?.list() ?? []).filter((item) =>
    canExport(login.value?.permissions, item.type),
  );
}
loadHistory();
const unsubscribe = session.subscribe((value) => {
  sequence++;
  login.value = value;
  currentTask.value = null;
  queryTaskId.value = '';
  busy.value = downloading.value = false;
  error.value = '';
  exportType.value = allowedTypes.value[0]?.value ?? 'ORDERS';
  loadHistory();
});
async function run(action: () => Promise<ExportTask>) {
  if (busy.value || disposed || !allowedTypes.value.length) return;
  const request = ++sequence;
  const generation = session.getGeneration();
  const active = () => !disposed && request === sequence && generation === session.getGeneration();
  busy.value = true;
  downloading.value = false;
  error.value = '';
  currentTask.value = null;
  try {
    const task = await action();
    if (!active()) return;
    if (!canExport(login.value?.permissions, task.type))
      throw new Error('无权查看此类型的导出任务');
    currentTask.value = task;
    loadHistory();
  } catch (cause) {
    if (active()) error.value = cause instanceof Error ? cause.message : '导出操作失败，请重试';
  } finally {
    if (active()) busy.value = false;
  }
}
async function createTask() {
  await run(async () => {
    if (!canExport(login.value?.permissions, exportType.value)) throw new Error('无导出权限');
    if (!dateRange.value) throw new Error('请选择完整日期');
    const [startDate, endDate] = dateRange.value;
    analyticsDates(startDate, endDate);
    return adminOperationsApi.createExport({ type: exportType.value, startDate, endDate });
  });
}
async function queryTask(id: string) {
  await run(async () => {
    if (!id.trim()) throw new Error('请输入任务ID');
    return adminOperationsApi.getExportTask(id.trim());
  });
}
async function download() {
  const task = currentTask.value;
  if (!task || downloading.value || !canExport(login.value?.permissions, task.type)) return;
  const generation = session.getGeneration();
  const request = sequence;
  const active = () => !disposed && request === sequence && generation === session.getGeneration();
  downloading.value = true;
  error.value = '';
  try {
    const blob = await fetchPrivateExport(task, {
      apiBaseUrl: environment.apiBaseUrl,
      accessToken: session.get()?.accessToken ?? '',
    });
    if (!active()) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${task.type.toLowerCase()}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (cause) {
    if (active()) {
      error.value = cause instanceof Error ? cause.message : '下载失败';
      if (cause instanceof ApiError && [401, 403, 404].includes(cause.status)) {
        forgetExportTask(task.id);
        currentTask.value = null;
        loadHistory();
        if (cause.status === 401) session.clear();
      }
    }
  } finally {
    if (active()) downloading.value = false;
  }
}
onUnmounted(() => {
  disposed = true;
  sequence++;
  unsubscribe();
});
</script>

<template>
  <main class="admin-content exports-page">
    <header class="page-header">
      <div>
        <h1>数据导出</h1>
        <p class="page-desc">选择业务类型与日期，生成报表后查看任务状态并下载</p>
      </div>
    </header>
    <div class="admin-card">
      <h2>新建导出任务</h2>
      <p>按北京时间选择日期范围，包含起止日，最多366天。生成后请刷新任务并下载。</p>
      <p v-if="environment.provider === 'mock'">开发演示任务不生成真实 CSV 文件。</p>
      <p v-if="!allowedTypes.length" role="alert">无可用导出权限，请联系管理员。</p>
      <template v-else>
        <div class="actions">
          <el-select v-model="exportType" aria-label="导出类型" style="width: 180px">
            <el-option
              v-for="type in allowedTypes"
              :key="type.value"
              :label="type.label"
              :value="type.value"
            />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
          <el-button type="primary" :loading="busy" @click="createTask">创建导出任务</el-button>
        </div>
        <div class="actions">
          <el-input
            v-model="queryTaskId"
            placeholder="任务ID"
            aria-label="任务ID"
            style="width: 240px"
          />
          <el-button :disabled="busy" @click="queryTask(queryTaskId)">按ID查询</el-button>
        </div>
      </template>
      <p v-if="error" role="alert">{{ error }}</p>
    </div>
    <section v-if="currentTask" class="admin-card" data-testid="export-task">
      <h3>任务 {{ currentTask.id }}</h3>
      <p>类型：{{ uiStatus(currentTask.type) }} · 状态：{{ uiStatus(currentTask.status) }}</p>
      <p v-if="currentTask.rowCount !== undefined">数据行数：{{ currentTask.rowCount }}</p>
      <p v-if="currentTask.expiresAt">
        文件过期时间：{{ formatShanghaiDateTime(currentTask.expiresAt) }}
      </p>
      <p v-if="currentTask.status === 'FAILED'" role="alert">
        生成失败：{{ currentTask.errorCode || '请重新创建任务' }}
      </p>
      <el-button :disabled="busy" @click="queryTask(currentTask.id)">刷新任务状态</el-button>
      <el-button
        v-if="currentTask.status === 'SUCCEEDED' && currentTask.downloadUrl"
        :loading="downloading"
        @click="download"
        >下载 CSV</el-button
      >
    </section>
    <section v-if="allowedTypes.length" class="admin-card">
      <h3>当前账户最近任务</h3>
      <el-table v-if="historyTasks.length" :data="historyTasks">
        <el-table-column prop="id" label="任务ID" />
        <el-table-column label="类型"
          ><template #default="{ row }">{{ uiStatus(row.type) }}</template></el-table-column
        >
        <el-table-column label="状态"
          ><template #default="{ row }">{{ uiStatus(row.status) }}</template></el-table-column
        >
        <el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button :disabled="busy" @click="queryTask(row.id)">刷新查询</el-button></template
          ></el-table-column
        >
      </el-table>
      <div v-else class="ls-state">
        <span class="ls-state-symbol"><UiIcon name="download" tone="blue" :size="25" /></span>
        <strong class="ls-state-title">暂无导出任务</strong>
        <p class="ls-state-description">
          创建任务后可在这里查看记录，也可通过任务 ID 查询已有任务。
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.exports-page {
  width: 100%;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
}
</style>
