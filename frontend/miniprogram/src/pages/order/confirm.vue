<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import UiIcon from '../../components/ui/UiIcon.vue';
import BookCover from '../../components/ui/BookCover.vue';
import { ref, computed, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { createCatalogApi, formatFen, referralManager, type ProductDetail } from '@jxsc/shared';
import { createCheckoutController } from '../../../../shared/src/commerce/checkout';
import type { AddressView } from '../../../../shared/src/commerce/types';
import PriceText from '../../components/PriceText.vue';
import { client } from '../../api/client';
import { commerceApi, operationKey } from '../../api/commerce';
import { session, sessionStorage, operationNamespace } from '../../api/session';
import { selectedAddressId } from '../../stores/orderDraft';
import { distributionApi } from '../../api/distribution';
import { findAddress, addressLabel } from '../../utils/address';

const controller = createCheckoutController({
  api: commerceApi,
  session,
  storage: sessionStorage,
  namespace: operationNamespace,
  keyGenerator: operationKey,
});
const contentApi = createCatalogApi(client);
const state = ref({ ...controller.state }),
  operation = ref({ ...controller.operation.state });
const product = ref<ProductDetail | null>(null),
  address = ref<AddressView | null>(null);
const busy = ref(false),
  error = ref('');
let productId = '',
  quantity = 1,
  epoch = 0;
let reviewedAddress: string | null = null;
function addressRevision(value: AddressView | null) {
  return value
    ? JSON.stringify([
        value.id,
        value.version,
        value.name.trim(),
        value.phone.trim(),
        value.provinceCode,
        value.cityCode,
        value.districtCode,
        value.detail.trim(),
      ])
    : null;
}
const item = computed(
  () =>
    state.value.displayedQuote?.item ??
    (product.value
      ? {
          productId: product.value.id,
          type: product.value.type,
          title: product.value.title,
          coverUrl: product.value.coverUrl,
          unitPriceFen: product.value.priceFen,
          quantity,
        }
      : null),
);
const quote = computed(() => (state.value.error ? null : state.value.displayedQuote));
const freightFen = computed(() => quote.value?.freightAmountFen ?? 0);
const goodsAmountFen = computed(() => quote.value?.goodsAmountFen ?? 0);
const totalPayableFen = computed(() => quote.value?.payableAmountFen ?? 0);
const selectedAddress = computed(() =>
  address.value ? { ...address.value, address: addressLabel(address.value) } : null,
);
const unresolved = computed(() => ['UNKNOWN', 'SUBMITTING'].includes(operation.value.status));
function sync() {
  state.value = { ...controller.state };
  operation.value = { ...controller.operation.state };
}
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(
    `/pages/order/confirm?productId=${encodeURIComponent(productId)}&quantity=${quantity}`,
  );
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
const unsubscribe = session.subscribe(() => {
  epoch++;
  reviewedAddress = null;
  product.value = null;
  address.value = null;
  busy.value = false;
  error.value = '';
  sync();
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
  try {
    if (controller.operation.state.status === 'SUCCEEDED') controller.operation.reset();
  } finally {
    controller.dispose();
  }
});
async function refresh() {
  if (busy.value || !authorized()) return;
  const ticket = ++epoch;
  busy.value = true;
  error.value = '';
  try {
    if (controller.operation.state.key && controller.operation.state.status !== 'FAILED') {
      await controller.operation.recover();
      if (ticket === epoch) openCreatedOrder();
      return;
    }
    if (!productId) throw new Error('缺少商品，请返回商品页重新选择');
    const result = await contentApi.product(productId);
    if (ticket !== epoch) return;
    if (!result.allowedActions.includes('BUY')) throw new Error('此商品暂不可购买');
    const refreshedAddress =
      result.type === 'BOOK' ? await findAddress(selectedAddressId.value) : null;
    if (ticket !== epoch) return;
    const revision = addressRevision(refreshedAddress);
    if (reviewedAddress !== null && revision !== reviewedAddress)
      controller.state.needsConfirmation = true;
    product.value = result;
    address.value = refreshedAddress;
    selectedAddressId.value = address.value?.id ?? null;
    controller.setDraft(
      { productId, quantity, addressId: address.value?.id },
      {
        id: result.id,
        type: result.type,
        ...(result.type === 'BOOK'
          ? { availableStock: result.book.availableStock, quantityLimit: result.book.quantityLimit }
          : {}),
      },
    );
    await controller.refresh();
    if (ticket === epoch) reviewedAddress = revision;
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '订单预览失败');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      sync();
    }
  }
}
function chooseAddress() {
  if (busy.value || unresolved.value || operation.value.status === 'SUCCEEDED') return;
  uni.navigateTo({ url: '/pages/address/list?select=1' });
}
function confirmQuote() {
  try {
    controller.confirmQuote();
    error.value = '';
    sync();
  } catch (reason) {
    error.value = customerMessage(reason, '请刷新报价');
  }
}
async function submitOrder() {
  if (busy.value || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  try {
    await referralManager.claimPending((token) => distributionApi.claimReferral(token));
    if (ticket !== epoch) return;
    const attributionToken = productId ? referralManager.getValidTokenForProduct(productId) : null;
    await controller.submit(attributionToken);
    if (ticket !== epoch) return;
    openCreatedOrder(true);
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '下单结果未知，请查询原操作');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      sync();
    }
  }
}
function openCreatedOrder(pay = false) {
  const id = controller.operation.state.resourceId;
  if (controller.operation.state.status !== 'SUCCEEDED' || !id) return;
  const ticket = epoch;
  uni.redirectTo({
    url: `/pages/order/detail?id=${encodeURIComponent(id)}${pay ? '&pay=1' : ''}`,
    success: () => {
      if (ticket === epoch) controller.operation.reset();
    },
    fail: () => {
      if (ticket === epoch) error.value = '订单已创建，请点击查看订单继续付款';
    },
  });
}
function newOrder() {
  controller.operation.reset();
  sync();
  void refresh();
}
function discardReferral() {
  referralManager.clear();
  error.value = '';
}
onLoad((options) => {
  // A confirmed old order is available in the order list. A new checkout must not
  // inherit that completed global slot; unresolved operations still require recovery.
  if (controller.operation.state.status === 'SUCCEEDED' && controller.operation.state.resourceId)
    controller.operation.reset();
  productId = options?.productId || '';
  quantity = Number(options?.quantity ?? '1');
});
onShow(() => {
  void refresh();
});
</script>

