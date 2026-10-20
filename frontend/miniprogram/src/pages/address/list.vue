<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import UiIcon from '../../components/ui/UiIcon.vue';
import { ref, onUnmounted } from 'vue';
import { onLoad, onShow, onReachBottom } from '@dcloudio/uni-app';
import { createAddressController } from '../../../../shared/src/commerce/address';
import type { AddressView } from '../../../../shared/src/commerce/types';
import { commerceApi } from '../../api/commerce';
import { session } from '../../api/session';
import { selectedAddressId } from '../../stores/orderDraft';
import { addressLabel } from '../../utils/address';

let controller = createAddressController(commerceApi);
const addresses = ref<AddressView[]>([]),
  error = ref(''),
  busy = ref(false),
  hasMore = ref(false);
let selecting = false,
  epoch = 0;
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(`/pages/address/list${selecting ? '?select=1' : ''}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
const unsubscribe = session.subscribe(() => {
  epoch++;
  controller = createAddressController(commerceApi);
  addresses.value = [];
  error.value = '';
  busy.value = false;
  hasMore.value = false;
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
});
function sync() {
  addresses.value = [...controller.pager.state.items];
  hasMore.value = controller.pager.state.hasMore;
  if (controller.pager.state.error)
    error.value = customerMessage(controller.pager.state.error, '地址加载失败，请重试');
}
async function load(more = false) {
  if (busy.value || !authorized()) return;
  const ticket = ++epoch;
  busy.value = true;
  error.value = '';
  try {
    await (more ? controller.pager.loadMore() : controller.pager.reset(null));
    if (ticket === epoch) sync();
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '地址加载失败');
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
async function mutate(action: () => Promise<unknown>) {
  if (busy.value || !authorized()) return;
  const ticket = ++epoch;
  busy.value = true;
  error.value = '';
  try {
    await action();
    if (ticket === epoch) sync();
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '地址操作失败');
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
function setDefault(id: string) {
  const address = addresses.value.find((item) => item.id === id);
  if (address && !address.isDefault)
    void mutate(() => controller.save({ ...address, isDefault: true }, id));
}
function removeAddress(item: AddressView) {
  if (busy.value || !authorized()) return;
  const generation = session.getGeneration();
  uni.showModal({
    title: '删除收货地址',
    content: `确认删除${item.name}的收货地址？`,
    success: (result) => {
      if (result.confirm && generation === session.getGeneration())
        void mutate(async () => {
          await controller.remove(item.id);
          if (selectedAddressId.value === item.id) selectedAddressId.value = null;
        });
    },
  });
}
function editAddress(id?: string) {
  if (busy.value) return;
  uni.navigateTo({
    url: id ? `/pages/address/edit?id=${encodeURIComponent(id)}` : '/pages/address/edit',
  });
}
function selectAddress(item: AddressView) {
  if (!selecting || busy.value || !authorized()) return;
  selectedAddressId.value = item.id;
  uni.navigateBack();
}
onLoad((options) => {
  selecting = options?.select === '1';
  uni.removeStorageSync('jxsc.selectedAddress');
});
onShow(() => {
  void load();
});
onReachBottom(() => {
  if (hasMore.value) void load(true);
});
</script>

<template>
  <view class="ls-mini-page address-list-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert"
      >{{ error }}<button :disabled="busy" @click="load()">重试</button></view
    >
    <view v-if="busy">正在加载…</view>
    <view v-if="!busy && !error && !addresses.length" class="address-empty">
      <view class="address-empty__icon"><UiIcon name="map-pin" :size="32" /></view>
      <text class="address-empty__title">暂无收货地址</text>
      <text class="address-empty__hint">添加地址，方便购买图书时接收快递</text>
    </view>
    <view v-if="addresses.length" class="address-cards-list">
      <view
        v-for="item in addresses"
        :key="item.id"
        class="address-card ui-card"
        @click="selectAddress(item)"
      >
        <view class="address-card__main">
          <view class="user-row">
            <text class="user-name">{{ item.name }}</text>
            <text class="user-phone">{{ item.phone }}</text>
            <view v-if="item.isDefault" class="default-badge ui-tag ui-tag--primary"> 默认 </view>
          </view>
          <text class="address-text">
            {{ addressLabel(item) }}
          </text>
        </view>

        <view class="address-card__footer" @click.stop>
          <view class="default-check" @click="setDefault(item.id)">
            <view class="radio-circle" :class="{ 'radio-circle--active': item.isDefault }">
              <view v-if="item.isDefault" class="inner-dot" />
            </view>
            <text class="check-label">{{ item.isDefault ? '默认地址' : '设为默认' }}</text>
          </view>

          <view class="action-btns">
            <button :disabled="busy" @click.stop="removeAddress(item)">删除</button>
            <button :disabled="busy" @click.stop="editAddress(item.id)">编辑</button>
          </view>
        </view>
      </view>
    </view>

    <button v-if="hasMore" :disabled="busy" @click="load(true)">加载更多</button>
    <!-- 底部常驻新增按钮 -->
    <view class="bottom-bar">
      <button class="ui-btn ui-btn--primary add-btn" :disabled="busy" @click="editAddress()">
        + 添加收货地址
      </button>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.address-list-page {
  padding: 32rpx 32rpx calc(160rpx + env(safe-area-inset-bottom));
}

.address-cards-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.address-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  padding: 96rpx 24rpx;
  text-align: center;
}
.address-empty__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 128rpx;
  height: 128rpx;
  border-radius: 50%;
  background: #eaf0fa;
}
.address-empty__title {
  font-size: 30rpx;
  font-weight: 600;
  color: $color-text-main;
}
.address-empty__hint {
  font-size: 26rpx;
  line-height: 1.6;
  color: $color-text-sub;
}

.address-card {
  padding: 28rpx 24rpx 20rpx;
  cursor: pointer;

  &:active {
    opacity: 0.95;
  }

  &__main {
    padding-bottom: 20rpx;
    border-bottom: 1rpx solid $color-border-light;
  }

  .user-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 16rpx;
    margin-bottom: 12rpx;

    .user-name {
      font-size: 32rpx;
      font-weight: 700;
      color: $color-text-main;
    }

    .user-phone {
      font-size: 28rpx;
      color: $color-text-secondary;
    }

    .default-badge {
      font-size: 24rpx;
      padding: 2rpx 10rpx;
    }
  }

  .address-text {
    font-size: 26rpx;
    color: $color-text-sub;
    line-height: 1.45;
  }

  &__footer {
    display: flex;
    flex-wrap: wrap;
    gap: 16rpx;
    align-items: center;
    justify-content: space-between;
    padding-top: 16rpx;
  }
}

.default-check {
  display: flex;
  align-items: center;
  gap: 12rpx;
  cursor: pointer;
  min-height: 88rpx;

  .radio-circle {
    width: 32rpx;
    height: 32rpx;
    border-radius: 50%;
    border: 2rpx solid $color-text-muted;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: $transition-fast;

    &--active {
      border-color: $color-primary;
      background-color: $color-primary;
    }

    .inner-dot {
      width: 12rpx;
      height: 12rpx;
      border-radius: 50%;
      background: #ffffff;
    }
  }

  .check-label {
    font-size: 24rpx;
    color: $color-text-secondary;
  }
}

.action-btns {
  display: flex;
  align-items: center;
  gap: 24rpx;
  button {
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0;
    line-height: 1.5;
    min-height: 88rpx;
    padding: 12rpx 20rpx;
    font-size: 24rpx;
    color: $color-text-sub;
    background: $color-bg-page;
    border-radius: $radius-sm;
  }
  button:after {
    border: 0;
  }

  .act-btn {
    display: flex;
    align-items: center;
    gap: 6rpx;
    color: $color-text-secondary;
    min-height: 88rpx;

    .svg-act {
      width: 28rpx;
      height: 28rpx;
    }

    .act-text {
      font-size: 24rpx;
    }
  }
}

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.95);
  box-shadow: $shadow-nav;

  .add-btn {
    width: 100%;
    min-height: 88rpx;
    margin: 0;
    font-size: 30rpx;
  }
}
</style>
