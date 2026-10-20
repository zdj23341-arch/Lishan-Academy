<script setup lang="ts">
import {
  customerMessage,
  commissionStatusLabel,
  withdrawalStatusLabel,
  withdrawalReason,
} from '../../utils/customer-text';
import { formatShanghaiDateTime } from '@jxsc/shared';
import { ref, computed, onUnmounted } from 'vue';
import { onShow, onHide } from '@dcloudio/uni-app';
import PriceText from '../../components/PriceText.vue';
import {
  validateWithdrawalAmount,
  WithdrawalController,
  type DistributorSummary,
  type WithdrawalRecord,
} from '@jxsc/shared';
import {
  distributionApi,
  withdrawalKey,
  wechatReceiptConfirmationAdapter,
} from '../../api/distribution';
import { session, sessionStorage, operationNamespace } from '../../api/session';

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
const balance = computed(() => ({
  pendingSettlementFen: summary.value.pendingFen,
  withdrawableFen: summary.value.availableFen,
  inWithdrawalFen: summary.value.withdrawingFen,
  totalWithdrawnFen: summary.value.paidOutFen,
  debtFen: summary.value.debtFen,
}));
const minimumFen = computed(() => summary.value.limits.minFen ?? summary.value.limits.minAmountFen);
const maximumFen = computed(() => summary.value.limits.maxFen ?? summary.value.limits.maxAmountFen);
const distributorLabel = computed(() => {
  const status = summary.value.status;
  if (!status || status === 'ACTIVE') return '分销员';
  if (status === 'DISABLED') return '分销档案已停用';
  if (status === 'RISK_BLOCKED') return '提现受限';
  return '分销状态待确认';
});
const receiptStatus = computed(() =>
  summary.value.receiptReady === true
    ? '已绑定'
    : summary.value.receiptReady === false
      ? '未绑定'
      : '绑定状态待确认',
);
const canBindReceipt = computed(
  () => summary.value.status !== 'DISABLED' && summary.value.receiptReady !== true,
);
const showWithdrawModal = ref(false),
  withdrawAmountYuan = ref(''),
  submitting = ref(false),
  loading = ref(false);
const commissions = ref<
  Array<{
    id: string;
    orderNo: string;
    goodsTitle: string;
    commissionFen: number;
    ratePercent: string;
    status: string;
    statusText: string;
    estimatedSettledAt: string;
  }>
>([]);
const withdrawals = ref<WithdrawalRecord[]>([]),
  withdrawalPage = ref(1),
  withdrawalTotal = ref(0);
const realName = ref(''),
  bindingReceipt = ref(false),
  pageError = ref('');
