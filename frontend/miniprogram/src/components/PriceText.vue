<script setup lang="ts">
import { computed } from 'vue';
import { formatFen } from '@jxsc/shared';

const props = withDefaults(
  defineProps<{
    fen: number;
    original?: number | null;
    size?: 'sm' | 'md' | 'lg' | 'hero' | 'card';
    showOriginal?: boolean;
    mode?: 'price' | 'amount';
  }>(),
  {
    original: null,
    size: 'md',
    showOriginal: true,
    mode: 'price',
  },
);

const isFree = computed(() => props.mode === 'price' && props.fen === 0);

const formatted = computed(() => {
  const str = formatFen(props.fen);
  const parts = str.split('.');
  return {
    int: parts[0] || '0',
    dec: parts[1] ? `.${parts[1]}` : '',
  };
});
</script>

<template>
  <view class="ui-price" :class="[`ui-price--${size}`, { 'ui-price--free': isFree }]">
    <template v-if="isFree">
      <text class="ui-price__free">免费</text>
    </template>
    <template v-else>
      <text class="ui-price__symbol">¥</text>
      <text class="ui-price__int">{{ formatted.int }}</text>
      <text v-if="formatted.dec" class="ui-price__dec">{{ formatted.dec }}</text>
    </template>

    <text v-if="showOriginal && original != null && original > fen" class="ui-price__original">
      ¥{{ formatFen(original) }}
    </text>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.ui-price {
  display: inline-flex;
  align-items: baseline;
  color: $color-price;
  font-weight: 700;
  font-family: -apple-system, 'DIN Alternate', 'PingFang SC', sans-serif;

  &__free {
    color: $color-accent;
    font-size: $font-size-title;
    font-weight: 700;
  }

  &__symbol {
    font-size: $font-size-caption;
    margin-right: 2rpx;
    font-weight: 600;
  }

  &__int {
    font-size: $font-size-title;
    line-height: 1;
  }

  &__dec {
    font-size: $font-size-caption;
    line-height: 1;
  }

  &__original {
    margin-left: 12rpx;
    font-size: $font-size-caption;
    color: $color-text-secondary;
    font-weight: 400;
    text-decoration: line-through;
  }

  // 尺寸变体
  &--card {
    .ui-price__symbol,
    .ui-price__int,
    .ui-price__dec {
      font-size: 34rpx;
      line-height: 1.55;
    }
  }

  &--sm {
    .ui-price__symbol {
      font-size: 24rpx;
    }
    .ui-price__int {
      font-size: 26rpx;
    }
    .ui-price__dec {
      font-size: 24rpx;
    }
    .ui-price__original {
      font-size: 24rpx;
    }
  }

  &--md {
    .ui-price__symbol {
      font-size: 24rpx;
    }
    .ui-price__int {
      font-size: 32rpx;
    }
    .ui-price__dec {
      font-size: 24rpx;
    }
  }

  &--lg {
    .ui-price__symbol {
      font-size: 28rpx;
    }
    .ui-price__int {
      font-size: 40rpx;
    }
    .ui-price__dec {
      font-size: 28rpx;
    }
    .ui-price__original {
      font-size: 26rpx;
    }
  }

  &--hero {
    .ui-price__symbol {
      font-size: 32rpx;
    }
    .ui-price__int {
      font-size: 52rpx;
    }
    .ui-price__dec {
      font-size: 32rpx;
    }
    .ui-price__original {
      font-size: 28rpx;
    }
  }
}
</style>
