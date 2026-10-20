<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import type { ChapterView } from '@jxsc/shared';
import { learningApi } from '../api/learning';
import { session } from '../api/session';

const props = defineProps<{ courseId: string; productId: string; owned: boolean }>();
const chapters = ref<ChapterView[]>([]);
const loading = ref(false);
const error = ref('');
const hasMore = ref(false);
let page = 0;
let epoch = 0;
async function load(more = false) {
  const ticket = ++epoch;
  const id = props.courseId;
  if (!more) {
    chapters.value = [];
    page = 0;
    hasMore.value = false;
  }
  loading.value = true;
  error.value = '';
  try {
    const result = await learningApi.courseChapters(id, page + 1);
    if (ticket !== epoch) return;
    chapters.value = more ? [...chapters.value, ...result.items] : result.items;
    page = result.page;
    hasMore.value = result.items.length > 0 && page * result.pageSize < result.total;
  } catch {
    if (ticket === epoch) error.value = '章节加载失败，请重试';
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}
function open(chapter: ChapterView) {
  if (!chapter.canPlay || loading.value) return;
  uni.navigateTo({
    url: `/pages/course/learn?id=${encodeURIComponent(props.courseId)}&productId=${encodeURIComponent(props.productId)}&chapterId=${encodeURIComponent(chapter.id)}`,
  });
}
function duration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
watch(
  () => props.courseId,
  () => {
    void load();
  },
  { immediate: true },
);
const unsubscribe = session.subscribe(() => {
  void load();
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
});
</script>

<template>
  <view class="chapter-preview ui-card">
    <text class="chapter-preview__title">课程章节</text>
    <text v-if="loading">正在加载章节…</text>
    <view v-if="error" role="alert">{{ error }}<button @click="load()">重试</button></view>
    <text v-else-if="!loading && !chapters.length">暂无章节</text>
    <button
      v-for="(chapter, index) in chapters"
      :key="chapter.id"
      class="chapter-preview__row"
      :disabled="!chapter.canPlay || loading"
      @click="open(chapter)"
    >
      <text class="chapter-preview__name">{{ index + 1 }}. {{ chapter.title }}</text>
      <view class="chapter-preview__meta">
        <text>{{ duration(chapter.durationSeconds) }}</text>
        <text v-if="chapter.isTrial" class="ui-tag ui-tag--accent">试看</text>
        <text>{{
          chapter.canPlay
            ? chapter.isTrial
              ? '点击试看'
              : '进入学习'
            : chapter.isTrial || owned
              ? '暂不可播放'
              : '购买后解锁'
        }}</text>
      </view>
    </button>
    <button v-if="hasMore" :disabled="loading" @click="load(true)">加载更多章节</button>
  </view>
</template>

<style scoped lang="scss">
@import '../styles/tokens.scss';
.chapter-preview {
  margin-bottom: 24rpx;
  padding: 28rpx;
}
.chapter-preview__title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  margin-bottom: 16rpx;
}
.chapter-preview__row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  width: 100%;
  min-height: 96rpx;
  margin: 0;
  padding: 20rpx 0;
  border-radius: 0;
  background: transparent;
  color: $color-text-main;
  font-size: 26rpx;
  line-height: 1.5;
  text-align: left;
}
.chapter-preview__row::after {
  border: 0;
  border-bottom: 1rpx solid $color-border-light;
  border-radius: 0;
}
.chapter-preview__row[disabled] {
  background: transparent;
  color: $color-text-sub;
}
.chapter-preview__name {
  font-weight: 600;
}
.chapter-preview__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16rpx;
  margin-top: 8rpx;
  color: $color-text-sub;
  font-size: 24rpx;
}
</style>