const controller = new WithdrawalController(distributionApi, wechatReceiptConfirmationAdapter, {
  session,
  storage: sessionStorage,
  namespace: operationNamespace,
});
const controllerState = ref({
  currentRecord: controller.currentRecord,
  pendingKey: controller.pendingKey,
  error: controller.error,
  polling: controller.polling,
});
const unsubscribeController = controller.subscribe(() => {
  controllerState.value = {
    currentRecord: controller.currentRecord,
    pendingKey: controller.pendingKey,
    error: controller.error,
    polling: controller.polling,
  };
  if (controller.currentRecord)
    withdrawals.value = withdrawals.value.map((record) =>
      String(record.id) === String(controller.currentRecord!.id)
        ? controller.currentRecord!
        : record,
    );
});
const pendingActionRecord = computed(() =>
  controllerState.value.currentRecord?.status === 'USER_ACTION_REQUIRED'
    ? controllerState.value.currentRecord
    : (withdrawals.value.find((w) => w.status === 'USER_ACTION_REQUIRED') ?? null),
);
let epoch = 0;
const unsubscribe = session.subscribe(() => {
  epoch++;
  summary.value = emptySummary();
  commissions.value = [];
  withdrawals.value = [];
  withdrawalPage.value = 1;
  withdrawalTotal.value = 0;
  showWithdrawModal.value = false;
  withdrawAmountYuan.value = '';
  realName.value = '';
  loading.value = false;
  submitting.value = false;
  pageError.value = session.get() ? '账户已变化，请刷新本人分销数据' : '请先登录后查看本人分销数据';
});
async function loadData() {
  const ticket = ++epoch;
  if (!session.get()) {
    pageError.value = '请先登录后查看本人分销数据';
    return;
  }
  loading.value = true;
  pageError.value = '';
  try {
    const [value, comms, history] = await Promise.all([
      distributionApi.getSummary(),
      distributionApi.getCommissions({ page: 1, pageSize: 20 }),
      distributionApi
        .listWithdrawals(withdrawalPage.value, 10)
        .catch(() => ({ items: [], total: 0 })),
    ]);
    if (ticket !== epoch) return;
    summary.value = { ...emptySummary(), ...value };
    withdrawals.value = history.items;
    withdrawalTotal.value = history.total;
    commissions.value = comms.items.map((item) => {
      const order = String(item.orderId ?? item.orderNo ?? '');
      const status = item.state ?? item.status ?? 'UNKNOWN';
      return {
        id: item.id,
        orderNo:
          order.length > 5 ? order.replace(/^(.{2})(.*)(.{3})$/, '$1****$3') : order || '****',
        goodsTitle: item.goodsTitle || '分销订单商品',
        commissionFen: item.earnedFen ?? item.commissionFen ?? item.remainingFen ?? 0,
        ratePercent: `${(item.rateBps / 100).toFixed(2)}%`,
        status,
        statusText: commissionStatusLabel(status),
        estimatedSettledAt:
          item.eligibleAt || item.settledAt
            ? formatShanghaiDateTime(item.eligibleAt ?? item.settledAt)
            : '售后期满自动结算',
      };
    });
  } catch (cause) {
    if (ticket !== epoch) return;
    summary.value = emptySummary();
    commissions.value = [];
    withdrawals.value = [];
    withdrawalTotal.value = 0;
    pageError.value = customerMessage(cause, '加载分销数据失败');
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}
onShow(() => {
  void loadData();
  if (controller.pendingKey) void recoverWithdrawal();
});
onHide(() => controller.pause());
onUnmounted(() => {
  epoch++;
  unsubscribe();
  unsubscribeController();
  controller.dispose();
});
async function changeWithdrawalPage(delta: number) {
  withdrawalPage.value += delta;
  await loadData();
}
async function bindReceiptProfile() {
  if (bindingReceipt.value || !summary.value.distributorId || !canBindReceipt.value) return;
  const ticket = epoch;
  bindingReceipt.value = true;
  try {
    await distributionApi.bindReceiptProfile(realName.value);
    if (ticket !== epoch) return;
    realName.value = '';
    await loadData();
    uni.showToast({ title: '已绑定本人收款身份，请核对最新提现状态', icon: 'none' });
  } catch (cause) {
    if (ticket === epoch)
      uni.showToast({ title: customerMessage(cause, '绑定失败'), icon: 'none' });
  } finally {
    bindingReceipt.value = false;
  }
}
function openWithdraw() {
  if (controller.pendingKey) {
    uni.navigateTo({ url: '/pages/distribution/withdrawal' });
    return;
  }
  if (!summary.value.distributorId || !summary.value.withdrawalEnabled) {
    uni.showToast({
      title: customerMessage(summary.value.disabledReason, '请先绑定收款身份并核对提现条件'),
      icon: 'none',
    });
    return;
  }
  uni.navigateTo({ url: '/pages/distribution/withdrawal' });
}
function closeWithdraw() {
  showWithdrawModal.value = false;
}
async function confirmWithdraw() {
  if (submitting.value) return;
  if (controller.pendingKey) {
    await recoverWithdrawal();
    return;
  }
  const valid = validateWithdrawalAmount(withdrawAmountYuan.value, summary.value);
  if (!valid.valid) {
    uni.showToast({ title: customerMessage(valid.error, '提现金额无效'), icon: 'none' });
    return;
  }
  const ticket = epoch;
  submitting.value = true;
  try {
    const record = await controller.submitWithdrawal(valid.amountFen, withdrawalKey());
    if (ticket !== epoch) return;
    showWithdrawModal.value = false;
    await loadData();
    uni.showToast({ title: `提现单已受理：${withdrawalStatusLabel(record.status)}`, icon: 'none' });
  } catch (cause) {
    if (ticket === epoch)
      uni.showToast({
        title: customerMessage(cause, '结果未知，请查询原单'),
        icon: 'none',
      });
  } finally {
    submitting.value = false;
  }
}
async function recoverWithdrawal() {
  const ticket = epoch;
  const record = await controller.recoverPending();
  if (ticket !== epoch) return;
  if (record) {
    showWithdrawModal.value = false;
    await loadData();
  } else
    uni.showToast({
      title: customerMessage(controller.error, '原提现结果尚未确认，请继续查询原单'),
      icon: 'none',
    });
}
async function retryOriginalWithdrawal() {
  const ticket = epoch;
  const record = await controller.retryPending();
  if (ticket !== epoch) return;
  if (record) await loadData();
  else
    uni.showToast({
      title: customerMessage(controller.error, '原提现仍待确认，请继续查询'),
      icon: 'none',
    });
}
async function handleActionConfirmation(record?: WithdrawalRecord) {
  const ticket = epoch;
  try {
    await controller.invokeReceiptConfirmation(record ? String(record.id) : undefined);
    if (ticket === epoch) await loadData();
  } catch (cause) {
    if (ticket === epoch)
      uni.showToast({ title: customerMessage(cause, '确认失败'), icon: 'none' });
  }
}
async function refreshWithdrawalRecord(id: string) {
  const ticket = epoch;
  try {
    await controller.refreshCurrent(String(id));
    if (ticket === epoch) await loadData();
  } catch (cause) {
    if (ticket === epoch)
      uni.showToast({ title: customerMessage(cause, '刷新失败'), icon: 'none' });
  }
}
function shareProduct() {
  uni.navigateTo({ url: '/pages/product/list' });
}
</script>

<template>
  <view class="ls-mini-page distribution-page ui-page-container">
    <view v-if="loading" role="status" class="ls-status">正在更新分销数据…</view>
    <view v-if="pageError" role="alert" class="ls-alert"
      >{{ pageError }}<button @click="loadData">刷新</button></view
    >
    <view v-if="controllerState.pendingKey" class="ui-card recovery-card">
      <text
        >原提现申请 ¥{{
          ((controller.pendingAmountFen ?? 0) / 100).toFixed(2)
        }}
        尚未结束，请查询原单后再申请。</text
      >
      <button :disabled="submitting" @click="recoverWithdrawal">查询原提现</button>
      <button :disabled="submitting || controller.loading" @click="retryOriginalWithdrawal">
        按原金额重试原提现
      </button>
      <text v-if="controllerState.error">{{
        customerMessage(controllerState.error, '原提现结果尚未确认，请继续查询原单')
      }}</text>
    </view>
    <!-- 头部专属分销员资产看板 -->
    <view v-if="summary.distributorId" class="asset-dashboard-card ui-card">
      <view class="dash-top">
        <view class="agent-info">
          <text class="agent-badge">{{ distributorLabel }}</text>
          <text class="agent-desc">结算金额以当前账单为准</text>
        </view>
        <button
          class="withdraw-trigger-btn"
          :disabled="!summary.distributorId || submitting"
          @click="openWithdraw"
        >
          申请提现
        </button>
      </view>

      <!-- 核心大字：可提现余额 -->
      <view class="main-balance-box">
        <text class="label">可提现余额（元）</text>
        <view class="balance-num-row">
          <PriceText mode="amount" :fen="balance.withdrawableFen" size="hero" />
        </view>
      </view>

      <!-- 三格资金资产流水明细 -->
      <view class="sub-balance-grid">
        <view class="sub-col">
          <text class="sub-num">¥{{ (balance.pendingSettlementFen / 100).toFixed(2) }}</text>
          <text class="sub-label">待结算佣金</text>
        </view>
        <view class="sub-col">
          <text class="sub-num">¥{{ (balance.inWithdrawalFen / 100).toFixed(2) }}</text>
          <text class="sub-label">转账处理中 </text>
        </view>
        <view class="sub-col">
          <text class="sub-num">¥{{ (balance.totalWithdrawnFen / 100).toFixed(2) }}</text>
          <text class="sub-label">累计已到账 </text>
        </view>
      </view>
      <view class="debt-row"
        ><text>退款欠款</text><text>¥{{ (balance.debtFen / 100).toFixed(2) }}</text></view
      >
    </view>

    <view v-if="summary.distributorId" class="ui-card receipt-card">
      <text class="receipt-title">微信收款身份</text>
      <text data-testid="receipt-status" class="receipt-description">{{ receiptStatus }}</text>
      <text class="receipt-description"
        >请绑定本人微信收款身份。渠道要求实名时，填写本人姓名。</text
      >
      <input
        v-model="realName"
        data-testid="receipt-real-name"
        maxlength="64"
        placeholder="本人收款实名（可选）"
      />
      <button
        class="ls-button ls-button--soft"
        :disabled="bindingReceipt || !canBindReceipt"
        @click="bindReceiptProfile"
      >
        绑定本人微信收款身份
      </button>
      <text v-if="!summary.withdrawalEnabled" class="receipt-description">{{
        customerMessage(summary.disabledReason, '当前暂不支持提现，请联系客服')
      }}</text>
    </view>

    <!-- 待确认收款通知卡片 -->
    <view v-if="pendingActionRecord" class="ui-card pending-action-card">
      <view class="pending-action-header">
        <text class="pending-action-title"> 提现待确认收款</text>
        <text class="pending-action-no">单号：{{ pendingActionRecord.withdrawalNo }}</text>
      </view>
      <view class="pending-action-body">
        <text class="pending-action-desc"
          >微信支付要求收款人主动确认。确认后系统将自动推进出款。</text
        >
        <view class="pending-action-btns">
          <button
            class="ui-btn ui-btn--primary"
            @click="handleActionConfirmation(pendingActionRecord)"
          >
            立即确认收款
          </button>
          <button class="ui-btn" @click="refreshWithdrawalRecord(pendingActionRecord.id)">
            刷新状态
          </button>
        </view>
      </view>
    </view>

    <!-- 佣金记录明细卡片 -->
    <view v-if="summary.distributorId" class="commission-section ui-card">
      <view class="card-header">
        <view class="accent-line" />
        <text class="header-title">最近佣金</text>
      </view>

      <view class="commission-list">
        <text v-if="!commissions.length" class="receipt-description">暂无佣金记录</text>
        <view v-for="comm in commissions" :key="comm.id" class="comm-item">
          <view class="comm-left">
            <text class="goods-name text-ellipsis">{{ comm.goodsTitle }}</text>
            <view class="order-meta">
              <text class="order-no">订单号：{{ comm.orderNo }}</text>
              <text class="rate-badge">比例 {{ comm.ratePercent }}</text>
            </view>
            <text class="settle-time">预计结算日：{{ comm.estimatedSettledAt }}</text>
          </view>

          <view class="comm-right">
            <text class="comm-amount">+¥{{ (comm.commissionFen / 100).toFixed(2) }}</text>
            <view
              class="comm-status-tag"
              :class="{
                'comm-status-tag--avail': comm.status === 'AVAILABLE',
                'comm-status-tag--pend': comm.status === 'PENDING',
              }"
            >
              {{ comm.statusText }}
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 提现单据记录明细卡片 -->
    <view v-if="withdrawals.length" class="withdrawal-section ui-card">
      <view class="card-header">
        <view class="accent-line" />
        <text class="header-title">提现转账记录</text>
      </view>

      <view class="withdrawal-list">
        <view v-for="item in withdrawals" :key="item.id" class="comm-item">
          <view class="comm-left">
            <text class="goods-name text-ellipsis">微信转账 {{ item.withdrawalNo }}</text>
            <text v-if="item.errorCode || item.failReason || item.failedReason" class="settle-time"
              >失败/渠道提示：{{ withdrawalReason(item) }}</text
            >
            <text class="settle-time">申请时间：{{ formatShanghaiDateTime(item.createdAt) }}</text>
          </view>
          <view class="comm-right">
            <text class="comm-amount">-¥{{ (item.amountFen / 100).toFixed(2) }}</text>
            <view class="comm-status-tag">
              {{ withdrawalStatusLabel(item.status) }}
            </view>
            <button
              v-if="item.status === 'USER_ACTION_REQUIRED'"
              size="mini"
              class="ui-btn ui-btn--primary"
              style="margin-top: 8rpx; font-size: 24rpx"
              @click.stop="handleActionConfirmation(item)"
            >
              去确认
            </button>
            <button
              v-else-if="['PROCESSING', 'UNKNOWN', 'REQUESTED'].includes(item.status)"
              size="mini"
              class="ui-btn"
              style="margin-top: 8rpx; font-size: 24rpx"
              @click.stop="refreshWithdrawalRecord(item.id)"
            >
              刷新
            </button>
          </view>
        </view>
      </view>
    </view>

    <view v-if="withdrawalTotal > 10" class="ui-card">
      <button :disabled="withdrawalPage <= 1 || loading" @click="changeWithdrawalPage(-1)">
        上一页提现
      </button>
      <text>第 {{ withdrawalPage }} 页 / 共 {{ withdrawalTotal }} 条</text>
      <button
        :disabled="withdrawalPage * 10 >= withdrawalTotal || loading"
        @click="changeWithdrawalPage(1)"
      >
        下一页提现
      </button>
    </view>
    <!-- 推广获客技巧卡片 -->
    <view v-if="summary.distributorId" class="share-tips-card ui-card">
      <text class="tips-title">结算与提现说明</text>
      <text class="tips-text"
        >佣金经过售后观察期后可提现，处理中金额不计入可提现余额。归因与结算期限以平台当前规则为准。</text
      >
      <text class="tips-text">部分微信转账需要确认收款；结果未知时请查询原单。</text>
      <button
        class="ui-btn ui-btn--primary"
        style="margin-top: 20rpx; font-size: 26rpx"
        @click="shareProduct"
      >
        去精选商品分享推广
      </button>
    </view>

    <!-- 申请提现拟态弹窗 -->
    <view v-if="showWithdrawModal" class="modal-mask" @click="closeWithdraw">
      <view class="modal-card" @click.stop>
        <view class="modal-header">
          <text class="modal-title">申请提现</text>
          <button class="close-x" aria-label="关闭提现窗口" @click="closeWithdraw">×</button>
        </view>

        <view class="modal-body">
          <text class="field-hint"
            >当前可提现金额：¥{{ (balance.withdrawableFen / 100).toFixed(2) }}</text
          >
          <view class="input-money-box">
            <text class="money-sym">¥</text>
            <input
              v-model="withdrawAmountYuan"
              type="digit"
              class="money-input"
              placeholder="请输入提现金额"
            />
          </view>
          <text class="withdraw-rule-tip">
            <template v-if="minimumFen !== undefined"
              >最低提现金额 ¥{{ (minimumFen / 100).toFixed(2) }}<br
            /></template>
            <template v-if="maximumFen !== undefined"
              >单笔最高 ¥{{ (maximumFen / 100).toFixed(2) }}<br
            /></template>
            实际额度以服务端校验为准。微信转账可能需要确认收款。
          </text>
        </view>

        <view class="modal-footer">
          <button class="ui-btn ui-btn--outline cancel-btn" @click="closeWithdraw">取消</button>
          <button class="ui-btn ui-btn--primary confirm-btn" @click="confirmWithdraw">
            确认提现
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.withdraw-trigger-btn {
  flex-shrink: 0;
  min-width: 150rpx;
  white-space: nowrap;
  padding: 0 20rpx;
}

