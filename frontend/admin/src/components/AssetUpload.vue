<script setup lang="ts">
import { computed, onScopeDispose, shallowRef } from 'vue';
import { ElButton } from 'element-plus';
import UiIcon from './ui/UiIcon.vue';
import { createUploadController, type UploadServices } from '@jxsc/shared/content/upload';
import type { AdminAsset, AssetPurpose } from '@jxsc/shared/content/admin-types';

const props = defineProps<{
  services: UploadServices;
  purpose: AssetPurpose;
  disabled?: boolean;
}>();
const emit = defineEmits<{ ready: [asset: AdminAsset]; busy: [value: boolean] }>();
const controller = createUploadController(props.services);
const state = shallowRef({ ...controller.state });
onScopeDispose(
  controller.subscribe((value) => {
    state.value = value;
    emit('busy', ['AUTHORIZING', 'UPLOADING', 'FINALIZING'].includes(value.phase));
  }),
);
const busy = computed(() => ['AUTHORIZING', 'UPLOADING', 'FINALIZING'].includes(state.value.phase));
const accept = computed(() =>
  props.purpose === 'COURSE_VIDEO'
    ? '.mp4'
    : props.purpose === 'PAPER_ATTACHMENT'
      ? '.pdf,.doc,.docx,.jpg,.jpeg,.png,.webp'
      : '.jpg,.jpeg,.png,.webp',
);
const labels = {
  IDLE: '请选择文件',
  AUTHORIZING: '正在申请上传凭证',
  UPLOADING: '正在传输文件',
  FINALIZING: '正在检查文件',
  PROCESSING: '上传完成，文件处理中',
  READY: '文件已就绪',
  FAILED: '上传失败',
  DISABLED: '文件已停用',
};
async function select(event: Event) {
  const input = event.target as HTMLInputElement;
  const selected = input.files?.[0];
  input.value = '';
  if (!selected || busy.value || props.disabled) return;
  try {
    emit(
      'ready',
      await controller.start({
        name: selected.name,
        mime: selected.type,
        bytes: selected.size,
        purpose: props.purpose,
        source: selected,
      }),
    );
  } catch {
    /* The controller exposes the error and retains the file for retry. */
  }
}
async function retry() {
  if (busy.value || props.disabled) return;
  try {
    emit('ready', await controller.retry());
  } catch {
    /* Visible state includes the failure. */
  }
}
</script>

<template>
  <fieldset class="asset-upload" :disabled="disabled || busy" :aria-busy="busy">
    <legend>上传文件</legend>
    <label class="upload-select">
      <UiIcon name="upload" tone="blue" :size="24" />
      <span>{{ busy ? labels[state.phase] : '点击选择文件上传' }}</span>
      <input type="file" aria-label="选择上传文件" :accept="accept" @change="select" />
    </label>
    <p v-if="purpose === 'PUBLIC_IMAGE'">支持 JPG、PNG、WebP，图片不超过 10 MiB。</p>
    <p v-else-if="purpose === 'COURSE_VIDEO'">支持 MP4，课程视频不超过 2 GiB。</p>
    <p v-else>支持 PDF、Word 与图片，文档不超过 50 MiB，图片不超过 10 MiB。</p>
    <p role="status">{{ state.fileName }} {{ labels[state.phase] }}</p>
    <p v-if="state.error" role="alert">{{ state.error.message }}</p>
    <el-button
      v-if="state.phase === 'FAILED' || state.phase === 'PROCESSING'"
      :disabled="disabled || busy"
      @click="retry"
      >{{ state.phase === 'PROCESSING' ? '继续处理' : '重试上传' }}</el-button
    >
    <p v-if="state.asset">已就绪：{{ state.asset.name }}（{{ state.asset.id }}）</p>
  </fieldset>
</template>

<style lang="scss" scoped>
.asset-upload {
  border-style: dashed;
  background: #f8faff;
}
.asset-upload .upload-select {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  min-height: 92px;
  margin: 0;
  color: #2855b7;
  cursor: pointer;
}
.upload-select:focus-within {
  outline: 2px solid #2855b7;
  outline-offset: 3px;
  border-radius: 8px;
}
.asset-upload input[type='file'] {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.asset-upload input[type='file']:disabled {
  cursor: wait;
}
.asset-upload p {
  margin: 12px 0 0;
  color: #617088;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.asset-upload p[role='alert'] {
  color: #c04436;
}
</style>
