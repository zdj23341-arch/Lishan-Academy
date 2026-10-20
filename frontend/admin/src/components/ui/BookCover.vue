<script setup lang="ts">
import { ref, watch } from 'vue';
const props = withDefaults(
  defineProps<{
    title: string;
    imageUrl?: string | null;
    theme?: 'blue' | 'jade' | 'sand' | 'paper';
  }>(),
  { imageUrl: null, theme: 'blue' },
);
const failed = ref(false);
watch(
  () => props.imageUrl,
  () => {
    failed.value = false;
  },
);
</script>
<template>
  <div class="ls-cover" :class="'ls-cover--' + theme">
    <img
      v-if="imageUrl && !failed"
      :src="imageUrl"
      :alt="title"
      class="ls-cover-image"
      @error="failed = true"
    />
    <div v-else class="ls-cover-art">
      <span class="ls-cover-title">{{ title }}</span
      ><span class="ls-cover-caption">力山学堂 · 阅读与成长</span>
    </div>
  </div>
</template>
<style scoped>
.ls-cover {
  position: relative;
  overflow: hidden;
  width: 100%;
  aspect-ratio: 4/3;
  min-width: 0;
  background: #294c7f;
  color: #fff;
  border-radius: 8px;
}
.ls-cover--jade {
  background: #3b7169;
}
.ls-cover--sand {
  background: #866648;
}
.ls-cover--paper {
  background: #f7f4ed;
  color: #324057;
  box-shadow: inset 0 0 0 1px #e4dfd3;
}
.ls-cover-image {
  width: 100%;
  height: 100%;
  position: absolute;
  inset: 0;
  object-fit: cover;
}
.ls-cover-art {
  height: 100%;
  position: absolute;
  inset: 0;
  padding: 17px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
}
.ls-cover-art:before {
  content: '';
  position: absolute;
  left: 8px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: currentColor;
  opacity: 0.22;
}
.ls-cover-title {
  font-family: STSong, SimSun, serif;
  font-size: 23px;
  line-height: 1.4;
  letter-spacing: 2px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ls-cover-caption {
  font-size: 12px;
  opacity: 0.72;
}
</style>