.distribution-page {
  padding: 32rpx;
}
.distribution-page > .ui-card {
  margin: 0 0 24rpx;
}
.recovery-card,
.receipt-card,
.pending-action-card,
.withdrawal-section {
  padding: 28rpx;
}
.receipt-card {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.receipt-title {
  font-size: 30rpx;
  font-weight: 600;
}
.receipt-description {
  font-size: 24rpx;
  color: $color-text-sub;
  line-height: 1.7;
}
.receipt-card input {
  min-height: 88rpx;
  padding: 20rpx;
  border-radius: 16rpx;
  background: $color-bg-page;
}
.recovery-card button {
  margin-top: 16rpx;
  font-size: 26rpx;
  background: $color-primary-light;
  color: $color-primary;
}
.pending-action-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 20rpx;
}
.pending-action-header,
.pending-action-body {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.debt-row {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding-top: 24rpx;
  font-size: 24rpx;
  color: $color-text-sub;
}

.asset-dashboard-card {
  padding: 32rpx 28rpx;
  background: white;
  color: #18283e;
  margin-bottom: 24rpx;
  border: 1rpx solid #e5ebf3;
  box-shadow: none;

  .dash-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 24rpx;
    gap: 20rpx;
    .agent-info {
      flex: 1;
      min-width: 0;
    }

    .agent-badge {
      font-size: 26rpx;
      font-weight: 700;
      color: #2855b7;
      display: block;
      margin-bottom: 4rpx;
    }

    .agent-desc {
      font-size: 24rpx;
      color: #617088;
    }

    .withdraw-trigger-btn {
      height: 88rpx;
      line-height: 88rpx;
      padding: 0 24rpx;
      border-radius: 16rpx;
      background: $color-primary;
      color: #fff;
      font-size: 24rpx;
      font-weight: 700;
      border: none;
      box-shadow: none;

      &::after {
        display: none;
      }
      &:active {
        opacity: 0.9;
      }
    }
  }

  .main-balance-box {
    padding: 16rpx 0 24rpx;
    border-bottom: 1rpx solid #e5ebf3;

    .label {
      font-size: 24rpx;
      color: #617088;
      display: block;
      margin-bottom: 8rpx;
    }

    .balance-num-row {
      display: flex;
      align-items: baseline;
    }
  }

  .sub-balance-grid {
    display: flex;
    justify-content: space-around;
    padding-top: 24rpx;

    .sub-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6rpx;

      .sub-num {
        font-size: 28rpx;
        font-weight: 700;
        color: #18283e;
        font-family: -apple-system, 'DIN Alternate', sans-serif;
      }

      .sub-label {
        font-size: 24rpx;
        color: #617088;
      }
    }
  }
}

