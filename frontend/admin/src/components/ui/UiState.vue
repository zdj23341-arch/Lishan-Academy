<script setup lang="ts">
import UiIcon from './UiIcon.vue';
import UiButton from './UiButton.vue';
defineProps<{
  kind: 'loading' | 'empty' | 'error' | 'denied';
  title: string;
  description?: string;
  actionLabel?: string;
}>();
defineEmits<{ action: [] }>();
</script>
<template>
  <div class="ls-state" :role="kind === 'error' ? 'alert' : 'status'">
    <div class="ls-state-symbol">
      <UiIcon
        :name="
          {
            loading: 'loader-circle',
            empty: 'inbox',
            error: 'circle-alert',
            denied: 'lock-keyhole',
          }[kind]
        "
        tone="blue"
        :size="28"
      />
    </div>
    <span class="ls-state-title">{{ title }}</span
    ><span v-if="description" class="ls-state-description">{{ description }}</span
    ><UiButton v-if="actionLabel" kind="secondary" @click="$emit('action')">{{
      actionLabel
    }}</UiButton>
  </div>
</template>
