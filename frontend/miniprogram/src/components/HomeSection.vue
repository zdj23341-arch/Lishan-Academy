<script setup lang="ts">
import { computed } from 'vue';
import UiIcon from './ui/UiIcon.vue';
import type { HomeView } from '@jxsc/shared';
import { navigate } from '../composables/useCatalog';
import ProductCard from './ProductCard.vue';
import BookCover from './ui/BookCover.vue';

const props = defineProps<{ section: HomeView['sections'][number] }>();
const papers = () => props.section.papers ?? [];
const fallbackMoreTargets: Partial<
  Record<
    HomeView['sections'][number]['type'],
    NonNullable<HomeView['sections'][number]['moreTarget']>
  >
> = {
  BOOK_GRID: { type: 'PRODUCT_LIST', id: null, filters: { type: 'BOOK' } },
  COURSE_GRID: { type: 'PRODUCT_LIST', id: null, filters: { type: 'COURSE' } },
  PAPER_GRID: { type: 'PAPER_LIST', id: null, filters: { paperType: 'PRACTICE' } },
};
const moreTarget = computed(
  () => props.section.moreTarget ?? fallbackMoreTargets[props.section.type] ?? null,
);
function paperAccessLabel(value: string | null | undefined) {
  if (value === 'ACTIVE_MEMBER') return '会员可访问';
  return value ? `访问条件：${value}` : '访问条件待确认';
}
function schoolStageLabel(value: string | null | undefined) {
  if (value === 'PRIMARY') return '小学';
  if (value === 'JUNIOR') return '初中';
  if (value === 'SENIOR') return '高中';
  return value || '学段待确认';
}
function paperStatusLabel(value: string | null | undefined) {
  if (value === 'READY') return '资源已就绪';
  if (value === 'NOT_READY') return '资源准备中';
  return value ? `资源状态：${value}` : '资源状态待确认';
}
function openPaper(id: string) {
  uni.navigateTo({ url: `/pages/paper/detail?id=${encodeURIComponent(id)}` });
}
</script>

<template>
  <view
    class="home-section"
    :class="`home-section--${section.type.toLowerCase().replace('_', '-')}`"
  >
    <!-- 楼层标题栏 -->
    <view class="home-section__header">
      <view class="title-wrap">
        <view class="title-bar" />
        <text class="title-text">{{ section.title }}</text>
      </view>

      <view v-if="moreTarget" class="more-btn" @click="navigate(moreTarget)">
        <text class="more-text">查看更多</text>
        <UiIcon name="chevron-right" class="more-icon" :size="18" />
      </view>
    </view>

    <!-- 会员专区特色横幅 -->
    <view
      v-if="section.type === 'MEMBERSHIP_ENTRY'"
      class="vip-banner-card"
      @click="navigate({ type: 'MEMBERSHIP', id: null, filters: {} })"
    >
      <view class="vip-banner-card__left">
        <view class="vip-crown-badge">试卷会员</view>
        <text class="vip-banner-card__title">开通试卷专属特权</text>
        <text class="vip-banner-card__desc">有效期内访问 Word / PDF / 图片资料</text>
      </view>
      <button class="vip-banner-card__btn">立即开通</button>
    </view>

    <!-- 双列商品网格 -->
    <view
      v-if="
        (section.type === 'PRODUCT_GRID' ||
          section.type === 'BOOK_GRID' ||
          section.type === 'COURSE_GRID' ||
          section.type === 'MEMBERSHIP_ENTRY') &&
        section.products.length
      "
      class="product-grid"
    >
      <ProductCard v-for="product in section.products" :key="product.id" :product="product" />
    </view>

    <!-- 试卷专栏使用服务端返回的 papers，列表顺序和内容不在客户端重排。 -->
    <view v-else-if="section.type === 'PAPER_GRID' && papers().length" class="paper-grid">
      <view
        v-for="paper in papers()"
        :key="paper.id"
        class="paper-grid__item"
        @click="openPaper(paper.id)"
      >
        <BookCover
          :title="paper.title"
          :image-url="paper.coverUrl ?? null"
          theme="paper"
          class="paper-grid__cover"
        />
        <view class="paper-grid__body">
          <text class="paper-grid__title text-ellipsis-2">{{ paper.title }}</text>
          <text class="paper-grid__meta text-ellipsis">
            {{ schoolStageLabel(paper.schoolStage || paper.stage) }} · {{ paper.grade }}年级 ·
            {{ paper.year == null ? '年份待确认' : paper.year }}
          </text>
          <text class="paper-grid__meta text-ellipsis">
            {{
              paper.attachmentFormats?.length
                ? paper.attachmentFormats.join(' / ')
                : '附件格式待确认'
            }}
          </text>
          <text class="paper-grid__status">{{ paperAccessLabel(paper.accessCondition) }}</text>
          <text class="paper-grid__status">{{ paperStatusLabel(paper.resourceStatus) }}</text>
        </view>
      </view>
    </view>

    <!-- 独立图文卡片 -->
    <view
      v-else-if="section.type === 'ARTICLE_CARD' && section.article"
      class="article-card"
      @click="navigate({ type: 'ARTICLE', id: section.article.id, filters: {} })"
    >
      <BookCover
        :title="section.article.title"
        :image-url="section.article.coverUrl"
        theme="jade"
        class="article-card__cover"
      />
      <view class="article-card__body">
        <view class="ui-tag ui-tag--primary">精品导读</view>
        <text class="article-card__title text-ellipsis-2">{{ section.article.title }}</text>
      </view>
    </view>

    <!-- 模块空状态 -->
    <view
      v-else-if="!section.products.length && !section.article && !papers().length"
      class="empty-box"
    >
      <text class="empty-text">该模块暂未发布内容</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.home-section {
  margin: 24rpx 32rpx 32rpx;

  &--product-grid .title-bar {
    display: block;
  }

  &--membership-entry {
    .title-text {
      color: $color-vip-dark;
    }
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20rpx;
  }
}

