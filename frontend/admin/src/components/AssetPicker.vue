<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ElButton, ElDialog } from 'element-plus';
import type { AdminAsset } from '@jxsc/shared/content/admin-types';
import type { UploadServices } from '@jxsc/shared/content/upload';
import { contentApi, uploadServices } from '../api/content';
import AssetUpload from './AssetUpload.vue';

const props = defineProps<{ modelValue: string | null; services?: UploadServices }>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; busy: [value: boolean] }>();
const opened = ref(false),
  visited = ref(false),
  loading = ref(false),
  uploading = ref(false);
const images = ref<AdminAsset[]>([]),
  page = ref(1),
  current = ref<AdminAsset | null>(null);
const error = ref(''),
  currentError = ref('');
const failedThumbnails = ref(new Set<string>());
const visibleImages = computed(() => images.value.slice((page.value - 1) * 10, page.value * 10));
const pages = computed(() => Math.max(1, Math.ceil(images.value.length / 10)));
let readSequence = 0,
  currentSequence = 0;
watch(
  () => props.modelValue,
  async (value) => {
    const sequence = ++currentSequence;
    currentError.value = '';
    if (!value) {
      current.value = null;
      return;
    }
    if (current.value?.id === value) return;
    current.value = null;
    try {
      const asset = await contentApi.asset(value);
      if (sequence === currentSequence) current.value = asset;
    } catch {
      if (sequence === currentSequence)
        currentError.value = '当前图片信息暂不可读，可重新选择图片。';
    }
  },
  { immediate: true },
);
async function load() {
  const sequence = ++readSequence;
  loading.value = true;
  error.value = '';
  images.value = [];
  page.value = 1;
  failedThumbnails.value = new Set();
  const resultImages = new Map<string, AdminAsset>();
  try {
    // The existing endpoint pages every purpose. Scan all pages before reporting image totals.
    for (let next = 1; ; next++) {
      const result = await contentApi.assets({ page: next, pageSize: 100 });
      if (sequence !== readSequence || !opened.value) return;
      for (const asset of result.items) {
        if (asset.purpose === 'PUBLIC_IMAGE' && asset.status === 'READY')
          resultImages.set(asset.id, asset);
      }
      if (result.page * result.pageSize >= result.total) break;
      if (!result.items.length || result.page !== next || result.pageSize <= 0)
        throw new Error('素材分页发生变化，请刷新图片后重试');
    }
    if (sequence === readSequence) images.value = [...resultImages.values()];
  } catch (cause) {
    if (sequence === readSequence)
      error.value = cause instanceof Error ? cause.message : '图片加载失败，请重试';
  } finally {
    if (sequence === readSequence) loading.value = false;
  }
}
function open() {
  visited.value = true;
  opened.value = true;
  void load();
}
function cancel() {
  if (uploading.value) return;
  opened.value = false;
  readSequence++;
  loading.value = false;
}
function choose(asset: AdminAsset) {
  currentSequence++;
  current.value = asset;
  currentError.value = '';
  emit('update:modelValue', asset.id);
}
function select(asset: AdminAsset) {
  choose(asset);
  cancel();
}
function ready(asset: AdminAsset) {
  choose(asset);
  if (!images.value.some((item) => item.id === asset.id)) images.value.unshift(asset);
  page.value = 1;
}
function uploadBusy(value: boolean) {
  uploading.value = value;
  emit('busy', value);
}
function beforeClose(done: () => void) {
  if (!uploading.value) {
    cancel();
    done();
  }
}
onUnmounted(() => {
  readSequence++;
  currentSequence++;
});
</script>
<template>
  <div class="ls-asset-picker ls-asset-picker--compact">
    <div class="ls-asset-current">
      <img
        v-if="
          current?.publicUrl &&
          current.purpose === 'PUBLIC_IMAGE' &&
          current.status === 'READY' &&
          !failedThumbnails.has(current.id)
        "
        :src="current.publicUrl"
        :alt="current.name"
        class="ls-asset-thumbnail"
        @error="failedThumbnails.add(current.id)"
      />
      <span v-else class="ls-asset-placeholder">{{ modelValue ? '已关联图片' : '暂无图片' }}</span>
      <div>
        <p>{{ current?.name || (modelValue ? '当前图片' : '请选择公开图片') }}</p>
        <p v-if="current && current.status !== 'READY'" role="alert">
          当前素材不可用，请更换图片。
        </p>
        <p v-if="currentError" role="status">{{ currentError }}</p>
        <details v-if="modelValue">
          <summary>素材详情</summary>
          <p>已选择：{{ modelValue }}</p>
          <p>{{ current?.status === 'READY' ? '公开图片 · 已就绪' : '请核对素材状态' }}</p>
        </details>
      </div>
      <el-button :disabled="uploading" @click="open">{{
        modelValue ? '更换图片' : '选择图片'
      }}</el-button>
    </div>
    <el-dialog
      v-if="visited"
      :model-value="opened"
      title="选择公开图片"
      width="760px"
      class="ls-image-dialog"
      append-to-body
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
      :before-close="beforeClose"
    >
      <p>仅展示已就绪的公开图片。上传新图片后会直接关联到当前字段。</p>
      <p v-if="loading" role="status">正在读取可用图片，请稍候…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <el-button :disabled="loading || uploading" @click="load">刷新图片</el-button>
      <template v-if="!loading && !error">
        <p>共 {{ images.length }} 张可用图片</p>
        <ul class="ls-asset-grid">
          <li v-for="asset in visibleImages" :key="asset.id">
            <img
              v-if="asset.publicUrl && !failedThumbnails.has(asset.id)"
              :src="asset.publicUrl"
              :alt="asset.name"
              class="ls-asset-thumbnail"
              loading="lazy"
              @error="failedThumbnails.add(asset.id)"
            />
            <span v-else class="ls-asset-placeholder">图片暂不可读</span>
            <el-button :disabled="uploading" @click="select(asset)"
              >选择 {{ asset.name }}</el-button
            >
          </li>
        </ul>
        <p v-if="!images.length">暂无可用公开图片，可上传新图片。</p>
        <div class="ls-image-pagination">
          <el-button :disabled="uploading || page <= 1" @click="page--">上一页图片</el-button
          ><span>{{ page }} / {{ pages }}</span
          ><el-button :disabled="uploading || page >= pages" @click="page++">下一页图片</el-button>
        </div>
      </template>
      <AssetUpload
        :services="services ?? uploadServices"
        purpose="PUBLIC_IMAGE"
        :disabled="loading"
        @ready="ready"
        @busy="uploadBusy"
      />
      <template #footer
        ><el-button :disabled="uploading" @click="cancel">取消</el-button
        ><el-button :disabled="uploading" type="primary" @click="cancel">完成</el-button></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.ls-asset-current {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.ls-asset-current > div {
  flex: 1;
  min-width: 120px;
  overflow-wrap: anywhere;
}
.ls-asset-current p {
  margin: 4px 0;
}
.ls-asset-current .ls-asset-thumbnail,
.ls-asset-current .ls-asset-placeholder {
  width: 80px;
  height: 64px;
  object-fit: cover;
}
.ls-image-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 16px 0;
}
</style>

<style>
.ls-image-dialog {
  max-width: calc(100vw - 32px);
}
.ls-image-dialog .ls-asset-grid {
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
}
.ls-image-dialog .ls-asset-grid li {
  min-width: 0;
  overflow-wrap: anywhere;
}
.ls-image-dialog .ls-asset-grid .el-button {
  height: auto;
  min-height: 32px;
  white-space: normal;
  max-width: 100%;
}
.ls-image-dialog summary,
.ls-secondary-details summary {
  cursor: pointer;
  color: #2855b7;
}
</style>
