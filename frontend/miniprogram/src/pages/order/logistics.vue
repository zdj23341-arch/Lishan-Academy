<script setup lang="ts">
import { customerMessage, carrierLabel, shipmentStatusLabel } from '../../utils/customer-text';
import { formatShanghaiDateTime } from '@jxsc/shared';
import UiIcon from '../../components/ui/UiIcon.vue';
import { ref, onUnmounted } from 'vue';
import { onHide, onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { shipmentLabel, type ShipmentView } from '../../../../shared/src/commerce/order';
import { orderApi } from '../../api/order';
import { session } from '../../api/session';
const STAY_REFRESH_MS = 60_000;
const shipment = ref<ShipmentView | null>(null),
  busy = ref(false),
  error = ref(''),
  loaded = ref(false),
  updatedAt = ref('');
let orderId = '',
  epoch = 0,
  timer: ReturnType<typeof setInterval> | undefined;
function clear() {
  epoch++;
  stopTimer();
  shipment.value = null;
  busy.value = false;
  error.value = '';
  loaded.value = false;
  updatedAt.value = '';
}
const unsubscribe = session.subscribe(clear);
onUnmounted(() => {
  clear();
  unsubscribe();
});
async function load() {
  clear();
  if (!orderId) {
    error.value = '缺少订单编号';
    return;
  }
  if (!session.get()) {
    session.rememberReturnTo(`/pages/order/logistics?orderId=${encodeURIComponent(orderId)}`);
    uni.navigateTo({ url: '/pages/login/index' });
    return;
  }
  const ticket = epoch;
  busy.value = true;
  try {
    const result = await orderApi.shipment(orderId);
    if (ticket !== epoch) return;
    shipment.value = result;
    loaded.value = true;
    updatedAt.value = formatShanghaiDateTime(new Date().toISOString());
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '物流加载失败');
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
/** Silent re-read while the customer stays on the page; never blanks the visible timeline. */
async function refresh() {
  if (!orderId || !session.get() || busy.value) return;
  const ticket = epoch;
  try {
    const result = await orderApi.shipment(orderId);
    if (ticket !== epoch) return;
    shipment.value = result;
    loaded.value = true;
    updatedAt.value = formatShanghaiDateTime(new Date().toISOString());
  } catch {
    // Keep the last good timeline; the explicit retry button still handles real failures.
  }
}
function stopTimer() {
  if (timer !== undefined) clearInterval(timer);
  timer = undefined;
}
function schedule() {
  stopTimer();
  timer = setInterval(() => {
    void refresh();
  }, STAY_REFRESH_MS);
}
function copyTrackingNo() {
  if (!session.get() || !shipment.value?.trackingNo) return;
  uni.setClipboardData({
    data: shipment.value.trackingNo,
    success: () => uni.showToast({ title: '单号已复制', icon: 'success' }),
  });
}

onLoad((options) => {
  orderId = options?.orderId ?? '';
});
onShow(() => {
  void load();
  schedule();
});
onHide(stopTimer);
onPullDownRefresh(async () => {
  await refresh();
  uni.stopPullDownRefresh();
});
</script>

<template>
  <view class="ls-mini-page logistics-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert"
      >{{ error }}<button :disabled="busy" @click="load">重试</button></view
    >
    <view v-if="busy">正在加载…</view>
    <view v-if="loaded">{{ shipmentLabel(shipment) }}</view>
    <text v-if="loaded && updatedAt" class="refresh-hint"
      >更新于 {{ updatedAt.slice(-5) }} · 下拉刷新</text
    >
    <!-- 头部物流公司信息卡片 -->
    <view v-if="loaded && shipment" class="company-card ui-card">
      <UiIcon name="truck" tone="blue" :size="28" />
      <view class="company-info">
        <view class="name-row">
          <text v-if="shipment.carrierCode" class="company-name">{{
            carrierLabel(shipment.carrierCode)
          }}</text>
          <view v-if="shipment.status" class="status-tag ui-tag ui-tag--accent">{{
            shipmentStatusLabel(shipment.status)
          }}</view>
        </view>
        <view v-if="shipment.trackingNo" class="no-row">
          <text class="tracking-no">运单号：{{ shipment.trackingNo }}</text>
          <button class="copy-btn" @click="copyTrackingNo">复制</button>
        </view>
      </view>
    </view>

    <!-- 垂直时间线卡片 -->
    <view
      v-if="loaded && shipment?.trackingNo && shipment.events.length"
      class="timeline-card ui-card"
    >
      <view class="timeline-title">运输轨迹</view>

      <view class="timeline-list">
        <view
          v-for="(node, index) in shipment.events"
          :key="index"
          class="timeline-item"
          :class="{ 'timeline-item--active': index === 0 }"
        >
          <!-- 节点连线与小圆点 -->
          <view class="node-axis">
            <view class="node-dot" />
            <view v-if="index !== shipment.events.length - 1" class="node-line" />
          </view>

          <!-- 节点内容 -->
          <view class="node-body">
            <text v-if="node.description" class="node-context">{{ node.description }}</text>
            <text v-if="node.occurredAt" class="node-time">{{
              formatShanghaiDateTime(node.occurredAt)
            }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.logistics-page {
  padding: 32rpx;
}

.refresh-hint {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: $color-text-sub;
}

.company-card {
  padding: 28rpx 24rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;

  .company-icon {
    font-size: 52rpx;
  }

  .company-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8rpx;

    .name-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16rpx;

      .company-name {
        font-size: 32rpx;
        font-weight: 700;
        color: $color-text-main;
      }
    }

    .no-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16rpx;

      .tracking-no {
        font-size: 26rpx;
        color: $color-text-secondary;
      }

      .copy-btn {
        font-size: 24rpx;
        padding: 2rpx 14rpx;
        border-radius: $radius-full;
        background: #f0f2f5;
        color: $color-text-sub;
        border: none;
        &::after {
          display: none;
        }
      }
    }
  }
}

.timeline-card {
  padding: 28rpx 24rpx;

  .timeline-title {
    font-size: 30rpx;
    font-weight: 700;
    color: $color-text-main;
    margin-bottom: 32rpx;
  }
}

.timeline-list {
  display: flex;
  flex-direction: column;
}

.timeline-item {
  display: flex;
  gap: 20rpx;
  min-height: 120rpx;

  .node-axis {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 32rpx;

    .node-dot {
      width: 18rpx;
      height: 18rpx;
      border-radius: 50%;
      background: $color-text-muted;
      margin-top: 6rpx;
    }

    .node-line {
      flex: 1;
      width: 2rpx;
      background: #e5ebf3;
      margin: 6rpx 0;
    }
  }

  .node-body {
    flex: 1;
    padding-bottom: 36rpx;
    display: flex;
    flex-direction: column;
    gap: 8rpx;

    .node-context {
      font-size: 26rpx;
      color: $color-text-secondary;
      line-height: 1.45;
    }

    .node-time {
      font-size: 24rpx;
      color: $color-text-muted;
    }
  }

  &--active {
    .node-axis {
      .node-dot {
        width: 26rpx;
        height: 26rpx;
        background: $color-accent;
        box-shadow: 0 0 0 6rpx rgba(25, 116, 96, 0.2);
        margin-top: 2rpx;
      }
    }

    .node-body {
      .node-context {
        color: $color-text-main;
        font-weight: 600;
      }

      .node-time {
        color: $color-accent;
        font-weight: 500;
      }
    }
  }
}
</style>
