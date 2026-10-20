<script setup lang="ts">
import { computed } from 'vue';
import type { MembershipView, PaperDetail } from '@jxsc/shared';

const props = defineProps<{
  file: PaperDetail['attachments'][number];
  busy: boolean;
  membershipState?: MembershipView['state'];
}>();
const emit = defineEmits<{ preview: []; download: [] }>();
const extension = computed(() => props.file.extension.toUpperCase());
const badge = computed(() => {
  if (['DOC', 'DOCX'].includes(extension.value))
    return { label: 'WORD', bg: '#EBF3FE', color: '#2F80ED' };
  if (extension.value === 'PDF') return { label: 'PDF', bg: '#FFF0ED', color: '#FF5024' };
  if (['PNG', 'JPG', 'JPEG', 'WEBP'].includes(extension.value))
    return { label: 'IMG', bg: '#EAF8EE', color: '#2DBE5C' };
  return { label: 'FILE', bg: '#F3F4F6', color: '#6B7280' };
});
const size = computed(() => {
  const bytes = props.file.bytes;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
});
const unavailableLabel = computed(() =>
  props.membershipState === 'NONE'
    ? '需开通会员'
    : props.membershipState === 'EXPIRED'
      ? '需续费会员'
      : '暂不可用',
);
const permission = computed(() =>
  [
    props.file.canPreview ? '可预览' : `预览${unavailableLabel.value}`,
    props.file.canDownload ? '可下载' : `下载${unavailableLabel.value}`,
  ].join(' · '),
);
function preview() {
  if (!props.busy) emit('preview');
}
function download() {
  if (!props.busy) emit('download');
}
</script>

<template>
  <!-- Scoped content retains the page's existing DOM classes and scoped styles. -->
  <slot
    :badge="badge"
    :size="size"
    :extension="extension"
    :permission="permission"
    :preview="preview"
    :download="download"
  />
</template>
