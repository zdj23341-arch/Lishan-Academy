<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElDialog } from 'element-plus';
import type { HomeDraft } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../api/content';
const props = defineProps<{
  contentId: string;
  kind: 'BOOK' | 'COURSE' | 'PAPER';
  disabled?: boolean;
}>();
const opened = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  sectionId = ref('');
const draft = ref<HomeDraft>();
const sections = computed(
  () => draft.value?.sections.filter((s) => s.type === `${props.kind}_GRID` && s.enabled) ?? [],
);
async function open() {
  opened.value = true;
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    draft.value = await contentApi.homeDraft();
    sectionId.value = sections.value[0]?.id ?? '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取首页草稿失败';
  } finally {
    busy.value = false;
  }
}
async function recommend() {
  if (busy.value || !draft.value || !sectionId.value || props.disabled) return;
  busy.value = true;
  error.value = '';
  try {
    draft.value = await contentApi.recommendHome(
      sectionId.value,
      draft.value.revision,
      props.contentId,
    );
    message.value = '推荐已保存，发布首页后生效。请到首页配置核对预览后发布。';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '推荐失败';
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <button type="button" :disabled="disabled" @click="open">首页推荐</button>
  <el-dialog
    :model-value="opened"
    title="推荐到首页栏目"
    width="520px"
    class="ls-recommend-dialog"
    append-to-body
    @close="opened = false"
  >
    <p v-if="busy" role="status">处理中…</p>
    <p v-if="error" role="alert">{{ error }}。本次未覆盖本地选择，请重新读取草稿核对。</p>
    <p v-if="message" role="status">{{ message }}</p>
    <label
      >首页栏目
      <select v-model="sectionId" :disabled="busy">
        <option v-for="s in sections" :key="s.id" :value="s.id">
          {{ s.title }} · {{ s.selectionMode === 'AUTO' ? '自动展示（置顶）' : '手动精选' }}
        </option>
      </select></label
    >
    <p v-if="!busy && !sections.length">尚无对应业务的启用栏目，请先到首页配置添加栏目。</p>
    <p>此操作仅修改所选栏目草稿，不会发布首页或其他草稿。</p>
    <template #footer
      ><button type="button" :disabled="busy" @click="open">重新读取草稿</button
      ><button type="button" :disabled="busy || !sectionId || !!message" @click="recommend">
        保存推荐</button
      ><button type="button" @click="opened = false">关闭</button></template
    >
  </el-dialog>
</template>
<style>
.ls-recommend-dialog {
  max-width: calc(100vw - 32px);
}
</style>
