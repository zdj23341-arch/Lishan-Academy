<script setup lang="ts">
defineProps<{ title: string; description?: string }>();
</script>
<template>
  <div class="ls-page-header">
    <div>
      <span class="ls-page-heading">{{ title }}</span
      ><span v-if="description" class="ls-page-description">{{ description }}</span>
    </div>
    <slot />
  </div>
</template>
