<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { onHide, onShow } from '@dcloudio/uni-app';
import {
  WithdrawalController,
  type DistributorSummary,
  type WithdrawalRecord,
  validateWithdrawalAmount,
} from '@jxsc/shared';
import PriceText from '../../components/PriceText.vue';
import { customerMessage, withdrawalStatusLabel } from '../../utils/customer-text';
import {
  distributionApi,
  wechatReceiptConfirmationAdapter,
  withdrawalKey,
} from '../../api/distribution';
import { operationNamespace, session, sessionStorage } from '../../api/session';

const emptySummary = (): DistributorSummary => ({
  distributorId: '',
  pendingFen: 0,
  availableFen: 0,
  withdrawingFen: 0,
  paidOutFen: 0,
  debtFen: 0,
  withdrawalEnabled: false,
  disabledReason: null,
  limits: {},
});

const summary = ref(emptySummary());
const summaryReady = ref(false);
const amountYuan = ref('');
const loading = ref(false);
const submitting = ref(false);
const error = ref('');
const success = ref('');
const controller = new WithdrawalController(distributionApi, wechatReceiptConfirmationAdapter, {
  session,
  storage: sessionStorage,
  namespace: operationNamespace,
});
const state = ref({
  currentRecord: controller.currentRecord,
  pendingKey: controller.pendingKey,
  pendingAmountFen: controller.pendingAmountFen,
  error: controller.error,
  loading: controller.loading,
});
const stopController = controller.subscribe(() => {
  state.value = {
    currentRecord: controller.currentRecord,
    pendingKey: controller.pendingKey,
    pendingAmountFen: controller.pendingAmountFen,
    error: controller.error,
    loading: controller.loading,
  };
});
let epoch = 0;
const minimumFen = computed(() => summary.value.limits.minFen ?? summary.value.limits.minAmountFen);
const maximumFen = computed(() => summary.value.limits.maxFen ?? summary.value.limits.maxAmountFen);
const receiptStatus = computed(() =>
  !summaryReady.value || summary.value.receiptReady === undefined
    ? '绑定状态待确认'
    : summary.value.receiptReady
      ? '已绑定'
      : '未绑定',
);
const canSubmit = computed(() =>
  Boolean(
    summaryReady.value &&
    !loading.value &&
    summary.value.distributorId &&
    summary.value.withdrawalEnabled &&
    !submitting.value,
  ),
);
const pendingRecord = computed<WithdrawalRecord | null>(() => state.value.currentRecord);

const clearForAccountChange = () => {
  epoch++;
  summary.value = emptySummary();
  summaryReady.value = false;
  amountYuan.value = '';
  error.value = session.get() ? '账户已变化，请重新查询提现条件' : '请先登录后申请提现';
  success.value = '';
};
const stopSession = session.subscribe(clearForAccountChange);

async function loadSummary() {
  const ticket = ++epoch;
  summaryReady.value = false;
  if (!session.get()) {
    error.value = '请先登录后申请提现';
    return;
  }
  loading.value = true;
  error.value = '';
  success.value = '';
  try {
    const value = await distributionApi.getSummary();
    if (ticket !== epoch) return;
    summary.value = { ...emptySummary(), ...value };
    summaryReady.value = true;
    if (!amountYuan.value && summary.value.availableFen > 0) {
      const max = maximumFen.value ?? summary.value.availableFen;
      amountYuan.value = (Math.min(summary.value.availableFen, max) / 100).toFixed(2);
    }
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '加载提现条件失败');
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}

async function recoverPending() {
  const ticket = epoch;
  error.value = '';
  success.value = '';
  const record = await controller.recoverPending();
  if (ticket !== epoch) return;
  if (record) success.value = `原提现已恢复：${withdrawalStatusLabel(record.status)}`;
  else error.value = customerMessage(controller.error, '原提现结果尚未确认，请继续查询原单');
}

async function retryPending() {
  const ticket = epoch;
  error.value = '';
  success.value = '';
  const record = await controller.retryPending();
  if (ticket !== epoch) return;
  if (record) success.value = `原提现已受理：${withdrawalStatusLabel(record.status)}`;
  else error.value = customerMessage(controller.error, '原提现仍待确认，请继续查询');
}

