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
      return '录播课程';
    case 'BOOK':
      return '自有图书';
    case 'MEMBERSHIP':
      return '试卷会员';
    default:
      return '商品';
  }
});

function onClickCard() {
  navigate({ type: 'PRODUCT', id: props.product.id, filters: {} });
}
</script>

<template>
  <view class="product-card" :class="{ 'product-card--offsale': isOffSale }" @click="onClickCard"
    ><view class="product-card__cover-box product-card__cover-box--portrait"
      ><BookCover
        :title="product.title"
        :image-url="product.coverUrl"
        :theme="product.type === 'BOOK' || product.type === 'MEMBERSHIP' ? 'sand' : 'blue'"
      /><view v-if="isOffSale" class="product-card__mask"><text>已下架</text></view></view
    ><view class="product-card__info"
      ><text class="product-card__type">{{ typeLabel }}</text
      ><text class="product-card__title text-ellipsis-2">{{ product.title }}</text
      ><PriceText :fen="product.priceFen" :original="product.originalPriceFen" size="card" /></view
  ></view>
</template>
<style lang="scss" scoped>
@import '../styles/tokens.scss';
.product-card {
  background: white;
  border: 1rpx solid $color-border;
  border-radius: 24rpx;
  overflow: hidden;
  min-width: 0;
}
.product-card__cover-box {
  position: relative;
}
.product-card__cover-box--portrait :deep(.ls-cover) {
  aspect-ratio: 3 / 4;
}
.product-card__cover-box :deep(.ls-cover) {
  border-radius: 0;
}
.product-card__info {
  padding: 22rpx;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
}
.product-card__type {
  max-width: 100%;
  padding: 6rpx 18rpx;
  border-radius: 10rpx;
  background: #edf3ff;
  color: $color-primary;
  font-size: 24rpx;
  line-height: 1.55;
}
.product-card__title {
  font-size: 24rpx;
  line-height: 1.55;
  min-height: 72rpx;
  font-weight: 700;
  color: $color-text-main;
}
.product-card__mask {
  position: absolute;
  inset: 0;
  background: #18283e80;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
}
.product-card--offsale {
  opacity: 0.75;
}
</style>
