<script setup lang="ts">
import { ref } from 'vue';
import { useRecentLearning } from '../../composables/useRecentLearning';
import RecentLearningCard from '../../components/RecentLearningCard.vue';
import BrandMark from '../../components/ui/BrandMark.vue';
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { catalog } from '../../composables/useCatalog';
import { useAccountCatalogDetail } from '../../composables/useAccountCatalogDetail';
import { environment } from '../../api/client';
import AppSearchBar from '../../components/AppSearchBar.vue';
import HomeCarousel from '../../components/HomeCarousel.vue';
import ShortcutGrid from '../../components/ShortcutGrid.vue';
import HomeSection from '../../components/HomeSection.vue';
import AsyncState from '../../components/AsyncState.vue';
import BaseNav from '../../components/BaseNav.vue';

const {
  data: home,
  loading,
  error,
  load,
} = useAccountCatalogDetail(() => catalog.home(), {
  id: 'home',
  retainOnRefresh: true,
});
const {
  recent,
  error: recentError,
  loading: recentLoading,
  loadRecent,
  continueLearning,
} = useRecentLearning();
const statusBarHeight = ref(0);

function search() {
  uni.navigateTo({ url: '/pages/search/index' });
}

onShow(() => {
  statusBarHeight.value = uni.getSystemInfoSync?.().statusBarHeight ?? 0;
  void loadRecent();
});

onPullDownRefresh(async () => {
  try {
    await Promise.all([load(), loadRecent()]);
  } finally {
    uni.stopPullDownRefresh();
  }
});
</script>

<template>
  <view
    class="ls-mini-page home-page ui-page-container"
    :style="{ '--status-bar-height': `${statusBarHeight}px` }"
  >
    <!-- 开发环境轻提示 -->
    <view v-if="environment.provider === 'mock'" class="env-banner">
      <text class="env-banner__text"> 演示环境 · 示例内容</text>
    </view>

    <view class="home-header">
      <view class="home-brand"><BrandMark subtitle="" /></view>
      <view class="sticky-search">
        <AppSearchBar
          model-value=""
          variant="entry"
          placeholder="搜索课程、图书、语文试卷"
          @submit="search"
        />
      </view>
    </view>

    <!-- 首屏加载只保留骨架屏，避免与全页状态重复叠加 -->
    <view v-if="loading && !home" class="home-skeleton">
      <view class="ui-skeleton skeleton-banner" />
      <view class="skeleton-grid">
        <view v-for="i in 6" :key="i" class="skeleton-grid__item">
          <view class="ui-skeleton skeleton-circle" />
          <view class="ui-skeleton skeleton-text" />
        </view>
      </view>
      <view class="skeleton-section">
        <view class="ui-skeleton skeleton-title" />
        <view class="skeleton-cards">
          <view v-for="i in 4" :key="i" class="ui-skeleton skeleton-card" />
        </view>
      </view>
    </view>

    <!-- 错误重试 -->
    <view v-else-if="error && !home" class="error-wrapper">
      <AsyncState :loading="false" :error="error" @retry="load()" />
    </view>

    <!-- 正常首页内容 -->
    <view v-else-if="home" class="home-content">
      <!-- 轮播横幅 -->
      <HomeCarousel :banners="home.banners" />

      <AsyncState v-if="error" :loading="false" :error="error" @retry="load()" />

      <!-- 仅展示后端已发布快捷入口 -->
      <ShortcutGrid :shortcuts="home.shortcuts" /><RecentLearningCard
        v-if="recent"
        :recent="recent"
        :loading="recentLoading"
        @continue="continueLearning"
      /><view v-else-if="recentError" class="recent-error"
        ><text>{{ recentError }}</text
        ><button class="ls-button ls-button--link" @click="loadRecent">重试</button></view
      >
      <view v-else-if="recentLoading" class="recent-loading" aria-live="polite">
        <view class="recent-loading__dot" />
        <text>正在加载最近学习</text>
      </view>
      <view v-else class="recent-empty" aria-live="polite">
        <text>暂无课程学习记录</text>
      </view>

      <!-- 动态楼层列表 -->
      <HomeSection v-for="section in home.sections" :key="section.id" :section="section" />

      <!-- 全空兜底 -->
      <view v-if="!home.sections || !home.sections.length" class="empty-floor">
        <text class="empty-floor__text">暂无更多模块推荐</text>
      </view>

      <!-- 页面底部品牌印记 -->
      <view class="home-footer-brand">
        <text class="brand-text">力山学堂 · 阅读与成长</text>
      </view>
    </view>

    <!-- 底部 TabBar 导航 -->
    <BaseNav active-tab="/pages/home/index" />
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.home-page {
  position: relative;
  background-color: $color-bg-page;
}

.env-banner {
  background: #fff5df;
  border-bottom: 1rpx solid #ffe4b5;
  padding: 10rpx 24rpx;
  text-align: center;

  &__text {
    font-size: 24rpx;
    color: #d97706;
    font-weight: 500;
  }
}

.home-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #fff;
}

.home-content {
  padding-bottom: 24rpx;
}

.home-page :deep(.ls-state) {
  width: calc(100% - 32px);
  margin: 16px;
}

// 骨架屏样式
.home-skeleton {
  padding: 24rpx;

  .skeleton-banner {
    height: 260rpx;
    border-radius: $radius-md;
    margin-bottom: 24rpx;
  }

  .skeleton-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 20rpx;
    background: #ffffff;
    padding: 24rpx;
    border-radius: $radius-md;
    margin-bottom: 24rpx;

    &__item {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12rpx;
    }

    .skeleton-circle {
      width: 88rpx;
      height: 88rpx;
      border-radius: 24rpx;
    }

    .skeleton-text {
      width: 72rpx;
      height: 24rpx;
    }
  }

  .skeleton-section {
    .skeleton-title {
      width: 200rpx;
      height: 36rpx;
      margin-bottom: 20rpx;
    }

    .skeleton-cards {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 16rpx;

      .skeleton-card {
        height: 380rpx;
        border-radius: $radius-md;
      }
    }
  }
}

.empty-floor {
  padding: 60rpx 0;
  text-align: center;

  &__text {
    font-size: 24rpx;
    color: $color-text-secondary;
  }
}

.home-footer-brand {
  padding: 40rpx 24rpx 20rpx;
  text-align: center;

  .brand-text {
    font-size: 24rpx;
    color: $color-text-muted;
    letter-spacing: 1rpx;
  }
}
.home-brand {
  box-sizing: border-box;
  min-height: calc(88rpx + var(--status-bar-height, 0px));
  padding: calc(10rpx + var(--status-bar-height, 0px)) 220rpx 10rpx 32rpx;
  display: flex;
  align-items: center;
  background: #fff;
}
.sticky-search {
  box-shadow: none;
  background: #f4f7fb;
}
.home-footer-brand .brand-text {
  color: #617088;
  font-size: 24rpx;
}
.recent-error {
  margin: 0 32rpx 32rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  background: #fff0ee;
  color: #c04436;
  font-size: 26rpx;
}
.recent-loading {
  margin: 0 32rpx 20rpx;
  min-height: 44rpx;
  display: flex;
  align-items: center;
  gap: 10rpx;
  color: #617088;
  font-size: 24rpx;
}
.recent-loading__dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background: #2855b7;
  opacity: 0.7;
}
.recent-empty {
  margin: 0 32rpx 20rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  background: #fff;
  border: 1rpx solid #e5ebf3;
  color: #617088;
  font-size: 24rpx;
  text-align: center;
}
</style>
