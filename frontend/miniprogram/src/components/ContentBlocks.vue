<script setup lang="ts">
import type { ContentBlock } from '@jxsc/shared';
import { ref, watch } from 'vue';
import UiIcon from './ui/UiIcon.vue';

const props = defineProps<{ blocks: ContentBlock[] }>();
const failed = ref<string[]>([]);
watch(
  () => props.blocks,
  () => {
    failed.value = [];
  },
);
function retry(url: string) {
  failed.value = failed.value.filter((value) => value !== url);
}
</script>

<template>
  <view v-if="blocks && blocks.length" class="content-blocks-flow">
    <view v-for="(block, index) in blocks" :key="index" class="content-block-item">
      <!-- 文本段落 -->
      <text v-if="block.type === 'TEXT'" class="block-text">{{ block.text }}</text>

      <!-- 详情图文图片 -->
      <view v-else-if="block.type === 'IMAGE'" class="block-image-wrap">
        <image
          v-if="!failed.includes(block.url)"
          class="block-image"
          :src="block.url"
          :aria-label="block.alt || '内容详情图片'"
          mode="widthFix"
          lazy-load
          @error="failed = [...failed, block.url]"
        />
        <view v-else class="block-image-error ls-status" role="status">
          <UiIcon name="image" tone="neutral" :size="24" />
          <text>{{ block.alt || '内容详情图片' }}暂时无法显示</text>
          <button class="ls-button ls-button--soft" @click="retry(block.url)">重新加载图片</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.content-blocks-flow {
  width: 100%;
}

.content-block-item {
  margin-bottom: 20rpx;

  &:last-child {
    margin-bottom: 0;
  }
}

.block-text {
  display: block;
  font-size: 28rpx;
  line-height: 1.7;
  color: $color-text-main;
  white-space: pre-wrap;
  word-break: break-word;
}

.block-image-wrap {
  width: 100%;
  border-radius: $radius-sm;
  overflow: hidden;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}

.block-image {
  width: 100%;
  display: block;
}
.block-image-error {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  background: $color-bg-page;
  color: $color-text-sub;
  font-size: 26rpx;
  text-align: center;
}
</style>
