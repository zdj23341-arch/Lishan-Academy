<script setup lang="ts">
withDefaults(
  defineProps<{ label: string; tone?: 'neutral' | 'blue' | 'success' | 'warning' | 'danger' }>(),
  { tone: 'neutral' },
);
</script>
<template>
  <span class="ls-badge" :class="'ls-badge--' + tone">{{ label }}</span>
</template>