.title-wrap {
  display: flex;
  align-items: center;
}

.title-bar {
  width: 8rpx;
  height: 32rpx;
  border-radius: 4rpx;
  background: #2855b7;
  margin-right: 14rpx;
}

.title-text {
  font-size: 32rpx;
  font-weight: 700;
  color: $color-text-main;
  letter-spacing: 0.5rpx;
}

.more-btn {
  display: flex;
  align-items: center;
  color: $color-text-secondary;
  cursor: pointer;
  padding: 4rpx 0;

  &:active {
    opacity: 0.7;
  }

  .more-text {
    font-size: 24rpx;
  }

  .more-icon {
    width: 28rpx;
    height: 28rpx;
    margin-left: 2rpx;
  }
}

// 会员特色卡片
.vip-banner-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 28rpx;
  border-radius: $radius-md;
  background: $color-vip-card-bg;
  box-shadow: $shadow-card;
  margin-bottom: 20rpx;
  position: relative;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    top: -40rpx;
    right: -40rpx;
    width: 140rpx;
    height: 140rpx;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(229, 186, 115, 0.25) 0%, transparent 70%);
  }

  &__left {
    display: flex;
    flex-direction: column;
  }

  .vip-crown-badge {
    align-self: flex-start;
    padding: 2rpx 14rpx;
    border-radius: 999rpx;
    background: $color-vip-gradient;
    color: #382405;
    font-size: 24rpx;
    font-weight: 700;
    margin-bottom: 8rpx;
  }

  &__title {
    font-size: 28rpx;
    font-weight: 700;
    color: $color-text-main;
    margin-bottom: 4rpx;
  }

  &__desc {
    font-size: 24rpx;
    color: #b5a894;
  }

  &__btn {
    height: 56rpx;
    line-height: 56rpx;
    padding: 0 28rpx;
    border-radius: 999rpx;
    background: $color-vip-gradient;
    color: #382405;
    font-size: 24rpx;
    font-weight: 700;
    border: none;
    box-shadow: $shadow-gold;

    &::after {
      display: none;
    }

    &:active {
      opacity: 0.9;
    }
  }
}

// 双列商品网格
.product-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
}

.paper-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
}

.paper-grid__item {
  min-width: 0;
  padding: 0;
  background: $color-bg-card;
  border: 1rpx solid $color-border;
  border-radius: $radius-md;
  overflow: hidden;
}

.paper-grid__cover {
  width: 100%;
  aspect-ratio: 3 / 4;
}
.paper-grid__cover :deep(.ls-cover) {
  border-radius: 0;
}

.paper-grid__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 20rpx;
  gap: 12rpx;
}

.paper-grid__title {
  font-size: 24rpx;
  line-height: 1.4;
  font-weight: 600;
  color: $color-text-main;
}

.paper-grid__meta,
.paper-grid__status {
  font-size: 22rpx;
  line-height: 1.35;
  color: $color-text-secondary;
}

.paper-grid__status {
  color: $color-primary;
}

.home-section--product-grid:nth-of-type(3) .title-bar {
  background: $color-accent;
}

.home-section--membership-entry .product-grid {
  margin-top: 4rpx;
}

// 图文卡片
.article-card {
  display: flex;
  background: $color-bg-card;
  border-radius: $radius-md;
  overflow: hidden;
  box-shadow: $shadow-card;
  padding: 16rpx;
  gap: 24rpx;
  cursor: pointer;

  &__cover {
    width: 180rpx;
    height: 120rpx;
    border-radius: $radius-sm;
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 8rpx;
  }

  &__title {
    font-size: 26rpx;
    font-weight: 600;
    color: $color-text-main;
  }
}

.empty-box {
  padding: 40rpx;
  text-align: center;
  background-color: $color-bg-card;
  border-radius: $radius-md;

  .empty-text {
    font-size: 24rpx;
    color: $color-text-secondary;
  }
}
.title-bar {
  display: none;
}
.vip-banner-card {
  box-shadow: none;
  border: 1rpx solid #e5ebf3;
}
.vip-banner-card__title {
  color: #18283e;
}
.vip-banner-card__desc {
  color: #617088;
}
.article-card__cover :deep(.ls-cover-art) {
  padding: 16rpx 18rpx 16rpx 26rpx;
}
.article-card__cover :deep(.ls-cover-title) {
  font-size: 24rpx;
  letter-spacing: 0;
}
.article-card__cover :deep(.ls-cover-brand),
.article-card__cover :deep(.ls-cover-caption) {
  display: none;
}
</style>
