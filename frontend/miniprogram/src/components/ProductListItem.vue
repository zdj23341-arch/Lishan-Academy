<script setup lang="ts">
import BookCover from './ui/BookCover.vue';
import { computed } from 'vue';
import type { ProductCard } from '@jxsc/shared';
import { navigate } from '../composables/useCatalog';
import PriceText from './PriceText.vue';

const props = defineProps<{ product: ProductCard }>();

const isOffSale = computed(() => props.product.status === 'OFF_SALE');

const typeLabel = computed(() => {
  switch (props.product.type) {
    case 'COURSE':
      return '视频课程';
    case 'BOOK':
      return '实体图书';
    case 'MEMBERSHIP':
      return '会员专享';
    default:
      return '资料';
  }
});

const typeTagClass = computed(() => {
  switch (props.product.type) {
    case 'COURSE':
      return 'ui-tag--primary';
    case 'BOOK':
      return 'ui-tag--accent';
    case 'MEMBERSHIP':
      return 'ui-tag--vip';
    default:
      return 'ui-tag--muted';
  }
});

function onClickItem() {
  navigate({ type: 'PRODUCT', id: props.product.id, filters: {} });
}
</script>

<template>
  <view
    class="product-list-item"
    :class="{ 'product-list-item--offsale': isOffSale }"
    @click="onClickItem"
  >
    <!-- 左侧缩略图 -->
    <view class="product-list-item__thumb-box">
      <BookCover
        :title="product.title"
        :image-url="product.coverUrl"
        :theme="product.type === 'BOOK' ? 'jade' : 'blue'"
      />

      <view v-if="isOffSale" class="product-list-item__mask">
        <text class="mask-text">下架</text>
      </view>
    </view>

    <!-- 右侧内容 -->
    <view class="product-list-item__body">
      <view class="product-list-item__header">
        <view class="type-tag ui-tag" :class="typeTagClass">{{ typeLabel }}</view>
        <text class="product-list-item__title text-ellipsis-2">{{ product.title }}</text>
      </view>

      <text v-if="product.subtitle" class="product-list-item__desc text-ellipsis">
        {{ product.subtitle }}
      </text>

      <view class="product-list-item__footer">
        <PriceText :fen="product.priceFen" :original="product.originalPriceFen" size="md" />
        <button class="view-btn">查看详情</button>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.product-list-item {
  display: flex;
  background-color: $color-bg-card;
  border-radius: $radius-md;
  padding: 20rpx;
  margin-bottom: 16rpx;
  box-shadow: $shadow-card;
  transition: $transition-fast;
  cursor: pointer;

  &:active {
    transform: translateY(2rpx);
    box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
  }

  &--offsale {
    opacity: 0.7;
  }

  &__thumb-box {
    position: relative;
    width: 160rpx;
    height: 214rpx; // 3:4 竖版封面
    border-radius: $radius-sm;
    overflow: hidden;
    flex-shrink: 0;
    background-color: #f0f3f7;
  }

  &__thumb {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: contain;
  }

  &__thumb-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, #e6effd 0%, #d8e5f9 100%);
    color: $color-primary;

    .placeholder-text {
      font-size: 24rpx;
      font-weight: 600;
    }
  }

  &__mask {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;

    .mask-text {
      color: #fff;
      font-size: 24rpx;
      font-weight: 600;
      background: rgba(0, 0, 0, 0.6);
      padding: 4rpx 12rpx;
      border-radius: 999rpx;
    }
  }

  &__body {
    flex: 1;
    margin-left: 20rpx;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    min-width: 0;
  }

  &__header {
    display: flex;
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8rpx;

    .type-tag {
      flex-shrink: 0;
      margin-top: 4rpx;
    }
  }

  &__title {
    font-size: 28rpx;
    font-weight: 600;
    color: $color-text-main;
    line-height: 1.35;
    flex: 1;
  }

  &__desc {
    font-size: 24rpx;
    color: $color-text-secondary;
    margin-top: 6rpx;
  }

  &__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 12rpx;
  }
}

.view-btn {
  height: 52rpx;
  line-height: 52rpx;
  padding: 0 24rpx;
  border-radius: $radius-full;
  background-color: $color-primary-light;
  color: $color-primary;
  font-size: 24rpx;
  font-weight: 600;
  border: none;
  outline: none;

  &::after {
    display: none;
  }

  &:active {
    opacity: 0.85;
  }
}
.product-list-item {
  border: 1rpx solid $color-border;
  box-shadow: none;
}
.product-list-item__thumb-box :deep(.ls-cover) {
  height: 100%;
}
.product-list-item__thumb-box :deep(.ls-cover-title) {
  font-size: 30rpx;
}
.product-list-item__thumb-box :deep(.ls-cover-caption) {
  display: none;
}
</style>
