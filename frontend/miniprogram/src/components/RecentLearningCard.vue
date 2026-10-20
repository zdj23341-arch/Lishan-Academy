<script setup lang="ts">
import type { RecentLearningView } from '../../../shared/src/learning/recent';
import BookCover from './ui/BookCover.vue';
defineProps<{ recent: RecentLearningView; loading?: boolean }>();
defineEmits<{ continue: [] }>();
</script>
<template>
  <view class="recent-card">
    <view class="recent-header"
      ><text>继续上次学习</text><text class="recent-source">本机学习记录</text></view
    >
    <view class="recent-row"
      ><BookCover :title="recent.course.title" theme="jade" class="recent-cover" /><view
        class="recent-info"
        ><text class="recent-title">{{ recent.course.title }}</text
        ><text class="recent-chapter"
          >{{ recent.chapter.title }} · 已学
          {{ Math.floor(recent.positionSeconds / 60) }} 分钟</text
        ><view class="recent-progress"
          ><view
            class="recent-progress__bar"
            :style="{ width: recent.progressPercent + '%' }" /></view></view
      ><button
        class="recent-continue ls-button ls-button--soft"
        :disabled="loading"
        @click="$emit('continue')"
      >
        继续
      </button></view
    >
  </view>
</template>
<style scoped>
.recent-card {
  margin: 0 32rpx 32rpx;
  padding: 28rpx;
  background: white;
  border: 1rpx solid #e5ebf3;
  border-radius: 24rpx;
}
.recent-header {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  font-size: 30rpx;
  font-weight: 600;
  margin-bottom: 24rpx;
}
.recent-source {
  font-size: 24rpx;
  font-weight: 400;
  color: #617088;
}
.recent-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.recent-cover {
  width: 108rpx;
  height: 144rpx;
  flex-shrink: 0;
}
.recent-cover :deep(.ls-cover-title) {
  font-size: 30rpx;
}
.recent-cover :deep(.ls-cover-art) {
  padding: 16rpx;
}
.recent-cover :deep(.ls-cover-caption) {
  display: none;
}
.recent-cover :deep(.ls-cover-brand) {
  display: none;
}
.recent-info {
  min-width: 0;
  flex: 1;
}
.recent-title {
  font-size: 28rpx;
  font-weight: 600;
  display: block;
}
.recent-chapter {
  font-size: 24rpx;
  color: #617088;
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}
.recent-progress {
  height: 10rpx;
  border-radius: 10rpx;
  background: #e5ebf3;
  overflow: hidden;
  margin-top: 16rpx;
}
.recent-progress__bar {
  height: 100%;
  background: #197460;
}
.recent-continue {
  padding: 8rpx 16rpx;
  font-size: 24rpx;
  min-height: 88rpx;
}
</style>
