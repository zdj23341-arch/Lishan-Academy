<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { adminOperationsApi } from '../../api/operations';
import type { RuntimeReadinessView } from '@jxsc/shared';

const loading = ref(false);
const saving = ref(false);

// 真实财务与清算设置
const settingsVersion = ref(1);
const minAmountYuan = ref('1.00');
const maxAmountYuan = ref('500.00');
const dailyMaxCount = ref(3);
const observationDays = ref(14);
const editReason = ref('运营与财务常规风控配置调整');

// 运行时渠道真实探针
const runtimeLoading = ref(false);
const runtimeStatus = ref<RuntimeReadinessView | null>(null);

async function loadData() {
  loading.value = true;
  try {
    const res = await adminOperationsApi.getFinanceSettings();
    if (res && res.limits) {
      settingsVersion.value = res.version;
      minAmountYuan.value = ((res.limits.minFen || 100) / 100).toFixed(2);
      maxAmountYuan.value = ((res.limits.maxFen || 50000) / 100).toFixed(2);
      dailyMaxCount.value = res.limits.dailyMaxCount || 3;
      observationDays.value = res.observationDays || 14;
    }
  } catch (e: unknown) {
    ElMessage.error((e as Error)?.message || '加载财务与运营设置失败');
  } finally {
    loading.value = false;
  }
}

async function loadRuntime() {
  runtimeLoading.value = true;
  try {
    const res = await adminOperationsApi.getRuntimeReadiness();
    runtimeStatus.value = res;
  } catch {
    // 若当前管理员权限非 FINANCE_OPERATE 可能返回 403，优雅处理
    runtimeStatus.value = null;
  } finally {
    runtimeLoading.value = false;
  }
}

