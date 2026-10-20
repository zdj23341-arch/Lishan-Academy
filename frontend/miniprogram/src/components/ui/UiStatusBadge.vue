<script setup lang="ts">
withDefaults(
  defineProps<{ label: string; tone?: 'neutral' | 'blue' | 'success' | 'warning' | 'danger' }>(),
  { tone: 'neutral' },
);
</script>
<template>
  <text class="ls-badge" :class="'ls-badge--' + tone">{{ label }}</text>
</template>
