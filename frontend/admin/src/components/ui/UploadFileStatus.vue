<script setup lang="ts">
import UiStatusBadge from './UiStatusBadge.vue';
withDefaults(
  defineProps<{
    name: string;
    state: 'uploading' | 'processing' | 'ready' | 'failed' | 'disabled';
    reason?: string;
    busy?: boolean;
    canRetry?: boolean;
  }>(),
  { reason: '', busy: false, canRetry: false },
);
const emit = defineEmits<{ retry: [] }>();
const status = {
  uploading: { label: '正在上传', tone: 'blue' as const },
  processing: { label: '上传完成，处理中', tone: 'warning' as const },
  ready: { label: '可使用', tone: 'success' as const },
  failed: { label: '上传失败', tone: 'danger' as const },
  disabled: { label: '已停用', tone: 'neutral' as const },
};
</script>
<template>
  <div
    class="ls-upload-status"
    :class="`state-${state}`"
    :aria-busy="busy || state === 'uploading'"
  >
    <span class="ls-upload-file" :title="name">{{ name }}</span>
    <UiStatusBadge :label="status[state].label" :tone="status[state].tone" />
    <span v-if="state === 'failed' && reason" class="ls-upload-reason" role="alert">{{
      reason
    }}</span>
    <button
      v-if="state === 'failed' && canRetry"
      type="button"
      :disabled="busy"
      @click="emit('retry')"
    >
      重试上传
    </button>
  </div>
</template>
<style scoped>
.ls-upload-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid #e1e8f1;
  border-radius: 7px;
  background: #fff;
}
.ls-upload-file {
  min-width: 100px;
  flex: 1;
  overflow-wrap: anywhere;
  color: #24334b;
  font-size: 13px;
}
.ls-upload-reason {
  flex-basis: 100%;
  color: #b5473a;
  font-size: 13px;
}
.ls-upload-status button {
  min-height: 36px;
  padding: 5px 10px;
  color: #2855b7;
  background: #fff;
  border: 1px solid #cbd8eb;
  border-radius: 5px;
  cursor: pointer;
}
.ls-upload-status button:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
.ls-upload-status button:disabled {
  color: #8793a4;
  cursor: not-allowed;
}
.state-uploading {
  background: #f6f9ff;
}
.state-ready {
  border-color: #b8dfca;
  background: #f7fcf8;
}
.state-failed {
  border-color: #efc7c1;
  background: #fff9f7;
}
</style>
