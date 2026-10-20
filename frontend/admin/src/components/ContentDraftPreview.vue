<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import type { ContentBlockInput } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../api/content';
const props = withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    assetId?: string | null;
    blocks: ContentBlockInput[];
    price?: string;
    showCover?: boolean;
  }>(),
  { showCover: true },
);
const cover = ref('');
const failed = ref(false);
let sequence = 0;
watch(
  () => props.assetId,
  async (id) => {
    const current = ++sequence;
    cover.value = '';
    failed.value = false;
    if (!id) return;
    try {
      const asset = await contentApi.asset(id);
      if (current === sequence && asset.purpose === 'PUBLIC_IMAGE' && asset.status === 'READY')
        cover.value = asset.publicUrl || '';
    } catch {
      /* The preview stays empty when the selected cover cannot be read. */
    }
  },
  { immediate: true },
);
onUnmounted(() => {
  sequence++;
});
const paragraphs = computed(() =>
  props.blocks
    .filter((block) => block.type === 'TEXT')
    .map((block) => block.text)
    .filter(Boolean),
);
</script>
<template>
  <section class="admin-card draft-preview" aria-label="当前编辑内容预览">
    <h3>内容预览</h3>
    <img
      v-if="showCover !== false && cover && !failed"
      :src="cover"
      :alt="title || '当前封面'"
      @error="failed = true"
    />
    <div v-else-if="showCover !== false" class="draft-preview-placeholder">
      {{ assetId ? '封面暂不可预览' : '尚未选择封面' }}
    </div>
    <h4>{{ title || '尚未填写标题' }}</h4>
    <p v-if="subtitle">{{ subtitle }}</p>
    <strong v-if="price" class="draft-preview-price">¥{{ price }}</strong>
    <p v-for="(paragraph, index) in paragraphs" :key="index" class="draft-preview-text">
      {{ paragraph }}
    </p>
    <p class="draft-preview-note">
      预览当前编辑的{{
        showCover === false ? '文字' : '封面与文字'
      }}；用户端以保存后的发布内容为准。
    </p>
  </section>
</template>
<style scoped>
.draft-preview img,
.draft-preview-placeholder {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 8px;
}
.draft-preview-placeholder {
  display: grid;
  place-items: center;
  background: var(--ls-tint);
  color: var(--ls-sub);
  font-size: 12px;
}
.draft-preview h4 {
  margin: 14px 0 8px;
  font-size: 15px;
}
.draft-preview p {
  font-size: 13px;
}
.draft-preview h4,
.draft-preview p,
.draft-preview-price {
  overflow-wrap: anywhere;
}
.draft-preview-text {
  white-space: pre-wrap;
  max-height: 160px;
  overflow: auto;
}
.draft-preview-price {
  color: var(--ls-primary);
}
.draft-preview-note {
  color: var(--ls-sub);
  border-top: 1px solid var(--ls-line);
  padding-top: 12px;
  margin-bottom: 0;
}
</style>
