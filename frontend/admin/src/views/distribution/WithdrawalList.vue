<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { formatShanghaiDateTime } from '@jxsc/shared';
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
const exportAllowed = computed(() => canExport(permissions.value, 'WITHDRAWALS'));
import type { AdminWithdrawalAuditView } from '@jxsc/shared';

const loading = ref(false);
const list = ref<AdminWithdrawalAuditView[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const refreshingId = ref<string | null>(null);

async function loadData() {
  loading.value = true;
  try {
    const res = await adminDistributionApi.listWithdrawals({
      page: page.value,
      pageSize: pageSize.value,
    });
    list.value = res.items;
    total.value = res.total;
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '加载提现审核流水失败');
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

async function handleRefresh(row: AdminWithdrawalAuditView) {
  refreshingId.value = row.id;
  try {
    const updated = await adminDistributionApi.refreshWithdrawal(row.id);
    ElMessage.success(`提现单 ${updated.withdrawalNo} 渠道最新状态已同步`);
    await loadData();
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '原单重查失败');
  } finally {
    refreshingId.value = null;
  }
}

async function handleExport() {
  try {
    if (!exportAllowed.value) throw new Error('无此导出权限');
    const [startDate, endDate] = defaultAnalyticsDates();
    const task = await adminOperationsApi.createExport({
      type: 'WITHDRAWALS',
      startDate,
      endDate,
    });
    ElMessage.success(
      `已创建 ${startDate} 至 ${endDate} 的导出任务 (ID: ${task.id})，请前往数据导出页下载`,
    );
  } catch (e) {
    ElMessage.error((e instanceof Error ? e.message : '') || '导出提现记录失败');
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
        <h1 class="brand-title">自动提现</h1>
      </div>
      <el-button v-if="exportAllowed" size="small" @click="handleExport"
        >导出最近7天提现记录</el-button
      >
    </header>

    <main class="admin-content">
      <!-- 财务对账指引 -->
      <p class="notice">到账以微信渠道查询结果为准。处理中请使用“重新查单”查询原单。</p>
      <details class="admin-card reconciliation-card">
        <summary>资金与对账说明</summary>
        <p>
          本系统通过微信官方商家转账渠道出款，禁止人工标记到账或伪造成功。重新查单沿用原提现单，不发起新的转账。
        </p>
      </details>

      <p>按完整分页展示流水；当前接口暂不支持关键词与状态筛选。</p>

      <div class="admin-card">
        <el-table :data="list" v-loading="loading" stripe style="width: 100%">
          <el-table-column prop="withdrawalNo" label="提现单号" width="160" />
          <el-table-column prop="distributorId" label="分销员ID" width="130" />
          <el-table-column label="提现金额" width="120">
            <template #default="{ row }">
              <strong>¥{{ ((row.amountFen || 0) / 100).toFixed(2) }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="渠道与对账" min-width="240"
            ><template #default="{ row }">
              <p v-if="row.alerted" role="alert" class="ls-funds-alert">
                已告警，请核对渠道与资金记录
              </p>
              <p v-if="row.errorCode" role="alert" class="ls-funds-alert">
                渠道异常：{{ row.errorCode }}
              </p>
              <p
                v-if="
                  row.reconciliation &&
                  row.reconciliation.status !== 'MATCHED' &&
                  row.reconciliation.status !== 'BALANCED'
                "
                role="status"
              >
                资金对账：{{ uiStatus(row.reconciliation.status) }}
              </p>
              <details class="ls-secondary-details">
                <summary>查看渠道与对账详情</summary>
                <p>商户出款单号：{{ row.outBillNo || '暂无' }}</p>
                <p>
                  出款渠道：{{ uiStatus(row.channel) }} · 渠道状态：{{
                    uiStatus(row.providerState)
                  }}
                </p>
                <p>
                  计划下次查询：{{
                    row.nextQueryAt ? formatShanghaiDateTime(row.nextQueryAt) : '暂无'
                  }}
                </p>
                <template v-if="row.reconciliation"
                  ><p>对账状态：{{ uiStatus(row.reconciliation.status) }}</p>
                  <p>
                    渠道观察出款 ¥{{ (row.reconciliation.observedPaidOutFen / 100).toFixed(2) }} ·
                    已记账 ¥{{ (row.reconciliation.accountedPaidOutFen / 100).toFixed(2) }}
                  </p>
                  <p>最近查询：{{ uiStatus(row.reconciliation.lastQueryState) }}</p></template
                >
                <p v-else>暂无对账信息</p>
              </details>
            </template></el-table-column
          >
          <el-table-column label="当前状态" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'SUCCEEDED'" type="success">已成功到账</el-tag>
              <el-tag v-else-if="row.status === 'PROCESSING'" type="primary">微信处理中</el-tag>
              <el-tag v-else-if="row.status === 'USER_ACTION_REQUIRED'" type="warning"
                >待用户确认</el-tag
              >
              <el-tag v-else-if="row.status === 'FAILED'" type="danger">转账失败</el-tag>
              <el-tag v-else-if="row.status === 'CLOSED'" type="info">已关单</el-tag>
              <el-tag v-else type="info">{{ uiStatus(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="申请时间" width="160">
            <template #default="{ row }">
              {{ formatShanghaiDateTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="120">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                link
                :loading="refreshingId === row.id"
                @click="handleRefresh(row)"
                >重新查单</el-button
              >
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
.ls-funds-alert {
  color: #b42318;
  font-weight: 600;
  margin: 6px 0;
}
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}
.reconciliation-card {
  padding: 16px 20px;
  background: #f8fafc;
  border-left: 4px solid #3b82f6;
  margin-bottom: 16px;
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
