<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{ activeTab?: string }>();

const currentRoute = computed(() => {
  if (props.activeTab) return props.activeTab;
  try {
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    return current ? `/${current.route}` : '/pages/home/index';
  } catch {
    return '/pages/home/index';
  }
});

const navList = [
  {
    name: '首页',
    path: '/pages/home/index',
    image: '/static/design/icons/house-neutral.png',
    activeImage: '/static/design/icons/house-blue.png',
  },
  {
    name: '学习',
    path: '/pages/learning/index',
    image: '/static/design/icons/book-open-neutral.png',
    activeImage: '/static/design/icons/book-open-blue.png',
  },
  {
    name: '订单',
    path: '/pages/order/list',
    image: '/static/design/icons/clipboard-list-neutral.png',
    activeImage: '/static/design/icons/clipboard-list-blue.png',
  },
  {
    name: '我的',
    path: '/pages/profile/index',
    image: '/static/design/icons/user-round-neutral.png',
    activeImage: '/static/design/icons/user-round-blue.png',
  },
];

function switchTab(path: string) {
  if (currentRoute.value === path) return;
  uni.reLaunch({ url: path });
}
</script>

<template>
  <view class="ui-tabbar-placeholder">
    <view class="ui-tabbar">
      <view
        v-for="item in navList"
        :key="item.path"
        class="ui-tabbar__item"
        :class="{ 'ui-tabbar__item--active': currentRoute.startsWith(item.path) }"
        @click="switchTab(item.path)"
      >
        <view class="ui-tabbar__icon-wrap">
          <image
            :src="currentRoute.startsWith(item.path) ? item.activeImage : item.image"
            class="ui-tabbar__icon"
            mode="aspectFit"
            aria-hidden="true"
          />
        </view>
        <text class="ui-tabbar__label">{{ item.name }}</text>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../styles/tokens.scss';

.ui-tabbar-placeholder {
  height: calc(108rpx + env(safe-area-inset-bottom));
  width: 100%;
}

.ui-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 999;
  height: calc(108rpx + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;

  border-top: 1rpx solid rgba(0, 0, 0, 0.06);
  box-shadow: $shadow-nav;
  display: flex;
  align-items: center;
  justify-content: space-around;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: $color-text-secondary;
    transition: $transition-fast;
    cursor: pointer;

    &:active {
      transform: scale(0.92);
    }

    &--active {
      color: $color-primary;

      .ui-tabbar__label {
        font-weight: 600;
        color: $color-primary;
      }

      .ui-tabbar__icon {
        transform: translateY(-2rpx);
      }
    }
  }

  &__icon-wrap {
    width: 48rpx;
    height: 48rpx;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__icon {
    width: 44rpx;
    height: 44rpx;
    transition: $transition-fast;
  }

  &__label {
    font-size: 24rpx;
    margin-top: 4rpx;
    line-height: 1;
    letter-spacing: 0.5rpx;
  }
}
</style>
