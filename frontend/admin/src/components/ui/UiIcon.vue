<script setup lang="ts">
import { computed } from 'vue';
const props = withDefaults(
  defineProps<{
    name: string;
    tone?: 'neutral' | 'blue' | 'success' | 'warning' | 'danger';
    size?: number;
  }>(),
  { tone: 'neutral', size: 20 },
);
const aliases: Record<string, string> = {
  dashboard: 'layout-dashboard',
  home: 'house',
  goods: 'package',
  category: 'layers',
  article: 'file-text',
  folder: 'folder-open',
  paper: 'file-text',
  vip: 'crown',
  record: 'users',
  order: 'clipboard-list',
  inventory: 'warehouse',
  service: 'headset',
  team: 'users',
  money: 'coins',
  chart: 'chart-column',
  setting: 'settings',
  audit: 'shield-check',
  export: 'download',
};
const names = new Set([
  'arrow-left',
  'arrow-right',
  'bell',
  'book-open',
  'calendar',
  'chart-column',
  'check',
  'chevron-left',
  'chevron-right',
  'circle-alert',
  'circle-check',
  'circle-help',
  'clipboard-list',
  'clock',
  'coins',
  'credit-card',
  'crown',
  'download',
  'file-text',
  'folder-open',
  'graduation-cap',
  'grip-vertical',
  'headset',
  'house',
  'image',
  'image-plus',
  'inbox',
  'info',
  'layers',
  'layout-dashboard',
  'library',
  'loader-circle',
  'lock-keyhole',
  'log-out',
  'map-pin',
  'menu',
  'messages-square',
  'notebook-pen',
  'package',
  'pause',
  'play',
  'plus',
  'refresh-cw',
  'search',
  'settings',
  'share-2',
  'shield-check',
  'truck',
  'upload',
  'user-round',
  'users',
  'video',
  'wallet',
  'warehouse',
  'x',
]);
const name = computed(() => {
  const value = aliases[props.name] || props.name;
  return names.has(value) ? value : 'circle-help';
});
const assets = import.meta.glob('../../assets/design/icons/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
}) as Record<string, string>;
const src = computed(() => assets[`../../assets/design/icons/${name.value}-${props.tone}.svg`]);
</script>
<template>
  <img
    :src="src"
    class="ls-icon"
    :style="{ width: size * 1 + 'px', height: size * 1 + 'px' }"
    alt=""
    aria-hidden="true"
  />
</template>
<style scoped>
.ls-icon {
  display: inline-block;
  flex-shrink: 0;
  vertical-align: middle;
}
</style>
