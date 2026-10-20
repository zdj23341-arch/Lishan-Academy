<script setup lang="ts">
import {
  customerMessage,
  orderStatusLabel,
  fulfillmentStatusLabel,
  aftersaleStatusLabel,
} from '../../utils/customer-text';
import { formatShanghaiDateTime } from '@jxsc/shared';
import UiIcon from '../../components/ui/UiIcon.vue';
import UiState from '../../components/ui/UiState.vue';
import BookCover from '../../components/ui/BookCover.vue';
import { ref, computed, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import {
  createOrderActionController,
  type CustomerOrder,
} from '../../../../shared/src/commerce/order';
import { createPaymentController } from '../../../../shared/src/commerce/payment';
import { createAftersaleController } from '../../../../shared/src/commerce/aftersale';
import { aftersaleApi } from '../../api/aftersale';
import { canLearn, createCatalogApi, formatFen } from '@jxsc/shared';
import { client } from '../../api/client';
import { orderApi, paymentApi } from '../../api/order';
import { platformPayment } from '../../api/payment';
import { operationKey } from '../../api/commerce';
import { session, sessionStorage, operationNamespace } from '../../api/session';
import { environment } from '../../api/environment';
import { regionLabel } from '../../utils/regions';

const raw = ref<CustomerOrder | null>(null),
  busy = ref(false),
  error = ref('');
const paymentStatus = ref(''),
  automaticQueries = ref(0),
  cancelPending = ref(false),
  receiptPending = ref(false);
const simulatorPending = ref(false);
const refundPending = ref(false),
  journalAftersaleId = ref('');
let id = '',
  epoch = 0;
let checkoutPaymentPending = false;
let cancel: ReturnType<typeof createOrderActionController> | null = null;
let receipt: ReturnType<typeof createOrderActionController> | null = null;
let payment: ReturnType<typeof createPaymentController> | null = null;
let aftersale: ReturnType<typeof createAftersaleController> | null = null;
const validCaseId = (value: unknown) => (typeof value === 'string' && value.trim() ? value : '');
const aftersaleId = computed(() => {
  if (!raw.value) return '';
  const serverId = (raw.value as CustomerOrder & { aftersaleId?: unknown }).aftersaleId;
  return validCaseId(serverId) || journalAftersaleId.value;
});
const isDev = environment.mode !== 'production';
const can = (action: string) => raw.value?.allowedActions.includes(action) ?? false;
const catalog = createCatalogApi(client);
const order = computed(() => {
  const value = raw.value;
  if (!value) return null;
  const source = value.address as Record<string, unknown> | null;
  let address: { name: string; phone: string; detail: string } | null = null;
  if (
    source &&
    ['name', 'phone', 'detail', 'provinceCode', 'cityCode', 'districtCode'].every(
      (k) => typeof source[k] === 'string',
    )
  )
    address = {
      name: source.name as string,
      phone: source.phone as string,
      detail: `${regionLabel([source.provinceCode as string, source.cityCode as string, source.districtCode as string])} ${source.detail}`,
    };
  return {
    ...value,
    status: value.orderStatus,
    statusText: orderStatusLabel(value.orderStatus),
    statusTip: `${fulfillmentStatusLabel(value.fulfillmentStatus)} · ${aftersaleStatusLabel(value.refundStatus)}`,
    address,
    goods: { ...value.item, priceFen: value.item.unitPriceFen },
    freightFen: value.freightAmountFen,
    payableFen: value.payableAmountFen,
  };
});
function sync() {
  paymentStatus.value =
    payment?.operation.state.status === 'UNKNOWN' ? 'UNKNOWN' : (payment?.state.status ?? '');
  automaticQueries.value = payment?.state.automaticQueries ?? 0;
  cancelPending.value =
    !!cancel && ['UNKNOWN', 'SUBMITTING'].includes(cancel.operation.state.status);
  receiptPending.value =
    !!receipt && ['UNKNOWN', 'SUBMITTING'].includes(receipt.operation.state.status);
  simulatorPending.value =
    !!payment && ['UNKNOWN', 'SUBMITTING'].includes(payment.simulator.state.status);
  refundPending.value =
    !!aftersale && ['UNKNOWN', 'SUBMITTING'].includes(aftersale.refundOperation.state.status);
  journalAftersaleId.value =
    aftersale?.refundOperation.state.status === 'SUCCEEDED'
      ? validCaseId(aftersale.refundOperation.state.resourceId)
      : '';
}
function disposeControllers() {
  cancel?.dispose();
  receipt?.dispose();
  payment?.dispose();
  aftersale?.dispose();
}
function setupAftersale() {
  aftersale?.dispose();
  aftersale = createAftersaleController({
    session,
    storage: sessionStorage,
    namespace: operationNamespace,
    orderId: id,
    getOrder: () => raw.value,
    onOrder: (value) => {
      raw.value = value;
    },
    keyGenerator: operationKey,
    api: aftersaleApi,
    orderApi,
  });
}
function setupControllers() {
  disposeControllers();
  const options = {
    session,
    storage: sessionStorage,
    namespace: operationNamespace,
    orderId: id,
    getOrder: () => raw.value,
    onOrder: (value: CustomerOrder) => {
      raw.value = value;
    },
    keyGenerator: operationKey,
  };
  cancel = createOrderActionController({ ...options, api: orderApi, action: 'CANCEL' });
  receipt = createOrderActionController({ ...options, api: orderApi, action: 'CONFIRM_RECEIPT' });
  payment = createPaymentController({
    ...options,
    api: paymentApi,
    orderApi,
    adapter: platformPayment,
    mode: environment.mode,
  });
  setupAftersale();
  sync();
}
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(`/pages/order/detail?id=${encodeURIComponent(id)}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
const unsubscribe = session.subscribe(() => {
  checkoutPaymentPending = false;
  epoch++;
  raw.value = null;
  error.value = '';
  busy.value = false;
  paymentStatus.value = '';
  automaticQueries.value = 0;
  cancelPending.value = receiptPending.value = refundPending.value = false;
  journalAftersaleId.value = '';
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
  disposeControllers();
});
async function run(action: () => Promise<unknown>, showPaymentError = false) {
  if (busy.value || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '订单操作失败');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      sync();
      if (showPaymentError && !error.value)
        error.value = payment?.state.error
          ? customerMessage(payment.state.error, '付款结果待确认，请查询订单状态')
          : '';
    }
  }
}
async function load() {
  await run(async () => {
    if (!id) throw new Error('缺少订单编号');
    const ticket = epoch;
    const value = await orderApi.order(id);
    if (ticket === epoch) {
      raw.value = value;
      setupAftersale();
      if (checkoutPaymentPending) {
        checkoutPaymentPending = false;
        if (value.orderStatus === 'PENDING_PAYMENT' && can('PAY')) await payment!.pay();
      }
    }
  }, true);
}
function confirmAction(action: 'CANCEL' | 'CONFIRM_RECEIPT') {
  if (busy.value || !can(action) || !authorized()) return;
  const ticket = epoch;
  uni.showModal({
    title: action === 'CANCEL' ? '取消订单' : '确认收货',
    content: action === 'CANCEL' ? '确认取消此订单？' : '请确认已经收到商品并查验无误。',
    success: (result) => {
      if (result.confirm && ticket === epoch)
        void run(() => (action === 'CANCEL' ? cancel!.submit() : receipt!.submit()));
    },
  });
}
function copyOrderNo() {
  if (raw.value) uni.setClipboardData({ data: raw.value.orderNo });
}
function viewLogistics() {
  if (can('VIEW_LOGISTICS'))
    uni.navigateTo({ url: `/pages/order/logistics?orderId=${encodeURIComponent(id)}` });
}
function applyAftersale() {
  if (can('APPLY_AFTERSALE'))
    uni.navigateTo({ url: `/pages/aftersale/apply?orderId=${encodeURIComponent(id)}` });
}
function viewAftersale() {
  if (aftersaleId.value && authorized())
    uni.navigateTo({ url: `/pages/aftersale/detail?id=${encodeURIComponent(aftersaleId.value)}` });
}
function openPapers() {
  if (busy.value || !can('VIEW_CONTENT') || raw.value?.item.type !== 'MEMBERSHIP' || !authorized())
    return;
  uni.navigateTo({ url: '/pages/paper/list' });
}
function learn() {
  if (!can('VIEW_CONTENT') || raw.value?.item.type !== 'COURSE') return;
  void run(async () => {
    const ticket = epoch;
    const product = await catalog.product(raw.value!.item.productId);
    if (ticket !== epoch) return;
    if (product.type !== 'COURSE' || !product.course.courseId || !canLearn(product))
      throw new Error('课程权益不可用，请刷新订单状态');
    uni.navigateTo({
      url: `/pages/course/learn?id=${encodeURIComponent(product.course.courseId)}&productId=${encodeURIComponent(product.id)}`,
    });
  });
}
function recoverAftersale() {
  if (aftersale)
    void run(async () => {
      const ticket = epoch;
      await aftersale!.recoverRefund();
      if (ticket === epoch) {
        sync();
        viewAftersale();
      }
    });
}
function pay() {
  if (payment) void run(() => payment!.pay(), true);
}
function recoverPayment() {
  if (payment) void run(() => payment!.recover(), true);
}
function recoverSimulation() {
  if (payment)
    void run(async () => {
      await payment!.simulator.recover();
      if (payment!.simulator.state.status === 'SUCCEEDED') await payment!.refresh();
    });
}
function simulate(scenario: string) {
  if (payment && isDev) void run(() => payment!.simulate(scenario));
}
function recoverAction(action: 'CANCEL' | 'CONFIRM_RECEIPT') {
  void run(() => (action === 'CANCEL' ? cancel!.recover() : receipt!.recover()));
}
onLoad((options) => {
  id = options?.id || '';
  checkoutPaymentPending = options?.pay === '1';
  setupControllers();
});
onShow(() => {
  void load();
});
</script>

<template>
  <view class="ls-mini-page order-detail-page ui-page-container">
    <UiState v-if="!order && busy" kind="loading" title="订单加载中…" />
    <UiState
      v-else-if="!order && error"
      kind="error"
      title="订单加载遇到问题"
      :description="error"
      action-label="刷新订单状态"
      @action="load"
    />
    <view
      v-if="
        (order && (error || busy)) ||
        paymentStatus === 'UNKNOWN' ||
        paymentStatus === 'PARAMS_UNAVAILABLE' ||
        automaticQueries >= 5 ||
        (isDev && simulatorPending) ||
        cancelPending ||
        receiptPending
      "
      class="order-recovery ui-card"
    >
      <view v-if="error" role="alert" class="ls-alert">{{ error }}</view>
      <view v-if="busy" class="processing-message">正在处理…</view>
      <view v-if="paymentStatus === 'UNKNOWN'">付款结果未知，请查询原付款操作。</view>
      <view v-if="paymentStatus === 'PARAMS_UNAVAILABLE'"
        >原预支付已确认，可继续获取同一笔支付参数。</view
      >
      <view v-if="automaticQueries >= 5">已完成5次自动查询，请手动刷新订单状态。</view>
      <button v-if="paymentStatus === 'UNKNOWN'" :disabled="busy" @click="recoverPayment">
        查询原付款操作
      </button>
      <button
        v-if="paymentStatus === 'PARAMS_UNAVAILABLE' && can('PAY')"
        :disabled="busy"
        @click="pay"
      >
        继续原付款
      </button>
      <button v-if="isDev && simulatorPending" :disabled="busy" @click="recoverSimulation">
        查询原模拟操作
      </button>
      <button v-if="cancelPending" :disabled="busy" @click="recoverAction('CANCEL')">
        查询取消结果
      </button>
      <button v-if="receiptPending" :disabled="busy" @click="recoverAction('CONFIRM_RECEIPT')">
        查询收货结果
      </button>
    </view>
    <template v-if="order">
      <!-- 顶部状态高亮横幅 -->
      <view class="status-banner">
        <view class="status-main">
          <text class="status-title">{{ order.statusText }}</text>
          <text class="status-tip">{{ order.statusTip }}</text>
        </view>
      </view>

      <!-- 物流简报卡片 (仅图书/已发货) -->
      <view
        v-if="can('VIEW_LOGISTICS')"
        class="logistics-preview-card ui-card"
        @click="viewLogistics"
      >
        <view class="logistics-icon"><UiIcon name="truck" tone="blue" :size="24" /></view>
        <view class="logistics-info">
          <text class="logistics-highlight">查看订单物流信息</text>
          <text class="logistics-time">物流信息以承运接口返回为准</text>
        </view>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <!-- 收货地址卡片 -->
      <view v-if="order.address" class="address-card ui-card">
        <view class="addr-icon"><UiIcon name="map-pin" tone="blue" :size="24" /></view>
        <view class="addr-content">
          <view class="addr-user">
            <text class="name">{{ order.address.name }}</text>
            <text class="phone">{{ order.address.phone }}</text>
          </view>
          <text class="detail">{{ order.address.detail }}</text>
        </view>
      </view>

      <!-- 商品清单 -->
      <view class="goods-card ui-card">
        <view class="goods-item">
          <view class="goods-cover-box">
            <BookCover
              :title="order.goods.title"
              :image-url="order.goods.coverUrl"
              :theme="order.goods.type === 'BOOK' ? 'jade' : 'blue'"
            />
          </view>
          <view class="goods-detail">
            <text class="goods-title text-ellipsis-2">{{ order.goods.title }}</text>
            <view class="goods-meta">
              <text class="goods-cover-placeholder">{{
                order.goods.type === 'BOOK'
                  ? '图书'
                  : order.goods.type === 'MEMBERSHIP'
                    ? '会员'
                    : '课程'
              }}</text>
              <text class="goods-qty"> · × {{ order.goods.quantity }}</text>
            </view>
          </view>
          <text class="goods-unit-price">¥{{ formatFen(order.goods.priceFen) }}</text>
        </view>
        <view class="fee-card">
          <view class="fee-row">
            <text class="fee-label">商品总价</text>
            <text class="fee-value">¥{{ formatFen(order.goodsAmountFen) }}</text>
          </view>
          <view class="fee-row">
            <text class="fee-label">运费</text>
            <text class="fee-value">¥{{ formatFen(order.freightFen) }}</text>
          </view>
          <view class="fee-row">
            <text class="fee-label">应付金额</text>
            <text class="fee-value">¥{{ formatFen(order.payableFen) }}</text>
          </view>
          <view v-if="order.paidAmountFen !== undefined" class="fee-row fee-row--total">
            <text class="fee-label">已付金额</text>
            <text class="fee-value">¥{{ formatFen(order.paidAmountFen) }}</text>
          </view>
          <view v-if="order.refundedAmountFen !== undefined" class="fee-row">
            <text class="fee-label">已退金额</text>
            <text class="fee-value">¥{{ formatFen(order.refundedAmountFen) }}</text>
          </view>
        </view>
      </view>

      <!-- 订单信息与次操作跟随内容，不使用固定底栏遮住最后一行。 -->
      <view class="order-info-card ui-card">
        <view class="order-info-head">
          <text class="order-info-title">订单信息</text>
          <button class="order-refresh" :disabled="busy" @click="load">刷新订单状态</button>
        </view>
        <view class="info-row info-row--number">
          <text class="info-label">订单编号</text>
          <view class="copy-box">
            <text class="info-val order-number">{{ order.orderNo }}</text>
            <button class="copy-btn" @click="copyOrderNo">复制</button>
          </view>
        </view>
        <view v-if="order.paidAt" class="info-row">
          <text class="info-label">支付时间</text>
          <text class="info-val">{{ formatShanghaiDateTime(order.paidAt) }}</text>
        </view>
        <view v-if="order.expiresAt && order.orderStatus === 'PENDING_PAYMENT'" class="info-row"
          ><text class="info-label">付款截止时间</text
          ><text class="info-val">{{ formatShanghaiDateTime(order.expiresAt) }}</text></view
        >

        <view class="order-actions">
          <button
            v-if="can('VIEW_CONTENT') && order.goods.type === 'COURSE'"
            :disabled="busy"
            class="order-action--soft"
            @click="learn"
          >
            进入课程学习
          </button>
          <button
            v-if="can('VIEW_CONTENT') && order.goods.type === 'MEMBERSHIP'"
            :disabled="busy"
            class="order-action--soft"
            @click="openPapers"
          >
            进入试卷资料库
          </button>
          <button v-if="can('CANCEL')" :disabled="busy" @click="confirmAction('CANCEL')">
            取消订单
          </button>
          <button v-if="can('PAY')" :disabled="busy" class="ui-btn--primary" @click="pay">
            立即付款
          </button>
          <button v-if="can('APPLY_AFTERSALE')" :disabled="busy" @click="applyAftersale">
            申请售后
          </button>
          <button v-if="aftersaleId" :disabled="busy" @click="viewAftersale">查看售后</button>
          <button v-else-if="refundPending" :disabled="busy" @click="recoverAftersale">
            查询原售后申请
          </button>
          <button
            v-if="can('VIEW_LOGISTICS')"
            :disabled="busy"
            class="order-action--soft"
            @click="viewLogistics"
          >
            查看物流
          </button>
          <button
            v-if="can('CONFIRM_RECEIPT')"
            :disabled="busy"
            class="ui-btn ui-btn--primary"
            @click="confirmAction('CONFIRM_RECEIPT')"
          >
            确认收货
          </button>
        </view>
      </view>
      <view v-if="isDev && can('PAY')" class="order-simulator ui-card">
        <text>开发支付场景（不产生真实付款）</text>
        <button
          v-for="scenario in ['SUCCESS', 'TIMEOUT', 'FAILURE', 'CLOSED']"
          :key="scenario"
          :disabled="busy"
          @click="simulate(scenario)"
        >
          {{
            { SUCCESS: '模拟成功', TIMEOUT: '模拟超时', FAILURE: '模拟失败', CLOSED: '模拟关闭' }[
              scenario
            ]
          }}
        </button>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.order-detail-page {
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-detail-page .ui-card {
  margin: 0;
  padding: 16px;
  border-width: 1px;
  border-radius: 13px;
}

.status-banner {
  padding: 20px;
  border-radius: 13px;
  background: $color-primary-light;
}

.status-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.status-title {
  color: $color-primary;
  font-size: 17px;
  font-weight: 650;
  line-height: 1.55;
}

.status-tip {
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.logistics-preview-card,
.address-card {
  display: flex;
  align-items: center;
  gap: 11px;
}

.logistics-preview-card {
  min-height: 76px;
}

.logistics-info,
.addr-content {
  flex: 1;
  min-width: 0;
}

.logistics-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.logistics-highlight,
.addr-user {
  color: $color-text-main;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.55;
}

.address-card {
  min-height: 100px;
}

.addr-user {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.logistics-time,
.addr-content .detail {
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.goods-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.goods-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.goods-cover-box {
  flex: 0 0 63px;
  width: 63px;
  height: 84px;
  border-radius: 8px;
  overflow: hidden;
}

.goods-detail {
  flex: 1;
  min-width: 0;
}

.goods-title {
  color: $color-text-main;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.55;
}

.goods-meta {
  margin-top: 4px;
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
}

.goods-unit-price {
  flex-shrink: 0;
  max-width: 38%;
  color: $color-price;
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.fee-card,
.order-info-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.fee-row,
.info-row,
.order-info-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  line-height: 1.55;
}

.fee-label,
.info-label {
  flex-shrink: 0;
  color: $color-text-sub;
  font-size: 12px;
}

.fee-value {
  color: $color-text-main;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.fee-row--total .fee-label,
.fee-row--total .fee-value {
  font-size: 14px;
  font-weight: 700;
  color: $color-text-main;
}

.fee-row--total .fee-value {
  color: $color-price;
}

.order-info-title {
  font-size: 15px;
  font-weight: 650;
}

.info-val {
  min-width: 0;
  color: $color-text-main;
  font-size: 12px;
  text-align: right;
  overflow-wrap: anywhere;
}

.copy-box {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  min-width: 0;
}

.order-number {
  font-variant-numeric: tabular-nums;
}

.copy-btn,
.order-refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: 44px;
  min-height: 44px;
  margin: 0;
  padding: 0 4px;
  color: $color-primary;
  background: transparent;
  border: 0;
  font-size: 12px;
  line-height: 1.55;
}

.order-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.order-actions button,
.order-recovery button,
.order-simulator button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  min-height: 44px;
  max-width: 100%;
  margin: 0;
  padding: 8px 14px;
  border: 1px solid $color-border;
  border-radius: 8px;
  background: $color-bg-card;
  color: $color-text-main;
  font-size: 14px;
  font-weight: 400;
  line-height: 1.55;
  white-space: normal;
  box-shadow: none;
}

.order-actions .order-action--soft {
  background: $color-primary-light;
  color: $color-primary;
  border-color: transparent;
}

.order-actions .ui-btn--primary {
  background: $color-primary;
  color: $color-text-white;
}

.copy-btn::after,
.order-refresh::after,
.order-actions button::after,
.order-recovery button::after,
.order-simulator button::after {
  border: 0;
}

.order-recovery,
.order-simulator {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
</style>