async function submit() {
  if (submitting.value || state.value.pendingKey) {
    if (state.value.pendingKey) await recoverPending();
    return;
  }
  if (!canSubmit.value) {
    error.value = customerMessage(
      summary.value.disabledReason,
      '当前暂不支持提现，请先核对提现条件',
    );
    return;
  }
  const valid = validateWithdrawalAmount(amountYuan.value, summary.value);
  if (!valid.valid) {
    error.value = customerMessage(valid.error, '提现金额无效');
    return;
  }
  const ticket = epoch;
  submitting.value = true;
  error.value = '';
  success.value = '';
  try {
    const record = await controller.submitWithdrawal(valid.amountFen, withdrawalKey());
    if (ticket !== epoch) return;
    success.value = `提现单已受理：${withdrawalStatusLabel(record.status)}`;
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '提现结果未知，请查询原单');
  } finally {
    if (ticket === epoch) submitting.value = false;
  }
}

async function confirmReceipt() {
  const record = pendingRecord.value;
  if (!record) return;
  const ticket = epoch;
  error.value = '';
  success.value = '';
  try {
    const confirmed = await controller.invokeReceiptConfirmation(String(record.id));
    if (ticket !== epoch) return;
    if (!confirmed) {
      error.value = '收款确认未完成，请查看原单状态后重试';
    } else if (controller.error) {
      error.value = customerMessage(controller.error, '原提现状态暂未确认，请刷新原单');
    } else if (controller.currentRecord) {
      success.value = `提现状态：${withdrawalStatusLabel(controller.currentRecord.status)}`;
    }
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '收款确认失败');
  }
}

async function refreshRecord() {
  if (!pendingRecord.value) return;
  const ticket = epoch;
  error.value = '';
  success.value = '';
  const record = await controller.refreshCurrent(String(pendingRecord.value.id));
  if (ticket !== epoch) return;
  if (record) success.value = `提现状态：${withdrawalStatusLabel(record.status)}`;
  else error.value = customerMessage(controller.error, '刷新提现状态失败');
}

onShow(() => {
  void loadSummary();
  if (controller.pendingKey) void recoverPending();
});
onHide(() => controller.pause());
onUnmounted(() => {
  epoch++;
  stopSession();
  stopController();
  controller.dispose();
});
</script>

