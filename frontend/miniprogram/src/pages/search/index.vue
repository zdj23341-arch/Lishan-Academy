<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import { routeKeyword } from '../../utils/route-keyword';
import UiIcon from '../../components/ui/UiIcon.vue';
import { ref, shallowRef } from 'vue';
import { onLoad, onReachBottom } from '@dcloudio/uni-app';
import { createSearch, type ProductCard, type SearchFilters, type ProductSort } from '@jxsc/shared';
import { catalog } from '../../composables/useCatalog';
import AppSearchBar from '../../components/AppSearchBar.vue';
import AsyncState from '../../components/AsyncState.vue';
import ProductCardView from '../../components/ProductCard.vue';

const model = createSearch(catalog);
const state = shallowRef({ ...model.state });

const keyword = ref('');
const type = ref<SearchFilters['type']>('COURSE');
const sort = ref<ProductSort>('DEFAULT');

const typeTabs: { label: string; value: SearchFilters['type'] }[] = [
  { label: '视频课程', value: 'COURSE' },
  { label: '实体图书', value: 'BOOK' },
  { label: '会员专区', value: 'MEMBERSHIP' },
  { label: '试卷资料', value: 'PAPER' },
];

const sortTabs: { label: string; value: ProductSort }[] = [
  { label: '综合推荐', value: 'DEFAULT' },
  { label: '价格从低到高', value: 'PRICE_ASC' },
  { label: '价格从高到低', value: 'PRICE_DESC' },
  { label: '最新上架', value: 'NEWEST' },
];

const hotKeywords = ['现代文阅读', '作文训练', '高三语文'];
const recommendations = ref<ProductCard[]>([]);
const recommendationLoading = ref(false);
const recommendationError = ref('');

async function loadRecommendations() {
  if (recommendationLoading.value) return;
  recommendationLoading.value = true;
  recommendationError.value = '';
  try {
    const page = await catalog.products({ type: 'COURSE', page: 1, pageSize: 2, sort: 'DEFAULT' });
    recommendations.value = page.items;
  } catch (cause) {
    recommendationError.value = customerMessage(cause, '推荐课程加载失败');
  } finally {
    recommendationLoading.value = false;
  }
}

async function submit() {
  const task = model.submit({ keyword: keyword.value, type: type.value, sort: sort.value });
  state.value = { ...model.state };
  await task;
  state.value = { ...model.state };
}

async function clearSearch() {
  type.value = 'COURSE';
  sort.value = 'DEFAULT';
  const task = model.clear({ keyword: '', type: type.value, sort: sort.value });
  state.value = { ...model.state };
  await task;
  state.value = { ...model.state };
  await loadRecommendations();
}

async function more() {
  const task = model.loadMore();
  state.value = { ...model.state };
  await task;
  state.value = { ...model.state };
}

function selectType(value: SearchFilters['type']) {
  if (type.value === value) return;
  type.value = value;
  if (value === 'PAPER') sort.value = 'DEFAULT';
  if (state.value.submitted) void submit();
}

function selectSort(value: ProductSort) {
  if (sort.value === value) return;
  sort.value = value;
  if (state.value.submitted) void submit();
}

function onSelectHotKeyword(kw: string) {
  keyword.value = kw;
  void submit();
}

function openPaper(id: string) {
  uni.navigateTo({ url: `/pages/paper/detail?id=${encodeURIComponent(id)}` });
}

onLoad((options) => {
  keyword.value = routeKeyword(options?.keyword);
  if (keyword.value.trim()) void submit();
  else void loadRecommendations();
});

onReachBottom(() => {
  if (state.value.hasMore && !state.value.loading) {
    void more();
  }
});
</script>