<template>
  <view class="ls-mini-page order-confirm-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert">{{ error }}</view>
    <button
      v-if="error && referralManager.getPendingAttribution()"
      :disabled="busy"
      @click="discardReferral"
    >
      不使用推荐继续
    </button>
    <view v-if="busy">正在处理…</view>
    <view v-if="unresolved">原下单结果尚未确认，请查询原操作后再继续。</view>
    <view v-if="operation.status === 'SUCCEEDED'">订单已创建，可继续查看订单与付款。</view>
    <button v-if="operation.status !== 'SUCCEEDED'" :disabled="busy" @click="refresh">
      {{ unresolved ? '查询原下单结果' : '刷新订单预览' }}
    </button>
    <button v-if="operation.status === 'SUCCEEDED'" :disabled="busy" @click="newOrder">
      开始新的订单
    </button>
    <view v-if="state.needsConfirmation"
      >报价或收货信息已变化，请核对后确认。<button :disabled="busy" @click="confirmQuote">
        确认当前报价
      </button></view
    >
    <view v-for="warning in quote?.warnings || []" :key="warning">{{
      customerMessage(warning, '订单提示暂不可用，请刷新并核对报价与收货信息')
    }}</view>
    <template v-if="item">
      <!-- 图书类商品：收货地址卡片 -->
      <view v-if="item.type === 'BOOK'" class="address-section ui-card" @click="chooseAddress">
        <view class="address-icon-box">
          <UiIcon name="map-pin" class="svg-loc" :size="18" />
        </view>

        <view v-if="selectedAddress" class="address-content">
          <view class="user-row">
            <text class="user-name">{{ selectedAddress.name }}</text>
            <text class="user-phone">{{ selectedAddress.phone }}</text>
          </view>
          <text class="address-text text-ellipsis-2">{{ selectedAddress.address }}</text>
        </view>
        <view v-else class="address-empty">
          <text class="empty-tip">点击添加收货地址</text>
        </view>

        <view class="arrow-box">
          <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
        </view>
      </view>

      <!-- 数字商品：免地址提示卡片 -->
      <view v-else class="digital-notice-card ui-card">
        <UiIcon name="shield-check" tone="blue" :size="24" />
        <view class="notice-body">
          <text class="notice-title">数字内容 · 无需实体收货地址</text>
          <text class="notice-desc">付款成功后可在当前账号查询相应权益</text>
        </view>
      </view>

      <!-- 商品清单卡片 -->
      <view class="goods-card ui-card">
        <view class="goods-header">
          <text class="shop-name">力山学堂</text>
        </view>

        <view class="goods-item">
          <view class="goods-cover-box">
            <BookCover
              :title="item.title"
              :image-url="item.coverUrl"
              :theme="item.type === 'BOOK' ? 'jade' : 'blue'"
            />
          </view>

          <view class="goods-info">
            <text class="goods-title text-ellipsis-2">{{ item.title }}</text>
            <view class="goods-spec">
              <view class="ui-tag ui-tag--primary">
                {{
                  item.type === 'BOOK'
                    ? '实体教材'
                    : item.type === 'COURSE'
                      ? '在线录播'
                      : '试卷会员'
                }}
              </view>
            </view>
            <view class="goods-bottom">
              <PriceText mode="amount" :fen="item.unitPriceFen" size="md" />
              <text class="goods-qty">× {{ item.quantity }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 费用明细卡片 -->
      <view v-if="quote" class="fee-card ui-card">
        <view class="fee-row">
          <text class="fee-label">商品总额</text>
          <PriceText mode="amount" :fen="goodsAmountFen" size="sm" />
        </view>
        <view v-if="item.type === 'BOOK'" class="fee-row">
          <text class="fee-label">配送费用</text>
          <text class="free-text">¥{{ formatFen(freightFen) }}</text>
        </view>
        <view class="fee-row fee-row--total">
          <text class="fee-label font-bold">应付总计</text>
          <PriceText mode="amount" :fen="totalPayableFen" size="lg" />
        </view>
      </view>

      <!-- 吸底支付确认栏 -->
      <view class="bottom-pay-bar">
        <view v-if="quote" class="pay-amount-box">
          <text class="pay-label">实付款：</text>
          <PriceText mode="amount" :fen="totalPayableFen" size="hero" />
        </view>

        <button
          class="ui-btn ui-btn--primary submit-order-btn"
          :disabled="busy || state.needsConfirmation || (!quote && !operation.key)"
          :loading="busy"
          @click="submitOrder"
        >
          {{
            unresolved
              ? '查询原下单结果'
              : operation.status === 'SUCCEEDED'
                ? '查看已创建订单'
                : '提交订单'
          }}
        </button>
      </view>
    </template>
    <button v-if="!item && operation.key" :disabled="busy" @click="submitOrder">
      {{ unresolved ? '查询原下单结果' : '查看已创建订单' }}
    </button>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.order-confirm-page {
  padding: 32rpx 32rpx calc(180rpx + env(safe-area-inset-bottom));
}

.address-section {
  display: flex;
  align-items: center;
  padding: 28rpx 24rpx;
  cursor: pointer;

  .address-icon-box {
    width: 56rpx;
    height: 56rpx;
    border-radius: 50%;
    background: $color-primary-light;
    color: $color-primary;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 20rpx;
    flex-shrink: 0;

    .svg-loc {
      width: 32rpx;
      height: 32rpx;
    }
  }

  .address-content {
    flex: 1;
    min-width: 0;

    .user-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16rpx;
      margin-bottom: 8rpx;

      .user-name {
        font-size: 30rpx;
        font-weight: 700;
        color: $color-text-main;
      }

      .user-phone {
        font-size: 26rpx;
        color: $color-text-secondary;
      }
    }

    .address-text {
      font-size: 26rpx;
      color: $color-text-sub;
      line-height: 1.4;
    }
  }

  .address-empty {
    flex: 1;
    .empty-tip {
      font-size: 28rpx;
      font-weight: 600;
      color: $color-primary;
    }
  }

  .arrow-box {
    color: $color-text-muted;
    .svg-arrow {
      width: 32rpx;
      height: 32rpx;
    }
  }
}