<template>
  <view class="ls-mini-page withdrawal-page ui-page-container">
    <view v-if="loading" class="ls-status" role="status">正在读取提现条件…</view>
    <view v-if="error" class="ls-alert" role="alert">{{ error }}</view>
    <view v-if="summaryReady && !summary.withdrawalEnabled" class="ls-alert" role="status">{{
      customerMessage(summary.disabledReason, '当前暂不可提现，请核对提现条件或联系客服')
    }}</view>
    <view v-if="success" class="success-card" role="status">{{ success }}</view>

    <view class="ui-card balance-card">
      <text class="card-label">可提现余额（元）</text>
      <PriceText v-if="summaryReady" mode="amount" :fen="summary.availableFen" size="hero" />
      <text v-else class="muted">{{ loading ? '正在读取余额…' : '余额暂不可用' }}</text>
      <button v-if="!summaryReady && !loading" class="ui-btn" @click="loadSummary">
        重新读取提现条件
      </button>
      <view v-if="summaryReady" class="balance-meta">
        <text>待结算 ¥{{ (summary.pendingFen / 100).toFixed(2) }}</text>
        <text>处理中 ¥{{ (summary.withdrawingFen / 100).toFixed(2) }}</text>
        <text>已到账 ¥{{ (summary.paidOutFen / 100).toFixed(2) }}</text>
      </view>
      <view class="form-card">
        <text class="card-label">提现金额（元）</text>
        <input
          v-model="amountYuan"
          class="amount-input"
          data-testid="withdrawal-amount"
          type="digit"
          placeholder="输入金额"
        />
      </view>
      <text class="muted">
        <template v-if="minimumFen !== undefined"
          >最低 ¥{{ (minimumFen / 100).toFixed(2) }}
        </template>
        <template v-if="maximumFen !== undefined"
          >最高 ¥{{ (maximumFen / 100).toFixed(2) }}</template
        >
        <template v-if="minimumFen === undefined && maximumFen === undefined"
          >最低提现金额与额度以服务端返回为准。</template
        >
      </text>
    </view>

    <view v-if="state.pendingKey" class="ui-card recovery-card">
      <text class="card-title">原提现申请仍在处理中</text>
      <text class="muted"
        >¥{{
          ((state.pendingAmountFen ?? 0) / 100).toFixed(2)
        }}
        的申请尚未确认，请查询原单后再继续。</text
      >
      <view class="action-row">
        <button :disabled="state.loading" class="ui-btn ui-btn--primary" @click="recoverPending">
          查询原单
        </button>
        <button :disabled="state.loading" class="ui-btn" @click="retryPending">按原金额重试</button>
      </view>
    </view>

    <view class="ui-card receipt-card">
      <text class="card-title">收款方式</text>
      <view class="receipt-row">
        <text>微信商家转账</text>
        <text class="receipt-status">{{ receiptStatus }}</text>
      </view>
      <text class="muted">部分转账需要在微信确认收款。处理状态以原提现单查询结果为准。</text>
    </view>

    <view v-if="pendingRecord?.status === 'USER_ACTION_REQUIRED'" class="ui-card action-card">
      <text class="card-title">需要确认微信收款</text>
      <text class="muted">微信支付要求收款人主动确认，确认后系统会继续处理。</text>
      <view class="action-row">
        <button class="ui-btn ui-btn--primary" @click="confirmReceipt">立即确认收款</button>
        <button class="ui-btn" @click="refreshRecord">刷新状态</button>
      </view>
    </view>

    <view v-if="pendingRecord" class="ui-card record-card">
      <text class="card-title">当前提现单</text>
      <text>单号：{{ pendingRecord.withdrawalNo }}</text>
      <text class="muted">状态：{{ withdrawalStatusLabel(pendingRecord.status) }}</text>
      <button
        v-if="['PROCESSING', 'UNKNOWN', 'REQUESTED'].includes(pendingRecord.status)"
        class="ui-btn"
        @click="refreshRecord"
      >
        刷新状态
      </button>
    </view>

    <view class="withdrawal-footer">
      <button
        class="ui-btn ui-btn--primary submit-btn"
        :disabled="!canSubmit || state.loading"
        @click="submit"
      >
        {{ submitting ? '正在提交…' : '确认提现' }}
      </button>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.withdrawal-page {
  padding: 16px 16px calc(112px + env(safe-area-inset-bottom));
}
.muted {
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
}
.balance-card,
.recovery-card,
.receipt-card,
.action-card,
.record-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid $color-border-light;
  border-radius: 13px;
  box-shadow: none;
}
.card-label {
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
}
.card-title {
  color: $color-text-main;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.55;
}
.balance-card :deep(.ui-price) {
  color: $color-text-main;
  font-weight: 650;
}
.balance-card :deep(.ui-price__symbol),
.balance-card :deep(.ui-price__int),
.balance-card :deep(.ui-price__dec) {
  font-size: 24px;
  font-weight: 650;
  line-height: 1.4;
}
.balance-meta {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid $color-border-light;
  color: $color-text-sub;
  font-size: 22rpx;
  flex-wrap: wrap;
}
.form-card {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.amount-input {
  min-width: 0;
  min-height: 44px;
  height: 44px;
  padding: 8px 11px;
  box-sizing: border-box;
  border: 1px solid $color-border;
  border-radius: 7px;
  background: white;
  color: $color-text-main;
  font-size: 13px;
  line-height: 1.55;
}
.receipt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 14px;
  line-height: 1.55;
}
.receipt-status {
  padding: 3px 9px;
  border-radius: 5px;
  background: #edf3ff;
  color: $color-primary;
  font-size: 12px;
  flex-shrink: 0;
}
.withdrawal-footer {
  position: fixed;
  z-index: 20;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 12px 16px calc(20px + env(safe-area-inset-bottom));
  border-top: 1px solid $color-border-light;
  background: white;
}
.submit-btn {
  width: 100%;
  min-height: 44px;
  margin: 0;
  padding: 8px 14px;
  border: 1px solid $color-primary;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.55;
}
.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.action-row .ui-btn {
  flex: 1;
  min-width: 220rpx;
}
.success-card {
  padding: 24rpx 28rpx;
  margin-bottom: 24rpx;
  border-radius: 16rpx;
  background: #eefaf3;
  color: #18794e;
  font-size: 26rpx;
}
</style>
