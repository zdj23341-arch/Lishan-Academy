<script setup lang="ts">
import UiIcon from './ui/UiIcon.vue';
import type { HomeView } from '@jxsc/shared';
import { navigate } from '../composables/useCatalog';

defineProps<{ shortcuts: HomeView['shortcuts'] }>();
function shortcutIcon(label: string, targetType: string) {
  if (targetType === 'PAPER_LIST') return 'files';
  if (targetType === 'MEMBERSHIP' || targetType === 'MEMBERSHIP_CENTER') return 'crown';
  return label.includes('会员试卷') || label.includes('试卷下载') || label.includes('试卷资料')
    ? 'files'
    : label.includes('会员')
      ? 'crown'
      : label.includes('试卷') || label.includes('卷')
        ? 'files'
        : label.includes('物流')
          ? 'truck'
          : label.includes('书')
            ? 'book-marked'
            : label.includes('课程')
              ? 'video'
              : label.includes('文章') || label.includes('图文')
                ? 'notebook-pen'
                : label.includes('分销')
                  ? 'share-2'
                  : label.includes('学习')
                    ? 'graduation-cap'
                    : label.includes('作文') || label.includes('写作')
                      ? 'notebook-pen'
                      : 'library';
}
</script>

<template>
  <view v-if="shortcuts && shortcuts.length" class="shortcut-grid-card ui-card">
    <view class="shortcut-grid" :class="{ 'shortcut-grid--six': shortcuts.length === 6 }">
      <view
        v-for="(entry, index) in shortcuts"
        :key="entry.id"
        class="shortcut-item"
        @click="navigate(entry.target)"
      >
        <view class="shortcut-item__icon-wrap">
          <!-- 首页确认稿使用随包发布的设计图标；后端 iconUrl 只作为内容数据，不能覆盖 UI 素材。 -->
          <UiIcon
            :name="shortcutIcon(entry.label, entry.target.type)"
            :tone="index % 2 ? 'success' : 'blue'"
            :size="22"
          />
        </view>
        <text class="shortcut-item__label text-ellipsis">{{ entry.label }}</text>
      </view>
    </view>
  </view>
</template>
<style lang="scss" scoped>
@import '../styles/tokens.scss';
.shortcut-grid-card {
  margin: 16rpx 32rpx 32rpx;
  padding: 10rpx 0;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 32rpx 16rpx;
}
.shortcut-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14rpx;
  min-height: 116rpx;
}
.shortcut-item__icon-wrap {
  width: 80rpx;
  height: 80rpx;
  border-radius: 24rpx;
  background: $color-primary-light;
  border: 1rpx solid rgba(40, 85, 183, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
}
.shortcut-item:nth-child(2n) .shortcut-item__icon-wrap {
  background: $color-accent-light;
}
.shortcut-item__icon {
  width: 44rpx;
  height: 44rpx;
}
.shortcut-item__label {
  font-size: 22rpx;
  color: $color-text-main;
  max-width: 100%;
}
</style>