async function handleSave() {
  const minFen = Math.round(parseFloat(minAmountYuan.value) * 100);
  const maxFen = Math.round(parseFloat(maxAmountYuan.value) * 100);

  if (isNaN(minFen) || minFen < 100) {
    ElMessage.warning('单笔最小提现金额不能低于 1.00 元');
    return;
  }
  if (isNaN(maxFen) || maxFen > 100000) {
    ElMessage.warning('单笔最大提现金额不能高于 1000.00 元');
    return;
  }
  if (maxFen < minFen) {
    ElMessage.warning('单笔最大限额不能低于单笔最小限额');
    return;
  }
  if (dailyMaxCount.value < 1 || dailyMaxCount.value > 5) {
    ElMessage.warning('每日最大提现次数必须在 1 ~ 5 次之间');
    return;
  }
  if (observationDays.value < 7 || observationDays.value > 90) {
    ElMessage.warning('佣金结算观察期必须在 7 ~ 90 天之间');
    return;
  }
  if (!editReason.value.trim()) {
    ElMessage.warning('请填写本次参数变更的原因（审计合规必填）');
    return;
  }

  saving.value = true;
  try {
    const updated = await adminOperationsApi.updateFinanceSettings({
      version: settingsVersion.value,
      values: {
        minFen,
        maxFen,
        dailyMaxCount: dailyMaxCount.value,
        observationDays: observationDays.value,
      },
      reason: editReason.value.trim(),
    });

    settingsVersion.value = updated.version;
    minAmountYuan.value = (updated.limits.minFen / 100).toFixed(2);
    maxAmountYuan.value = (updated.limits.maxFen / 100).toFixed(2);
    dailyMaxCount.value = updated.limits.dailyMaxCount;
    observationDays.value = updated.observationDays;

    ElMessage.success('财务与清算参数已保存更新');
  } catch (e: unknown) {
    ElMessage.error((e as Error)?.message || '保存设置失败（若发生版本冲突请刷新后重试）');
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  loadData();
  loadRuntime();
});
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <router-link to="/" class="back-link">← 返回工作台</router-link>
        <h1 class="brand-title">系统参数与清算设置</h1>
      </div>
      <el-button type="primary" size="small" :loading="saving" @click="handleSave"
        >保存配置变更</el-button
      >
    </header>

    <main class="admin-content">
      <div class="admin-card" style="max-width: 800px; margin: 0 auto; padding: 28px">
        <div
          style="
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
          "
        >
          <h3 style="margin: 0">提现与佣金设置</h3>
          <details>
            <summary>配置详情</summary>
            配置版本：{{ settingsVersion }}
          </details>
        </div>

        <el-form label-position="top" v-loading="loading">
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px">
            <el-form-item label="单笔提现最小限额 (元)">
              <el-input v-model="minAmountYuan" placeholder="例如 1.00">
                <template #prefix>¥</template>
              </el-input>
              <span class="field-tip">范围 1.00 ~ 1000.00 元，低于此金额分销员不可发起提现。</span>
            </el-form-item>

            <el-form-item label="单笔提现最大限额 (元)">
              <el-input v-model="maxAmountYuan" placeholder="例如 500.00">
                <template #prefix>¥</template>
              </el-input>
              <span class="field-tip">单笔最高限额，上限 1000.00 元，且不得小于最小限额。</span>
            </el-form-item>
          </div>

          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px">
            <el-form-item label="每日单人提现次数上限">
              <el-input-number v-model="dailyMaxCount" :min="1" :max="5" style="width: 100%" />
              <span class="field-tip">单人自然日内成功发起提现的最大次数（1 ~ 5 次）。</span>
            </el-form-item>

            <el-form-item label="佣金解冻观察期 (天)">
              <el-input-number v-model="observationDays" :min="7" :max="90" style="width: 100%" />
              <span class="field-tip"
                >订单支付完成后满此天数无售后退款，佣金解冻为可提现（7 ~ 90 天）。</span
              >
            </el-form-item>
          </div>

          <el-form-item label="变更原因 (必填审计记录)">
            <el-input v-model="editReason" placeholder="请输入修改这组财务参数的业务或风控原因" />
          </el-form-item>

          <!-- 真实渠道与运行时探针卡片 -->
          <p
            v-if="
              runtimeStatus &&
              (!runtimeStatus.transferCreateReady ||
                !runtimeStatus.transferRecoveryReady ||
                runtimeStatus.unresolvedTransfers > 0 ||
                runtimeStatus.heldOrders > 0)
            "
            role="alert"
            class="runtime-warning"
          >
            渠道或资金记录需要核对，请展开运行状态查看详情。
          </p>
          <details class="channel-status-box" v-loading="runtimeLoading">
            <summary>渠道运行状态</summary>
            <div
              style="
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 12px;
              "
            >
              <h4 style="margin: 0; color: #1e293b">微信出款与恢复服务</h4>
              <el-button size="small" text type="primary" @click="loadRuntime"
                >刷新运行状态</el-button
              >
            </div>

            <div
              v-if="runtimeStatus"
              style="
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 16px;
                font-size: 13px;
                color: #475569;
              "
            >
              <div>
                微信自动出款通道：
                <el-tag
                  size="small"
                  :type="runtimeStatus.transferCreateReady ? 'success' : 'danger'"
                >
                  {{ runtimeStatus.transferCreateReady ? '通道就绪' : '未就绪 / 未配置' }}
                </el-tag>
              </div>
              <div>
                提现恢复服务状态：
                <el-tag
                  size="small"
                  :type="runtimeStatus.transferRecoveryReady ? 'success' : 'danger'"
                >
                  {{ runtimeStatus.transferRecoveryReady ? '服务就绪' : '恢复中 / 未就绪' }}
                </el-tag>
              </div>
              <div>
                未解决对账转账单：
                <strong
                  :style="{ color: runtimeStatus.unresolvedTransfers > 0 ? '#DC2626' : '#16A34A' }"
                >
                  {{ runtimeStatus.unresolvedTransfers }} 笔
                </strong>
              </div>
              <div>
                财务挂起订单数量：
                <strong :style="{ color: runtimeStatus.heldOrders > 0 ? '#D97706' : '#16A34A' }">
                  {{ runtimeStatus.heldOrders }} 笔
                </strong>
              </div>
            </div>
            <div v-else style="font-size: 13px; color: #64748b">
              运行状态暂不可读，请确认财务权限后刷新重试。
            </div>

            <p style="font-size: 12px; color: #94a3b8; margin-top: 12px; margin-bottom: 0">
              * 渠道就绪表示服务可用。实际到账仍以提现单的微信渠道查询结果为准。
            </p>
          </details>
        </el-form>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.runtime-warning {
  color: #b42318;
  line-height: 1.6;
}
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}
.field-tip {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}
.channel-status-box {
  margin-top: 24px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
</style>