.commission-section {
  padding: 28rpx 24rpx;
  margin-bottom: 24rpx;

  .card-header {
    display: flex;
    align-items: center;
    gap: 12rpx;
    padding-bottom: 20rpx;
    border-bottom: 1rpx solid $color-border-light;
    margin-bottom: 12rpx;

    .accent-line {
      width: 6rpx;
      height: 28rpx;
      background: $color-primary;
      border-radius: 3rpx;
    }

    .header-title {
      font-size: 30rpx;
      font-weight: 700;
      color: $color-text-main;
    }
  }
}

.commission-list {
  display: flex;
  flex-direction: column;
}

.comm-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid $color-border-light;

  &:last-child {
    border-bottom: none;
  }

  .comm-left {
    min-width: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
    margin-right: 20rpx;

    .goods-name {
      font-size: 26rpx;
      font-weight: 600;
      color: $color-text-main;
    }

    .order-meta {
      display: flex;
      align-items: center;
      gap: 12rpx;
      flex-wrap: wrap;

      .order-no {
        font-size: 24rpx;
        color: $color-text-secondary;
      }

      .rate-badge {
        font-size: 24rpx;
        background: #f0f4fa;
        color: $color-primary;
        padding: 2rpx 8rpx;
        border-radius: 4rpx;
      }
    }

    .settle-time {
      font-size: 24rpx;
      color: $color-text-sub;
    }
  }

  .comm-right {
    flex-shrink: 0;
    max-width: 220rpx;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 8rpx;

    .comm-amount {
      white-space: nowrap;
      font-size: 32rpx;
      font-weight: 700;
      color: $color-price;
      font-family: -apple-system, 'DIN Alternate', sans-serif;
    }

    .comm-status-tag {
      font-size: 24rpx;
      padding: 2rpx 10rpx;
      border-radius: 4rpx;

      &--avail {
        background: $color-accent-light;
        color: $color-accent;
      }

      &--pend {
        background: $color-warning-light;
        color: $color-warning;
      }
    }
  }
}

