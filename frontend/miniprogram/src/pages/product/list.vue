<script setup lang="ts">
import { routeKeyword } from '../../utils/route-keyword';
import { computed, ref } from 'vue';
import { onLoad, onReachBottom } from '@dcloudio/uni-app';
import type { ProductFilters, ProductSort, ProductType } from '@jxsc/shared';
import { catalog, useCatalogPager } from '../../composables/useCatalog';
import ProductCard from '../../components/ProductCard.vue';
import AsyncState from '../../components/AsyncState.vue';
import AppSearchBar from '../../components/AppSearchBar.vue';

const keyword = ref('');
const type = ref<ProductType | ''>('');
const sort = ref<ProductSort>('DEFAULT');
const sortSummaryLabel = computed(() =>
  sort.value === 'DEFAULT' ? '综合排序' : sort.value === 'NEWEST' ? '最新排序' : '价格排序',
);
const priceSortLabel = computed(() =>
  sort.value === 'PRICE_ASC' ? '价格↑' : sort.value === 'NEWEST' ? '最新' : '价格↓',
);

const typeOptions: { label: string; value: ProductType | '' }[] = [
  { label: '全部', value: '' },
  { label: '录播课程', value: 'COURSE' },
  { label: '语文图书', value: 'BOOK' },
  { label: '试卷会员', value: 'MEMBERSHIP' },
];

const { state, reset, more } = useCatalogPager((filters: ProductFilters, page) =>
  catalog.products({ ...filters, page, pageSize: 6 }),
);

function apply() {
  void reset({
    keyword: keyword.value,
    type: type.value || undefined,
    sort: sort.value,
  });
}

function selectType(val: ProductType | '') {
  if (type.value === val) return;
  type.value = val;
  apply();
}

function togglePriceSort() {
  sort.value = sort.value === 'PRICE_DESC' ? 'PRICE_ASC' : 'PRICE_DESC';
  apply();
}

onLoad((options) => {
  keyword.value = routeKeyword(options?.keyword);
  type.value = (options?.type as ProductType) || '';
  sort.value = (options?.sort as ProductSort) || 'DEFAULT';
  apply();
});

onReachBottom(() => {
  if (state.value.hasMore && !state.value.loading) {
    void more();
  }
});
</script>

<template>
  <view class="ls-mini-page product-list-page ui-page-container">
    <view class="sticky-filter-header">
      <AppSearchBar
        v-model="keyword"
        placeholder="搜索课程、图书、试卷"
        :show-submit="false"
        @submit="apply"
        @clear="apply"
      />
      <view class="product-type-tabs" role="tablist" aria-label="商品类型">
        <button
          v-for="t in typeOptions"
          :key="t.value"
          class="product-type-tab"
          :class="{ 'product-type-tab--active': type === t.value }"
          :aria-selected="type === t.value"
          role="tab"
          @click="selectType(t.value)"
        >
          {{ t.label }}
        </button>
      </view>
      <view class="list-summary-row">
        <text class="list-summary-text">{{ sortSummaryLabel }} · {{ state.total }} 个内容</text>
        <button class="price-sort-toggle" aria-label="切换价格排序" @click="togglePriceSort">
          {{ priceSortLabel }}
        </button>
      </view>
    </view>

    <view class="list-content">
      <AsyncState
        :loading="state.loading && !state.items.length"
        :error="state.error?.message"
        :empty="!state.items.length && !state.loading"
        empty-text="该筛选条件下暂无商品，试试其它分类吧"
        @retry="apply"
      />

      <view class="items-wrap">
        <ProductCard v-for="product in state.items" :key="product.id" :product="product" />
      </view>

      <view v-if="state.items.length" class="load-more-status">
        <view v-if="state.loading" class="loading-more">
          <view class="mini-spinner" />
          <text class="status-tip">正在加载更多精选好物...</text>
        </view>
        <view v-else-if="state.hasMore" class="click-more" @click="more">
          <text class="status-tip status-tip--action">滑动触底或点击加载更多</text>
        </view>
        <text v-else class="status-tip">—— 已经到底啦，仅呈现全部在售精选 ——</text>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.product-list-page {
  background-color: $color-bg-page;
}

.sticky-filter-header {
  background-color: $color-bg-page;
}

.product-type-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18rpx;
  padding: 24rpx 32rpx;
}

.product-type-tab,
.price-sort-toggle {
  margin: 0;
  border: 0;
  line-height: 1.2;
  font-family: inherit;

  &::after {
    border: 0;
  }
}

.product-type-tab {
  min-width: 0;
  min-height: 88rpx;
  padding: 10rpx 4rpx;
  border-radius: 14rpx;
  background: #ffffff;
  color: $color-text-secondary;
  font-size: 25rpx;
  white-space: nowrap;

  &--active {
    background: $color-primary;
    color: #ffffff;
    font-weight: 600;
  }
}

.list-summary-row {
  min-height: 76rpx;
  padding: 0 32rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: $color-bg-page;
}

.list-summary-text {
  font-size: 24rpx;
  color: $color-text-secondary;
}

.price-sort-toggle {
  width: 88rpx;
  min-width: 44px;
  min-height: 44px;
  padding: 0 8rpx;
  background: transparent;
  color: $color-primary;
  font-size: 26rpx;
  text-align: right;
}

.list-content {
  padding: 16rpx 32rpx 40rpx;
}
.items-wrap {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
}

.load-more-status {
  padding: 36rpx 0;
  text-align: center;
}

.loading-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
}

.mini-spinner {
  width: 28rpx;
  height: 28rpx;
  border: 3rpx solid rgba(40, 85, 183, 0.2);
  border-top-color: $color-primary;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.status-tip {
  font-size: 24rpx;
  color: $color-text-muted;

  &--action {
    color: $color-primary;
    cursor: pointer;
  }
}
</style>
