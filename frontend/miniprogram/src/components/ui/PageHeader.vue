<script setup lang="ts">
defineProps<{ title: string; description?: string }>();
</script>
<template>
  <view class="ls-page-header"
    ><view
      ><text class="ls-page-heading">{{ title }}</text
      ><text v-if="description" class="ls-page-description">{{ description }}</text></view
    ><slot
  /></view>
</template>
