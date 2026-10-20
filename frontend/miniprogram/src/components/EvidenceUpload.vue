<script setup lang="ts">
import { customerMessage } from '../utils/customer-text';
import UiIcon from './ui/UiIcon.vue';
import { onBeforeUnmount, ref } from 'vue';
import { chooseEvidence, createEvidenceUpload, evidenceServices } from '../api/evidence';
import { session } from '../api/session';

const props = withDefaults(
  defineProps<{ disabled?: boolean; maxCount?: number; count?: number }>(),
  { disabled: false, maxCount: 10, count: 0 },
);
const emit = defineEmits<{
  ready: [assetId: string];
  busy: [value: boolean];
  error: [message: string];
}>();
const busy = ref(false);
const preview = ref('');
const error = ref('');
const phase = ref('');
const retryable = ref(false);
let disposed = false;
let epoch = 0;
let upload: ReturnType<typeof createEvidenceUpload> | null = null;
function setBusy(value: boolean) {
  busy.value = value;
  emit('busy', value);
}
function clear() {
  epoch++;
  upload?.dispose();
  upload = null;
  preview.value = '';
  error.value = '';
  phase.value = '';
  retryable.value = false;
  setBusy(false);
}
const stopSession = session.subscribe(clear);
onBeforeUnmount(() => {
  disposed = true;
  stopSession();
  clear();
});
async function run(retry = false) {
  if (busy.value || props.disabled || props.count >= props.maxCount) return;
  const login = session.get();
  if (!login) {
    error.value = '请登录后上传凭证';
    emit('error', error.value);
    return;
  }
  const generation = session.getGeneration();
  const turn = epoch;
  const current = () =>
    !disposed && turn === epoch && !!session.get() && session.getGeneration() === generation;
  setBusy(true);
  error.value = '';
  retryable.value = false;
  try {
    if (!upload) {
      const services = await evidenceServices(session);
      if (!current()) return;
      upload = createEvidenceUpload(session, services);
      upload.subscribe((state) => {
        if (!current()) return;
        phase.value = {
          IDLE: '',
          AUTHORIZING: '正在获取上传凭证',
          UPLOADING: '正在上传图片',
          FINALIZING: '正在检查图片',
          READY: '上传完成',
          FAILED: '',
        }[state.phase];
      });
    }
    let result;
    if (retry) result = await upload.retry();
    else {
      const selected = await chooseEvidence(current);
      if (!current()) return;
      preview.value = selected.preview;
      result = await upload.start(selected.file);
    }
    if (!current()) return;
    if (result.status === 'READY') {
      emit('ready', result.id);
      upload.dispose();
      upload = null;
      preview.value = '';
      retryable.value = false;
    }
  } catch (cause) {
    if (!current()) return;
    const message =
      cause instanceof Error
        ? cause.message
        : ((cause as { errMsg?: string })?.errMsg ?? '上传失败，请重试');
    if (!message.includes('cancel')) {
      error.value = customerMessage(cause, '上传失败，请重试');
      emit('error', error.value);
    }
    retryable.value = !!preview.value;
  } finally {
    if (current()) setBusy(false);
  }
}
</script>

<template>
  <view class="evidence-upload">
    <button
      class="evidence-picker"
      :disabled="disabled || busy || count >= maxCount"
      @click="run()"
    >
      <UiIcon name="image-plus" :size="24" />
      <text>添加图片凭证</text>
      <text class="evidence-hint">已上传 {{ count }}/{{ maxCount }} 张，上传后可移除</text>
    </button>
    <text class="evidence-hint">支持 PNG、JPEG、WEBP 图片，单张不超过 10 MB</text>
    <image v-if="preview" class="evidence-preview" :src="preview" mode="aspectFit" />
    <text v-if="phase" role="status" class="ls-status">{{ phase }}</text>
    <text v-if="error" role="alert" class="ls-alert">{{ error }}</text>
    <button v-if="retryable" :disabled="disabled || busy || count >= maxCount" @click="run(true)">
      重试上传
    </button>
  </view>
</template>
<style scoped>
.evidence-upload {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}
.evidence-picker {
  width: 100%;
  min-height: 220rpx;
  margin: 0;
  padding: 28rpx 20rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14rpx;
  border: 2rpx dashed #ced7e4;
  border-radius: 16rpx;
  background: #f8fafd;
  color: #617088;
  font-size: 26rpx;
  line-height: 1.5;
}
.evidence-picker::after {
  border: 0;
}
.evidence-hint {
  font-size: 22rpx;
  color: #617088;
  line-height: 1.6;
}
.evidence-preview {
  width: 160rpx;
  height: 160rpx;
}
</style>
