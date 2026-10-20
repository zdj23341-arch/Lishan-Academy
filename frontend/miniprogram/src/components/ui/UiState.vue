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
  <view
    class="ls-state"
    :class="{ 'ls-state--loading': kind === 'loading' }"
    :role="kind === 'error' ? 'alert' : 'status'"
    ><view v-if="kind === 'loading'" class="ls-state-loading">
      <view v-for="line in 4" :key="line" class="ls-state-skeleton" />
      <text class="ls-state-loading-label">{{ title }}</text>
    </view>
    <view v-else class="ls-state-body">
      <view class="ls-state-symbol"
        ><UiIcon
          :name="
            {
              empty: 'folder-open',
              error: 'wifi-off',
              denied: 'shield-check',
            }[kind]
          "
          tone="blue"
          :size="24" /></view
      ><text class="ls-state-title">{{ title }}</text
      ><text v-if="description" class="ls-state-description">{{ description }}</text
      ><UiButton v-if="actionLabel" class="retry-btn" kind="primary" @click="$emit('action')">{{
        actionLabel
      }}</UiButton></view
    ></view
  >
</template>
<style scoped lang="scss">
@import '../../styles/tokens.scss';

.ls-state {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 16px;
  border: 1px solid $color-border;
  border-radius: 13px;
  background: $color-bg-card;
  color: $color-text-sub;
  text-align: center;
}
.ls-state-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  min-height: 265px;
  padding: 65px 20px;
  gap: 12px;
}
.ls-state-symbol {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  background: $color-primary-light;
}
.ls-state-symbol :deep(.ls-icon) {
  width: 25px !important;
  height: 25px !important;
}
.ls-state-title {
  font-size: 15px;
  font-weight: 650;
  line-height: 1.55;
  color: $color-text-sub;
}
.ls-state-description {
  max-width: 310px;
  font-size: 12px;
  line-height: 1.55;
  color: $color-text-sub;
}
.ls-state-body :deep(.ls-button) {
  min-height: 44px;
  margin: 0;
  padding: 8px 14px;
  border: 1px solid $color-primary;
  border-radius: 8px;
  background: $color-primary;
  color: $color-text-white;
  font-size: 14px;
  line-height: 1.55;
}
.ls-state-loading {
  padding: 32px 10px;
  text-align: left;
}
.ls-state-skeleton {
  width: 100%;
  height: 13px;
  margin: 18px 0;
  border-radius: 4px;
  background: $color-border;
}
.ls-state-skeleton:nth-child(even) {
  width: 75%;
}
.ls-state-loading-label {
  display: block;
  font-size: 12px;
  line-height: 1.55;
}
</style>
