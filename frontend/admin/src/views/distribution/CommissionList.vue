<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import { adminDistributionApi } from '../../api/distribution';
import { adminOperationsApi } from '../../api/operations';
import { canExport } from '../../api/export-history';
import { session } from '../../api/session';
import { defaultAnalyticsDates } from '@jxsc/shared';
const permissions = ref(session.get()?.permissions);
const unsubscribe = session.subscribe((value) => {
  permissions.value = value?.permissions;
});
onUnmounted(unsubscribe);
const exportAllowed = computed(() => canExport(permissions.value, 'COMMISSIONS'));
import type { CommissionItem } from '@jxsc/shared';

const loading = ref(false);
const list = ref<CommissionItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

async function loadData() {
  loading.value = true;
  try {
    const res = await adminDistributionApi.listCommissions({
      page: page.value,
      pageSize: pageSize.value,
    });
    list.value = res.items || [];
    total.value = res.total || 0;
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '加载佣金流水失败');
  } finally {
    loading.value = false;
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

async function handleExport() {
  try {
    if (!exportAllowed.value) throw new Error('无此导出权限');
    const [startDate, endDate] = defaultAnalyticsDates();
    const task = await adminOperationsApi.createExport({
      type: 'COMMISSIONS',
      startDate,
      endDate,
    });
    ElMessage.success(
      `已创建 ${startDate} 至 ${endDate} 的导出任务 (ID: ${task.id})，请前往数据导出页下载`,
    );
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '导出佣金流水失败');
  }
}

onMounted(() => {
  loadData();
});
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <router-link to="/" class="back-link">← 返回工作台</router-link>
        <h1 class="brand-title">佣金收益流水明细</h1>
      </div>
      <el-button v-if="exportAllowed" size="small" @click="handleExport">导出最近7天流水</el-button>
    </header>

    <main class="admin-content">
      <p>按完整分页展示流水；当前接口暂不支持关键词与状态筛选。</p>

      <div class="admin-card">
        <el-table :data="list" v-loading="loading" stripe style="width: 100%">
          <el-table-column prop="id" label="流水单号" width="130" />
          <el-table-column label="关联订单ID" width="150">
            <template #default="{ row }">
              {{ row.orderId || row.orderNo || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="distributorId" label="分销员ID" width="120" />
          <el-table-column label="业绩基数" width="120">
            <template #default="{ row }">
              ¥{{ (((row.baseFen ?? row.orderPaidAmountFen) || 0) / 100).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column label="分成比例" width="90">
            <template #default="{ row }"> {{ ((row.rateBps || 0) / 100).toFixed(1) }}% </template>
          </el-table-column>
          <el-table-column label="原计佣金" width="110">
            <template #default="{ row }">
              ¥{{ (((row.earnedFen ?? row.commissionFen) || 0) / 100).toFixed(2) }}
            </template>
          </el-table-column>
          <el-table-column label="累计退款冲回" width="120">
            <template #default="{ row }">
              <span style="color: #dc2626">-¥{{ ((row.reversedFen || 0) / 100).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="净剩余佣金" width="120">
            <template #default="{ row }">
              <strong
                >¥{{
                  (
                    ((row.remainingFen ??
                      (row.earnedFen || row.commissionFen || 0) - (row.reversedFen || 0)) ||
                      0) / 100
                  ).toFixed(2)
                }}</strong
              >
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag v-if="(row.state || row.status) === 'AVAILABLE'" type="success"
                >可提现</el-tag
              >
              <el-tag v-else-if="(row.state || row.status) === 'SETTLED'" type="primary"
                >已结算</el-tag
              >
              <el-tag v-else-if="(row.state || row.status) === 'PENDING'" type="warning"
                >待结算</el-tag
              >
              <el-tag v-else-if="(row.state || row.status) === 'PARTIALLY_REVERSED'" type="danger"
                >部分冲回</el-tag
              >
              <el-tag v-else-if="(row.state || row.status) === 'REVERSED'" type="info"
                >全额冲回</el-tag
              >
              <el-tag v-else type="info">{{ uiStatus(row.state || row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="预计可用时间" min-width="160">
            <template #default="{ row }">
              {{
                row.eligibleAt
                  ? new Date(row.eligibleAt).toLocaleString('zh-CN', { hour12: false })
                  : '-'
              }}
            </template>
          </el-table-column>
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
