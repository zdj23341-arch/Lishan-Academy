<script setup lang="ts">
import { formatShanghaiDateTime } from '@jxsc/shared';
import { ref, onMounted, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import { adminOperationsApi } from '../../api/operations';
import type { AuditLogItem } from '@jxsc/shared';
import { session } from '../../api/session';
import { auditSummary } from '../../api/audit-summary';

const loading = ref(false);
const list = ref<AuditLogItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
let sequence = 0;
let disposed = false;
const unsubscribe = session.subscribe(() => {
  sequence++;
  list.value = [];
  total.value = 0;
  loading.value = false;
});

async function loadData() {
  const request = ++sequence;
  const generation = session.getGeneration();
  const active = () => !disposed && request === sequence && generation === session.getGeneration();
  loading.value = true;
  try {
    const res = await adminOperationsApi.listAuditLogs({
      page: page.value,
      pageSize: pageSize.value,
    });
    if (active()) {
      list.value = res.items;
      total.value = res.total;
    }
  } catch (cause) {
    if (active()) ElMessage.error(cause instanceof Error ? cause.message : '加载审计日志失败');
  } finally {
    if (active()) loading.value = false;
  }
}

function handlePageChange(p: number) {
  page.value = p;
  loadData();
}

function handleSizeChange(size: number) {
  pageSize.value = size;
  page.value = 1;
  loadData();
}

onMounted(() => {
  loadData();
});
onUnmounted(() => {
  disposed = true;
  sequence++;
  unsubscribe();
});
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <router-link to="/" class="back-link">← 返回工作台</router-link>
        <h1 class="brand-title">系统操作审计日志</h1>
      </div>
    </header>

    <main class="admin-content">
      <div class="admin-card filter-card">
        <p>当前按操作时间分页展示全部日志；模块和操作者筛选暂未开放。</p>
        <el-button @click="loadData">刷新日志</el-button>
      </div>

      <div class="admin-card">
        <el-table :data="list" v-loading="loading" stripe style="width: 100%">
          <el-table-column prop="createdAt" label="操作时间" width="160">
            <template #default="{ row }">
              {{ formatShanghaiDateTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作者" width="170"
            ><template #default="{ row }"
              >{{ row.actorType }} / {{ row.actorId }}</template
            ></el-table-column
          >
          <el-table-column prop="resourceType" label="目标模块" width="160" />
          <el-table-column prop="resourceId" label="目标对象ID" width="140" />
          <el-table-column prop="action" label="动作" width="160" />
          <el-table-column prop="reason" label="变更理由" min-width="160" />
          <el-table-column label="变更前后摘要" min-width="180"
            ><template #default="{ row }">{{ auditSummary(row) }}</template></el-table-column
          >
          <el-table-column prop="requestId" label="请求ID" width="140" />
        </el-table>

        <div style="display: flex; justify-content: flex-end; margin-top: 16px">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleSizeChange"
            @current-change="handlePageChange"
          />
        </div>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}
.filter-card {
  padding: 16px 20px;
  margin-bottom: 16px;
  .filter-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}
</style>