.digital-notice-card {
  padding: 24rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  background: #f4f8fe;
  border: 1rpx solid #d5e5fd;

  .notice-icon {
    font-size: 36rpx;
  }

  .notice-body {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  .notice-title {
    font-size: 26rpx;
    font-weight: 600;
    color: $color-primary;
  }

  .notice-desc {
    font-size: 24rpx;
    color: $color-text-secondary;
  }
}

.goods-card {
  padding: 24rpx;

  .goods-header {
    padding-bottom: 16rpx;
    margin-bottom: 16rpx;
    border-bottom: 1rpx solid $color-border-light;

    .shop-name {
      font-size: 26rpx;
      font-weight: 600;
      color: $color-text-main;
    }
  }

  .goods-item {
    display: flex;
    gap: 20rpx;
  }

  .goods-cover-box {
    width: 160rpx;
    height: 214rpx;
    border-radius: $radius-sm;
    overflow: hidden;
    background: #f0f3f7;
    flex-shrink: 0;

    .goods-cover {
      width: 100%;
      height: 100%;
    }

    .goods-cover-placeholder {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: $color-primary-light;
      color: $color-primary;
      font-size: 24rpx;
      font-weight: 600;
    }
  }

  .goods-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
  }

  .goods-title {
    font-size: 28rpx;
    font-weight: 600;
    color: $color-text-main;
    line-height: 1.35;
  }

  .goods-bottom {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-top: 10rpx;

    .goods-qty {
      font-size: 24rpx;
      color: $color-text-secondary;
    }
  }
}

.fee-card {
  padding: 24rpx;

  .fee-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12rpx 0;

    &--total {
      padding-top: 20rpx;
      margin-top: 10rpx;
      border-top: 1rpx solid $color-border-light;
    }

    .fee-label {
      font-size: 26rpx;
      color: $color-text-sub;

      &.font-bold {
        font-weight: 700;
        font-size: 28rpx;
        color: $color-text-main;
      }
    }

    .free-text {
      font-size: 26rpx;
      color: $color-accent;
      font-weight: 600;
    }
  }
}

.bottom-pay-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 990;
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(20px);
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: $shadow-nav;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  flex-wrap: wrap;

  .pay-amount-box {
    display: flex;
    align-items: baseline;

    .pay-label {
      font-size: 26rpx;
      color: $color-text-sub;
      font-weight: 500;
    }
  }

  .submit-order-btn {
    flex: 1;
    min-width: 240rpx;
    min-height: 88rpx;
    margin: 0;
    padding: 12rpx 32rpx;
    font-size: 30rpx;
  }
}
</style>
