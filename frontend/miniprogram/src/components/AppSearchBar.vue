<script setup lang="ts">
import UiIcon from './ui/UiIcon.vue';
withDefaults(
  defineProps<{
    modelValue: string;
    placeholder?: string;
    error?: string;
    variant?: 'inline' | 'entry' | 'stacked';
    showSubmit?: boolean;
  }>(),
  { variant: 'inline', showSubmit: true },
);
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: []; clear: [] }>();

function onInput(event: Event) {
  emit('update:modelValue', (event as unknown as { detail: { value: string } }).detail.value);
}

function clear() {
  emit('update:modelValue', '');
  emit('clear');
}
</script>

<template>
  <view class="app-search-wrap" :class="'app-search-wrap--' + variant">
    <button v-if="variant === 'entry'" class="app-search-entry" @click="$emit('submit')">
      <UiIcon name="search" :size="18" />
      <text>{{ placeholder || '搜索课程、图书、语文试卷' }}</text>
    </button>
    <view v-else class="app-search-bar" :class="{ 'app-search-bar--error': !!error }">
      <!-- 搜索图标 -->
      <view class="app-search-bar__icon">
        <UiIcon name="search" class="svg-icon" :size="18" />
      </view>

      <!-- 输入框 -->
      <input
        class="app-search-bar__input"
        :value="modelValue"
        :placeholder="placeholder || '搜索课程、图书、试卷资料...'"
        placeholder-class="app-search-bar__placeholder"
        confirm-type="search"
        @input="onInput"
        @confirm="$emit('submit')"
      />

      <!-- 清空按钮 -->
      <view
        v-if="modelValue"
        class="app-search-bar__clear"
        role="button"
        aria-label="清空搜索内容"
        @click.stop="clear"
      >
        <text class="clear-icon">×</text>
      </view>

      <!-- 搜索动作按钮 -->
      <button
        v-if="variant === 'inline' && showSubmit"
        class="app-search-bar__btn"
        @click="$emit('submit')"
      >
        搜索
      </button>
    </view>
    <button
      v-if="variant === 'stacked'"
      class="app-search-bar__btn search-submit"
      @click="$emit('submit')"
    >
      搜索
    </button>

    <!-- 校验提示 -->
    <text v-if="error" class="app-search-bar__error-msg">{{ error }}</text>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.app-search-wrap {
  padding: 16rpx 32rpx;
  background-color: $color-bg-page;
}

.app-search-bar {
  display: flex;
  align-items: center;
  height: 88rpx;
  min-height: 44px;
  background-color: #fff;
  border-radius: 22rpx;
  padding: 0 8rpx 0 24rpx;
  border: 1rpx solid $color-border;
  transition: $transition-fast;

  &:focus-within {
    background-color: #ffffff;
    border-color: $color-primary;
    box-shadow: 0 0 0 4rpx rgba(40, 85, 183, 0.12);
  }

  &--error {
    border-color: $color-price;
  }

  &__icon {
    width: 36rpx;
    height: 36rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $color-text-secondary;
    margin-right: 12rpx;
    flex-shrink: 0;

    .svg-icon {
      width: 100%;
      height: 100%;
    }
  }

  &__input {
    flex: 1;
    min-width: 0;
    height: 100%;
    font-size: $font-size-sub;
    color: $color-text-main;
  }

  &__placeholder {
    color: $color-text-muted;
    font-size: $font-size-desc;
  }

  &__clear {
    position: relative;
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 12rpx;
    cursor: pointer;

    &::before {
      content: '';
      position: absolute;
      width: 44rpx;
      height: 44rpx;
      border-radius: 50%;
      background-color: rgba(0, 0, 0, 0.08);
    }

    .clear-icon {
      position: relative;
      font-size: 28rpx;
      line-height: 1;
      color: #666;
      margin-top: -2rpx;
    }
  }

  &__btn {
    height: 76rpx;
    min-height: 44px;
    padding: 0 28rpx;
    border-radius: 22rpx;
    background: #2855b7;
    color: #ffffff;
    font-size: 26rpx;
    font-weight: 600;
    line-height: 76rpx;
    border: none;
    outline: none;
    flex-shrink: 0;

    &::after {
      display: none;
    }

    &:active {
      opacity: 0.9;
      transform: scale(0.96);
    }
  }

  &__error-msg {
    display: block;
    padding: 8rpx 24rpx 0;
    color: $color-price;
    font-size: $font-size-caption;
  }
}
.app-search-entry {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 18rpx;
  width: 100%;
  height: 88rpx;
  min-height: 44px;
  padding: 0 28rpx;
  border: 1rpx solid $color-border;
  border-radius: 22rpx;
  background: white;
  color: $color-text-secondary;
  font-size: 26rpx;
  text-align: left;
  &::after {
    display: none;
  }
}
.search-submit {
  width: 100%;
  margin-top: 24rpx;
  height: 88rpx;
  min-height: 44px;
  line-height: 88rpx;
  border-radius: 16rpx;
}
</style>
