<script setup lang="ts">
import { customerMessage } from '../utils/customer-text';
import UiState from './ui/UiState.vue';
withDefaults(
  defineProps<{
    loading: boolean;
    error?: string;
    empty?: boolean;
    emptyText?: string;
    loadingText?: string;
  }>(),
  {
    error: '',
    empty: false,
    emptyText: '暂无相关内容，请稍后再来或调整筛选条件',
    loadingText: '内容加载中…',
  },
);

defineEmits<{ retry: [] }>();
</script>

<template>
  <UiState v-if="loading" kind="loading" :title="loadingText" /><UiState
    v-else-if="error"
    kind="error"
    title="加载遇到问题"
    :description="customerMessage(error, '加载失败，请稍后重试')"
    action-label="重新加载"
    @action="$emit('retry')"
  /><UiState v-else-if="empty" kind="empty" title="暂无内容" :description="emptyText" />
</template>