.share-tips-card {
  padding: 24rpx;
  background: #fffbf2;
  border: 1rpx solid #ffe6af;

  .tips-title {
    font-size: 26rpx;
    font-weight: 700;
    color: #92400e;
    display: block;
    margin-bottom: 10rpx;
  }

  .tips-text {
    font-size: 24rpx;
    color: #b45309;
    line-height: 1.5;
    display: block;
    margin-bottom: 6rpx;
  }
}

// 弹窗
.modal-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40rpx;
}

.modal-card {
  width: 100%;
  max-width: 620rpx;
  max-height: calc(100vh - 80rpx);
  overflow-y: auto;
  background: #ffffff;
  border-radius: $radius-lg;
  padding: 32rpx;
  box-shadow: 0 16rpx 40rpx rgba(0, 0, 0, 0.15);

  .modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 24rpx;

    .modal-title {
      font-size: 32rpx;
      font-weight: 700;
      color: $color-text-main;
    }

    .close-x {
      width: 88rpx;
      padding: 0;
      background: transparent;
      font-size: 40rpx;
      color: $color-text-muted;
      cursor: pointer;
    }
  }

  .modal-body {
    .field-hint {
      font-size: 24rpx;
      color: $color-text-secondary;
      margin-bottom: 16rpx;
      display: block;
    }

    .input-money-box {
      display: flex;
      align-items: center;
      border-bottom: 2rpx solid $color-border;
      padding-bottom: 12rpx;
      margin-bottom: 20rpx;

      .money-sym {
        font-size: 44rpx;
        font-weight: 700;
        color: $color-text-main;
        margin-right: 12rpx;
      }

      .money-input {
        flex: 1;
        min-width: 0;
        font-size: 44rpx;
        font-weight: 700;
        color: $color-text-main;
      }
    }

    .withdraw-rule-tip {
      font-size: 24rpx;
      color: $color-text-muted;
      line-height: 1.5;
    }
  }

  .modal-footer {
    display: flex;
    gap: 20rpx;
    margin-top: 32rpx;

    .cancel-btn {
      flex: 1;
      height: 76rpx;
      font-size: 28rpx;
    }

    .confirm-btn {
      flex: 1;
      height: 76rpx;
      font-size: 28rpx;
    }
  }
}
</style>
