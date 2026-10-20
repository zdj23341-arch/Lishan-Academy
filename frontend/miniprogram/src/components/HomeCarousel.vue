<script setup lang="ts">
import { computed, ref } from 'vue';
import BookCover from './ui/BookCover.vue';
import type { HomeView } from '@jxsc/shared';
import { navigate } from '../composables/useCatalog';

const props = defineProps<{ banners: HomeView['banners'] }>();

const bannerAsset = '/static/design/banners/home-three-business.png';
type ImageState = 'fallback' | 'placeholder';
const failedImages = ref(new Map<string, ImageState>());
const loadedSources = ref(new Set<string>());
const hasMultipleBanners = computed(() => props.banners.length > 1);

function imageSource(banner: HomeView['banners'][number]) {
  const state = failedImages.value.get(banner.id);
  if (state === 'placeholder') return '';
  if (state === 'fallback') return bannerAsset;
  return banner.imageUrl || bannerAsset;
}

function markImageFailed(banner: HomeView['banners'][number]) {
  const next = new Map(failedImages.value);
  const state = failedImages.value.get(banner.id);
  next.set(banner.id, banner.imageUrl && state !== 'fallback' ? 'fallback' : 'placeholder');
  failedImages.value = next;
}
</script>

<template>
  <view v-if="props.banners.length" class="home-carousel-wrap">
    <swiper
      class="home-swiper"
      :autoplay="hasMultipleBanners"
      :circular="hasMultipleBanners"
      :indicator-dots="hasMultipleBanners"
      :interval="4500"
      :duration="350"
      indicator-color="rgba(97, 112, 136, 0.32)"
      indicator-active-color="#2855b7"
    >
      <swiper-item v-for="banner in props.banners" :key="banner.id">
        <view class="home-swiper__item" :aria-label="banner.title" @click="navigate(banner.target)">
          <view class="banner-card">
            <image
              v-if="imageSource(banner)"
              class="banner-card__img"
              :class="{ 'banner-card__img--loaded': loadedSources.has(imageSource(banner)) }"
              :src="imageSource(banner)"
              @load="loadedSources.add(imageSource(banner))"
              @error="markImageFailed(banner)"
              mode="scaleToFill"
            />
            <view v-else class="banner-card__placeholder">
              <view class="banner-copy">
                <text class="banner-eyebrow">力山学堂 · 学习内容中心</text>
                <text class="banner-card__placeholder-text">{{ banner.title }}</text>
                <text class="banner-subtitle">开始探索</text>
              </view>
              <view class="banner-art"><BookCover :title="banner.title" /></view>
            </view>
          </view>
        </view>
      </swiper-item>
    </swiper>
  </view>
</template>
<style lang="scss" scoped>
@import '../styles/tokens.scss';
.home-carousel-wrap {
  position: relative;
  padding: 16rpx 32rpx 24rpx;
}
.home-swiper {
  width: 100%;
  height: 62vw;
}
.home-swiper__item {
  width: 100%;
  height: 100%;
  border-radius: 28rpx;
  overflow: hidden;
}
.banner-card {
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;
  background: #e9effa;
  border-radius: 28rpx;
}
.home-swiper__item--product {
  .banner-card {
    background: linear-gradient(135deg, #e9effa, #dce9fb);
  }
}
.home-swiper__item--paper_list,
.home-swiper__item--membership {
  .banner-card {
    background: linear-gradient(135deg, #fff5df, #f3e1bd);
  }
}
.banner-card__img {
  opacity: 0;
  width: 100%;
  height: 100%;
}
.banner-card__img--loaded {
  opacity: 1;
}
.banner-card__placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding: 40rpx;
}
.banner-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}
.banner-eyebrow {
  color: $color-primary;
  font-size: 24rpx;
  letter-spacing: 2rpx;
}
.banner-card__placeholder-text {
  font-family: STSong, SimSun, serif;
  font-size: 46rpx;
  line-height: 1.4;
  color: $color-text-main;
}
.banner-subtitle {
  font-size: 24rpx;
  color: $color-text-sub;
}
.banner-art {
  width: 170rpx;
  flex-shrink: 0;
  transform: rotate(7deg);
  box-shadow: 14rpx 14rpx 0 #cbd8ec;
  border-radius: 8rpx;
}
.banner-art :deep(.ls-cover) {
  aspect-ratio: 3/4;
}
.banner-art :deep(.ls-cover-title) {
  font-size: 40rpx;
}
.banner-art :deep(.ls-cover-caption) {
  display: none;
}
</style>
