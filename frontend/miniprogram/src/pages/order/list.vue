<script setup lang="ts">
import {
  customerMessage,
  orderStatusLabel,
  fulfillmentStatusLabel,
  aftersaleStatusLabel,
} from '../../utils/customer-text';
import BookCover from '../../components/ui/BookCover.vue';
import UiState from '../../components/ui/UiState.vue';
import { ref, onUnmounted } from 'vue';
import { onLoad, onShow, onReachBottom } from '@dcloudio/uni-app';
import { type CustomerOrder, type OrderTab } from '../../../../shared/src/commerce/order';
import { createCatalogApi, formatFen } from '@jxsc/shared';
import { client } from '../../api/client';
import { orderApi } from '../../api/order';
import { session } from '../../api/session';
import BaseNav from '../../components/BaseNav.vue';
import PriceText from '../../components/PriceText.vue';

const activeTab = ref<OrderTab>('ALL');

const tabs: { label: string; value: OrderTab }[] = [
  { label: '全部', value: 'ALL' },
  { label: '待付款', value: 'UNPAID' },
  { label: '待发货', value: 'UNSHIPPED' },
  { label: '已发货', value: 'SHIPPING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '售后', value: 'AFTERSALE' },
];

const orderList = ref<CustomerOrder[]>([]);
const busy = ref(false),
  error = ref(''),
  hasMore = ref(false);
const learningOrder = ref<CustomerOrder | null>(null),
  learningError = ref(''),
  learningBusy = ref(false);
let page = 0,
  epoch = 0,
  retryMore = false;
function clear() {
  epoch++;
  orderList.value = [];
  page = 0;
  hasMore.value = false;
  busy.value = false;
  error.value = '';
  retryMore = false;
  learningOrder.value = null;
  learningError.value = '';
  learningBusy.value = false;
}
const unsubscribe = session.subscribe(clear);
onUnmounted(() => {
  clear();
  unsubscribe();
});
function authorized() {
  if (session.get()) return true;
  clear();
  session.rememberReturnTo(`/pages/order/list?tab=${activeTab.value}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
async function load(more = false) {
  if (!authorized() || (more && (busy.value || !hasMore.value))) return;
  const ticket = ++epoch;
  learningOrder.value = null;
  learningError.value = '';
  learningBusy.value = false;
  if (!more) {
    orderList.value = [];
    page = 0;
    hasMore.value = false;
  }
  const nextPage = more ? page + 1 : 1;
  busy.value = true;
  error.value = '';
  retryMore = more;
  try {
    const result = await orderApi.orders(activeTab.value, nextPage, 20);
    if (ticket !== epoch) return;
    orderList.value = more ? [...orderList.value, ...result.items] : [...result.items];
    page = result.page;
    hasMore.value = result.page * result.pageSize < result.total;
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '订单加载失败');
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
function setTab(tab: OrderTab) {
  activeTab.value = tab;
  void load();
}
function openDetail(id: string) {
  if (authorized() && orderList.value.some((order) => order.id === id))
    uni.navigateTo({ url: `/pages/order/detail?id=${encodeURIComponent(id)}` });
}
function can(order: CustomerOrder, action: string) {
  return (
    !!session.get() && orderList.value.includes(order) && order.allowedActions.includes(action)
  );
}
function openLogistics(order: CustomerOrder) {
  if (can(order, 'VIEW_LOGISTICS'))
    uni.navigateTo({ url: `/pages/order/logistics?orderId=${encodeURIComponent(order.id)}` });
}
const catalog = createCatalogApi(client);
function payNow(order: CustomerOrder) {
  if (can(order, 'PAY')) openDetail(order.id);
}
async function learnNow(order: CustomerOrder) {
  if (learningBusy.value || !can(order, 'VIEW_CONTENT') || order.item.type !== 'COURSE') return;
  const ticket = epoch;
  learningOrder.value = order;
  learningError.value = '';
  learningBusy.value = true;
  try {
    let courseId = order.item.courseId;
    if (!courseId) {
      const product = await catalog.product(order.item.productId);
      if (product.type === 'COURSE') courseId = product.course?.courseId;
    }
    if (ticket !== epoch || !can(order, 'VIEW_CONTENT')) return;
    if (typeof courseId !== 'string' || !courseId.trim())
      throw new Error('课程关联暂不可用，请重试');
    uni.navigateTo({
      url: `/pages/course/learn?id=${encodeURIComponent(courseId)}&productId=${encodeURIComponent(order.item.productId)}`,
    });
  } catch (cause) {
    if (ticket === epoch) learningError.value = customerMessage(cause, '课程查询失败，请重试');
  } finally {
    if (ticket === epoch) learningBusy.value = false;
  }
}
onLoad((options) => {
  const legacy: Record<string, OrderTab> = {
    PENDING_PAYMENT: 'UNPAID',
    PAID: 'UNSHIPPED',
    SHIPPED: 'SHIPPING',
  };
  const requested = options?.tab ?? options?.status ?? 'ALL';
  activeTab.value =
    tabs.find((tab) => tab.value === requested)?.value ??
    (Object.hasOwn(legacy, requested) ? legacy[requested] : undefined) ??
    'ALL';
});
onShow(() => {
  void load();
});
onReachBottom(() => {
  if (hasMore.value && !error.value) void load(true);
});
</script>

<template>
  <view class="ls-mini-page order-list-page ui-page-container">
    <!-- 顶部选项卡 -->
    <view class="order-tabs-bar">
      <view
        v-for="t in tabs"
        :key="t.value"
        class="tab-item"
        :class="{ 'tab-item--active': activeTab === t.value }"
        @click="setTab(t.value)"
      >
        <text class="tab-label">{{ t.label }}</text>
        <view v-if="activeTab === t.value" class="active-indicator" />
      </view>
    </view>

    <!-- 订单列表 -->
    <view class="orders-wrap">
      <UiState v-if="busy && !orderList.length" kind="loading" title="订单加载中…" />
      <UiState
        v-else-if="error && !orderList.length"
        kind="error"
        title="订单加载遇到问题"
        :description="error"
        action-label="重试"
        @action="load(retryMore)"
      />
      <UiState
        v-else-if="!busy && !error && !orderList.length"
        kind="empty"
        title="暂无订单"
        description="可以切换其他订单状态，或前往首页挑选课程与图书"
      />
      <view v-if="error && orderList.length" role="alert" class="ls-alert"
        >{{ error }}<button :disabled="busy" @click="load(retryMore)">重试</button></view
      >
      <view v-if="busy && orderList.length">正在加载…</view>
      <view v-if="learningError" role="alert" class="ls-alert"
        >{{ learningError
        }}<button :disabled="learningBusy" @click="learningOrder && learnNow(learningOrder)">
          重试进入学习
        </button></view
      >
      <view
        v-for="order in orderList"
        :key="order.id"
        class="order-card ui-card"
        @click="openDetail(order.id)"
      >
        <!-- 卡片头部：订单号与状态 -->
        <view class="card-header">
          <text class="order-no">订单号：{{ order.orderNo }}</text>
          <text
            class="status-text"
            :class="{
              'status-text--warn': order.orderStatus === 'PENDING_PAYMENT',
              'status-text--primary': order.fulfillmentStatus === 'SHIPPED',
              'status-text--success': order.orderStatus === 'COMPLETED',
            }"
          >
            {{ orderStatusLabel(order.orderStatus) }}
          </text>
        </view>
        <text class="order-substatus">
          {{ fulfillmentStatusLabel(order.fulfillmentStatus) }} ·
          {{ aftersaleStatusLabel(order.refundStatus) }}
        </text>

        <!-- 商品条目 -->
        <view class="goods-row">
          <view class="goods-thumb">
            <BookCover
              :title="order.item.title"
              :image-url="order.item.coverUrl"
              :theme="order.item.type === 'BOOK' ? 'jade' : 'blue'"
            />
          </view>
          <view class="goods-detail">
            <text class="goods-title text-ellipsis-2">{{ order.item.title }}</text>
            <view class="goods-meta">
              <text class="goods-qty">×{{ order.item.quantity }}</text>
            </view>
          </view>
          <text class="goods-unit-price">¥{{ formatFen(order.item.unitPriceFen) }}</text>
        </view>

        <!-- 总结与实付金额 -->
        <view class="total-row">
          <text class="goods-summary">共 {{ order.item.quantity }} 件商品，应付金额：</text>
          <PriceText mode="amount" :fen="order.payableAmountFen" size="sm" />
          <text v-if="order.paidAmountFen !== undefined" class="goods-summary">已付金额：</text>
          <PriceText
            mode="amount"
            v-if="order.paidAmountFen !== undefined"
            :fen="order.paidAmountFen"
            size="sm"
          />
        </view>

        <!-- 底部操作按钮组 -->
        <view class="action-row" @click.stop>
          <button
            v-if="can(order, 'PAY')"
            class="ui-btn ui-btn--price action-btn"
            @click="payNow(order)"
          >
            立即付款
          </button>

          <button
            v-if="can(order, 'VIEW_LOGISTICS')"
            class="ui-btn ui-btn--primary action-btn"
            @click="openLogistics(order)"
          >
            查看物流
          </button>

          <button
            v-if="
              order.item.type === 'COURSE' && order.item.productId && can(order, 'VIEW_CONTENT')
            "
            class="ui-btn ui-btn--accent action-btn"
            @click="learnNow(order)"
          >
            进入学习
          </button>

          <button class="ui-btn ui-btn--outline action-btn" @click="openDetail(order.id)">
            订单详情
          </button>
        </view>
      </view>
      <button v-if="hasMore && !error" :disabled="busy" @click="load(true)">加载更多</button>
    </view>

    <!-- 底部导航栏 -->
    <BaseNav active-tab="/pages/order/list" />
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.order-tabs-bar {
  display: flex;
  flex-wrap: nowrap;
  gap: 4px;
  padding: 16px 16px 0;
}

.tab-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 44px;
  padding: 8px 4px;
  border-radius: 7px;
  background: $color-bg-card;
}

.tab-label {
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.55;
  color: $color-text-sub;
}

.tab-item--active {
  background: $color-primary;
  .tab-label {
    color: $color-text-white;
    font-weight: 650;
  }
}

.active-indicator {
  display: none;
}

.orders-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 16px 20px;
}

.order-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 0;
  padding: 16px;
  border: 1px solid $color-border;
  border-radius: 13px;
  &:active {
    opacity: 0.96;
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.order-no {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.55;
  color: $color-text-sub;
}

.status-text {
  flex-shrink: 0;
  padding: 3px 9px;
  border-radius: 5px;
  font-size: 12px;
  line-height: 1.55;
  font-weight: 400;
  color: $color-text-sub;
  background: $color-bg-page;
  &--warn {
    color: $color-warning;
    background: $color-warning-light;
  }
  &--primary {
    color: $color-primary;
    background: $color-primary-light;
  }
  &--success {
    color: $color-accent;
    background: $color-accent-light;
  }
}

.order-substatus {
  margin-top: -8px;
  color: $color-text-sub;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.goods-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.goods-thumb {
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
  font-size: 13px;
  font-weight: 650;
  line-height: 1.55;
  color: $color-text-main;
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
  color: $color-text-main;
  font-size: 14px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.total-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: flex-end;
  gap: 4px;
}

.goods-summary {
  font-size: 12px;
  line-height: 1.55;
  color: $color-text-sub;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.action-row .action-btn {
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

.action-row .ui-btn--primary,
.action-row .ui-btn--accent {
  background: $color-primary-light;
  border-color: transparent;
  color: $color-primary;
}

.action-row .ui-btn--price {
  background: $color-primary;
  border-color: $color-primary;
  color: $color-text-white;
}
</style>
