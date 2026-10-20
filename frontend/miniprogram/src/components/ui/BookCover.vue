<script setup lang="ts">
import { computed, ref, watch } from 'vue';
const props = withDefaults(
  defineProps<{
    title: string;
    imageUrl?: string | null;
    theme?: 'blue' | 'jade' | 'sand' | 'paper';
  }>(),
  { imageUrl: null, theme: 'blue' },
);
const failed = ref(false);
const loaded = ref(false);
const localAsset = computed(() => `/static/design/covers/${props.theme}.png`);
watch(
  () => props.imageUrl,
  () => {
    failed.value = false;
    loaded.value = false;
  },
);
</script>
<template>
  <view class="ls-cover" :class="'ls-cover--' + theme"
    ><image
      v-if="!failed"
      :src="imageUrl || localAsset"
      mode="aspectFit"
      class="ls-cover-image"
      :class="{ 'ls-cover-image--loaded': loaded }"
      @load="loaded = true"
      @error="failed = true"
    /><view v-else class="ls-cover-art"
      ><text class="ls-cover-brand">力山学堂 · 语文学习</text
      ><text class="ls-cover-title">{{ title }}</text
      ><text class="ls-cover-caption">读懂文本 · 写出思考</text></view
    ></view
  >
</template>
<style scoped>
.ls-cover {
  position: relative;
  overflow: hidden;
  width: 100%;
  aspect-ratio: 3 / 4;
  min-width: 0;
  background: linear-gradient(135deg, #294b7f, #172d50);
  color: #fff;
  border-radius: 16rpx;
}
.ls-cover--jade {
  background: linear-gradient(135deg, #41776c, #204c43);
}
.ls-cover--sand {
  background: linear-gradient(135deg, #a18157, #6d5136);
}
.ls-cover--paper {
  background: #f7f4ed;
  color: #324057;
  box-shadow: inset 0 0 0 1px #e4dfd3;
}
.ls-cover-image {
  opacity: 0;
  width: 100%;
  height: 100%;
  position: absolute;
  inset: 0;
  object-fit: contain;
  background: inherit;
}
.ls-cover-image--loaded {
  opacity: 1;
}
.ls-cover-art {
  height: 100%;
  position: absolute;
  inset: 0;
  padding: 28rpx 34rpx 28rpx 46rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 16rpx;
}
.ls-cover-art:before {
  content: '';
  position: absolute;
  left: 18rpx;
  top: 0;
  bottom: 0;
  width: 1px;
  background: currentColor;
  opacity: 0.22;
}
.ls-cover-brand {
  font-size: 22rpx;
  opacity: 0.78;
}
.ls-cover-title {
  font-family: STSong, SimSun, serif;
  font-size: 40rpx;
  line-height: 1.4;
  letter-spacing: 4rpx;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ls-cover-caption {
  font-size: 22rpx;
  opacity: 0.72;
}
</style>