<template>
  <view class="ls-mini-page search-page ui-page-container">
    <!-- 顶部常驻搜索栏 -->
    <view class="search-header">
      <text class="search-caption">搜索课程、图书、试卷</text>
      <AppSearchBar
        v-model="keyword"
        variant="stacked"
        placeholder="输入关键词，如：现代文阅读"
        :error="state.fieldError"
        @submit="submit"
        @clear="clearSearch"
      />

      <!-- 类型选项卡 -->
      <view v-if="state.submitted" class="type-tabs">
        <view
          v-for="tab in typeTabs"
          :key="tab.value"
          class="type-tab-item"
          :class="{ 'type-tab-item--active': type === tab.value }"
          @click="selectType(tab.value)"
        >
          <text class="type-tab-text">{{ tab.label }}</text>
          <view v-if="type === tab.value" class="tab-indicator" />
        </view>
      </view>

      <!-- 排序过滤条 (试卷不按价格排序) -->
      <view v-if="state.submitted && type !== 'PAPER'" class="sort-bar">
        <view
          v-for="s in sortTabs"
          :key="s.value"
          class="sort-bar__item"
          :class="{ 'sort-bar__item--active': sort === s.value }"
          @click="selectSort(s.value)"
        >
          <text class="sort-text">{{ s.label }}</text>
        </view>
      </view>
    </view>

    <!-- 初始未搜索态：展示推荐与热门关键词 -->
    <view v-if="!state.submitted" class="search-initial">
      <view class="tag-section">
        <view class="tag-section__header">
          <text class="section-title">推荐关键词</text>
        </view>
        <view class="tag-list">
          <view
            v-for="kw in hotKeywords"
            :key="kw"
            class="hot-tag"
            role="button"
            :aria-label="'搜索关键词' + kw"
            @click="onSelectHotKeyword(kw)"
          >
            <text class="hot-tag__text">{{ kw }}</text>
          </view>
        </view>
      </view>
      <view class="recommendation-section">
        <text class="section-title">推荐课程</text>
        <AsyncState
          :loading="recommendationLoading"
          :error="recommendationError"
          :empty="!recommendationLoading && !recommendationError && !recommendations.length"
          empty-text="暂无推荐课程"
          @retry="loadRecommendations"
        />
        <view class="product-grid">
          <ProductCardView
            v-for="product in recommendations"
            :key="product.id"
            :product="product"
          />
        </view>
      </view>
    </view>

    <!-- 搜索结果列表 -->
    <view v-else class="search-result">
      <!-- 统计信息栏 -->
      <view class="result-stats">
        <text class="stats-text">
          找到 <text class="highlight">{{ state.total }}</text> 条相关结果
        </text>
      </view>

      <!-- 状态提示 -->
      <AsyncState
        :loading="state.loading && !state.items.length"
        :error="state.error?.message"
        :empty="!state.items.length && !state.loading"
        empty-text="没有找到匹配的内容，换个关键词试试吧"
        @retry="submit"
      />

      <!-- 列表循环 -->
      <view class="result-list" :class="{ 'product-grid': type !== 'PAPER' }">
        <view v-for="item in state.items" :key="item.id" class="result-item-wrap">
          <!-- 商品类型（课程/图书/会员） -->
          <ProductCardView v-if="'priceFen' in item" :product="item as ProductCard" />

          <!-- 试卷资料类型卡片 -->
          <view v-else class="paper-card ui-card" @click="openPaper(item.id)">
            <view class="paper-card__icon-box">
              <UiIcon name="file-text" class="svg-doc" :size="18" />
            </view>
            <view class="paper-card__info">
              <view class="paper-card__title-row">
                <text class="paper-card__title text-ellipsis-2">{{ item.title }}</text>
              </view>
              <view class="paper-card__badges">
                <view class="ui-tag ui-tag--vip">会员免费专享</view>
              </view>
            </view>
            <view class="paper-card__action">
              <button class="preview-btn">查看试卷</button>
            </view>
          </view>
        </view>
      </view>

      <!-- 加载更多触底状态 -->
      <view v-if="state.submitted && state.items.length" class="load-more-status">
        <view v-if="state.loading" class="loading-more">
          <view class="mini-spinner" />
          <text class="status-tip">正在加载更多数据...</text>
        </view>
        <view v-else-if="state.hasMore" class="click-more" @click="more">
          <text class="status-tip status-tip--action">点击加载更多</text>
        </view>
        <text v-else class="status-tip">已显示全部 {{ state.total }} 条结果</text>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.search-page {
  background-color: $color-bg-page;
}

