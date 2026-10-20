<script setup lang="ts">
import { formatShanghaiDateTime } from '@jxsc/shared';
import { onLoad } from '@dcloudio/uni-app';
import { getCurrentInstance, ref } from 'vue';
import { catalog, useCatalogDetail } from '../../composables/useCatalog';
import AsyncState from '../../components/AsyncState.vue';
import ContentBlocks from '../../components/ContentBlocks.vue';
import BookCover from '../../components/ui/BookCover.vue';
const { data: article, loading, error, load } = useCatalogDetail(catalog.article);
const agreement = ref(false);
const instance = getCurrentInstance();
async function loadArticle(id?: string) {
  await load(id);
  if (!agreement.value) return;
  if (error.value) {
    const page = instance?.proxy as {
      getOpenerEventChannel?: () => { emit: (event: string) => void };
    } | null;
    page?.getOpenerEventChannel?.()?.emit('agreementUnavailable');
  } else if (article.value) {
    uni.setNavigationBarTitle({ title: article.value.title });
  }
}
onLoad((options) => {
  agreement.value = options?.agreement === 'terms' || options?.agreement === 'privacy';
  void loadArticle(options?.id ?? '');
});
</script>
<template>
  <view class="ls-mini-page ls-article-page"
    ><AsyncState :loading="loading" :error="error" @retry="loadArticle()" /><view
      v-if="article"
      class="ls-article-body"
      ><text v-if="!agreement && article.categoryName" class="article-category">{{
        article.categoryName
      }}</text>
      <text class="ls-article-title">{{ article.title }}</text>
      <view v-if="article.authorName || article.updatedAt" class="ls-article-meta">
        <text v-if="article.authorName">作者：{{ article.authorName }}</text>
        <text v-if="article.updatedAt"
          >更新时间：{{ formatShanghaiDateTime(article.updatedAt) }}</text
        >
      </view>
      <view v-if="!agreement && !article.coverUrl" class="article-cover-art">
        <text class="article-cover-art__brand">力山学堂 · 语文学习</text>
        <text class="article-cover-art__title">{{ article.title }}</text>
        <text class="article-cover-art__caption">读懂文本 · 写出思考</text>
      </view>
      <BookCover
        v-else-if="!agreement"
        :title="article.title"
        :image-url="article.coverUrl"
        theme="jade"
        class="article-cover" /><ContentBlocks :blocks="article.contentBlocks" /></view
  ></view>
</template>
<style scoped>
.ls-article-page {
  padding: 16px;
}
.ls-article-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  border: 1px solid #e5ebf3;
  border-radius: 13px;
  background: #fff;
}
.article-category {
  align-self: flex-start;
  display: inline-block;
  padding: 3px 9px;
  border-radius: 5px;
  color: #2855b7;
  background: #edf3ff;
  font-size: 12px;
  line-height: 1.9;
}
.ls-article-title {
  margin-bottom: 0;
  font-family: STSong, SimSun, serif;
  font-size: 22px;
  font-weight: 650;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.ls-article-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-bottom: 0;
  color: #617088;
  font-size: 12px;
  line-height: 1.9;
}
.article-cover {
  aspect-ratio: 4 / 3;
  border-radius: 8px;
  margin-bottom: 0;
  background: #3b7169;
}
.article-cover :deep(.ls-cover) {
  aspect-ratio: 4 / 3;
  border-radius: 8px;
  background: #3b7169;
}
.article-cover-art {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
  box-sizing: border-box;
  aspect-ratio: 4 / 3;
  width: 100%;
  padding: 14px;
  border-radius: 8px;
  background: #3b7169;
  color: #fff;
  overflow: hidden;
}
.article-cover-art::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: #fff;
  opacity: 0.22;
}
.article-cover-art__brand,
.article-cover-art__caption {
  font-family: 'Microsoft YaHei', sans-serif;
  font-size: 12px;
  line-height: 1.4;
  letter-spacing: 1px;
  opacity: 0.733;
}
.article-cover-art__title {
  font-family: STSong, SimSun, serif;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 2px;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ls-article-body :deep(.block-text) {
  color: #617088;
  font-size: 13px;
  line-height: 1.9;
}
.ls-article-body :deep(.content-block-item) {
  margin-bottom: 16px;
}
.ls-article-body :deep(.content-block-item:last-child) {
  margin-bottom: 0;
}
.article-cover :deep(.ls-cover-art) {
  padding: 14px;
  gap: 5px;
}
.article-cover :deep(.ls-cover-art::before) {
  left: 8px;
}
.article-cover :deep(.ls-cover-brand),
.article-cover :deep(.ls-cover-caption) {
  font-size: 12px;
  line-height: 1.4;
  letter-spacing: 1px;
  opacity: 0.733;
}
.article-cover :deep(.ls-cover-title) {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 2px;
}
</style>