.search-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background-color: #ffffff;
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.04);
}

.search-caption {
  display: block;
  padding: 16rpx 32rpx 0;
  color: $color-text-secondary;
  font-size: 24rpx;
  line-height: 1.4;
}

.type-tabs {
  display: flex;
  align-items: center;
  border-bottom: 1rpx solid $color-border;
  padding: 0 12rpx;
}

.type-tab-item {
  flex: 1;
  text-align: center;
  padding: 20rpx 0 16rpx;
  position: relative;
  cursor: pointer;

  &--active {
    .type-tab-text {
      color: $color-primary;
      font-weight: 700;
    }
  }

  .type-tab-text {
    font-size: 28rpx;
    color: $color-text-sub;
    transition: $transition-fast;
  }

  .tab-indicator {
    position: absolute;
    bottom: 0;
    left: 50%;
    transform: translateX(-50%);
    width: 44rpx;
    height: 6rpx;
    border-radius: 3rpx;
    background-color: $color-primary;
  }
}

.sort-bar {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 16rpx 24rpx;
  background-color: #fafbfd;

  &__item {
    font-size: 24rpx;
    color: $color-text-secondary;
    padding: 6rpx 16rpx;
    border-radius: $radius-full;
    transition: $transition-fast;
    cursor: pointer;

    &--active {
      color: $color-primary;
      background-color: $color-primary-light;
      font-weight: 600;
    }
  }
}

.search-initial {
  padding: 24rpx 32rpx;
}

.tag-section {
  background-color: #ffffff;
  border: 1px solid $color-border;
  border-radius: 13px;
  padding: 16px;

  &__header {
    margin-bottom: 20rpx;
  }

  .section-title {
    font-size: 28rpx;
    font-weight: 600;
    color: $color-text-main;
  }
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
}

.hot-tag {
  display: flex;
  align-items: center;
  min-height: 44px;
  padding: 0 8px;
  cursor: pointer;

  &:active {
    opacity: 0.7;
  }

  &__text {
    font-size: 24rpx;
    color: $color-text-sub;
  }
}

.search-result {
  padding: 16rpx 32rpx 40rpx;
}

.result-stats {
  padding: 12rpx 4rpx 20rpx;

  .stats-text {
    font-size: 24rpx;
    color: $color-text-secondary;

    .highlight {
      color: $color-primary;
      font-weight: 700;
      margin: 0 4rpx;
    }
  }
}

.result-list {
  display: flex;
  flex-direction: column;
}
.product-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
}
.result-item-wrap {
  min-width: 0;
}
.recommendation-section {
  margin-top: 32rpx;
}
.recommendation-section > .section-title {
  display: block;
  margin-bottom: 24rpx;
  font-size: 32rpx;
  font-weight: 700;
}

.paper-card {
  display: flex;
  align-items: center;
  padding: 24rpx;
  margin-bottom: 16rpx;
  cursor: pointer;

  &:active {
    opacity: 0.92;
  }

  &__icon-box {
    width: 80rpx;
    height: 80rpx;
    border-radius: $radius-sm;
    background-color: #edf3ff;
    color: $color-primary;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;

    .svg-doc {
      width: 48rpx;
      height: 48rpx;
    }
  }

  &__info {
    flex: 1;
    margin-left: 20rpx;
  }

  &__title {
    font-size: 28rpx;
    font-weight: 600;
    color: $color-text-main;
    line-height: 1.35;
    margin-bottom: 10rpx;
  }

  .preview-btn {
    height: 56rpx;
    line-height: 56rpx;
    padding: 0 24rpx;
    border-radius: $radius-full;
    background: $color-primary-light;
    color: $color-primary;
    font-size: 24rpx;
    font-weight: 600;
    border: none;
    outline: none;

    &::after {
      display: none;
    }
  }
}

.load-more-status {
  padding: 40rpx 0;
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
    font-weight: 500;
    cursor: pointer;
  }
}
</style>
