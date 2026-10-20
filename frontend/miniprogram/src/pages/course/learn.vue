<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import { chapterContentUnavailable, formatShanghaiDateTime } from '@jxsc/shared';
import UiIcon from '../../components/ui/UiIcon.vue';
import UiState from '../../components/ui/UiState.vue';
import { computed } from 'vue';
import { useCourse } from '../../composables/useCourse';

const {
  state,
  routeError,
  mediaError,
  fullscreen,
  media,
  load,
  select,
  play,
  retryProgress,
  playbackRates,
  playbackRate,
  playbackRateError,
  setPlaybackRate,
  playbackRateMenuOpen,
  togglePlaybackRateMenu,
  closePlaybackRateMenu,
} = useCourse();
const playbackRateRows = computed(() =>
  fullscreen.value
    ? playbackRates.map((rate) => [rate])
    : [playbackRates.slice(0, 2), playbackRates.slice(2)],
);
const course = computed(() => state.value?.course);
const chapters = computed(() => course.value?.chapters ?? []);
const currentIndex = computed(() =>
  chapters.value.findIndex((ch) => ch.id === state.value?.selectedChapterId),
);
const selected = computed(() => chapters.value[currentIndex.value]);
const entitlementLabel = computed(() =>
  course.value?.entitlement === 'ACTIVE'
    ? '已购正版权益'
    : course.value?.entitlement === 'EXPIRED'
      ? '课程权益已过期'
      : '尚未购买 · 可查看试看章节',
);
const validityLabel = computed(() =>
  course.value?.expiresAt
    ? `观看有效期至 ${formatShanghaiDateTime(course.value.expiresAt)}`
    : course.value?.entitlement === 'ACTIVE'
      ? '观看权益无固定截止时间'
      : '请以课程授权结果为准',
);
function duration(seconds: number) {
  const value = Math.max(0, Math.floor(seconds));
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
}
function adjacent(offset: number) {
  const chapter = chapters.value[currentIndex.value + offset];
  if (chapter) void select(chapter.id);
}
function buy() {
  if (course.value)
    uni.navigateTo({
      url: `/pages/product/detail?id=${encodeURIComponent(course.value.productId)}`,
    });
}
</script>

<template>
  <view class="ls-mini-page course-learn-page ui-page-container">
    <UiState v-if="routeError" kind="error" :title="routeError" />
    <UiState v-if="state?.loading" kind="loading" title="正在加载课程…" />
    <UiState
      v-if="state?.error || (state && !course && !state.loading)"
      kind="error"
      :title="customerMessage(state?.error, '课程加载失败，请重试')"
      action-label="重试加载"
      @action="load"
    />
    <view v-if="mediaError" role="alert" class="ls-alert">{{ mediaError }}</view>
    <view v-if="state?.progressError" role="alert" class="ls-alert">{{
      customerMessage(state.progressError, '学习进度保存失败，请重试保存')
    }}</view>
    <button v-if="state?.progressError" @click="retryProgress">重试保存进度</button>

    <view v-if="course" class="video-player-container">
      <template v-if="media"
        ><template v-for="binding in [media]" :key="binding.key">
          <video
            :id="binding.videoId"
            class="video-placeholder"
            :src="binding.authorization.url"
            :initial-time="binding.authorization.resumeSeconds"
            :autoplay="true"
            :controls="true"
            :show-fullscreen-btn="true"
            @loadedmetadata="binding.ready"
            @play="binding.ready"
            @timeupdate="binding.timeUpdate"
            @pause="binding.pause"
            @ended="binding.ended"
            @error="binding.error"
            @fullscreenchange="binding.fullscreenChange"
          >
            <cover-view
              v-if="!playbackRateMenuOpen"
              class="playback-rate-trigger"
              hover-class="playback-rate-hover"
              role="button"
              :aria-label="`选择播放速度，当前 ${playbackRate} 倍`"
              :aria-expanded="playbackRateMenuOpen"
              @click.stop="togglePlaybackRateMenu"
              >倍速 · {{ playbackRate }}x</cover-view
            >
            <cover-view
              v-if="playbackRateMenuOpen"
              class="playback-rate-dismiss"
              role="button"
              aria-label="关闭倍速菜单"
              @click.stop="closePlaybackRateMenu"
            />
            <cover-view
              v-if="playbackRateMenuOpen"
              class="playback-rate-menu"
              :class="{ 'playback-rate-menu--fullscreen': fullscreen }"
              role="group"
              aria-label="播放速度"
              @click.stop
            >
              <cover-view
                v-for="(row, index) in playbackRateRows"
                :key="index"
                class="playback-rate-row"
              >
                <cover-view
                  v-for="rate in row"
                  :key="rate"
                  class="playback-rate-button"
                  hover-class="playback-rate-hover"
                  :class="{ 'playback-rate-button--active': playbackRate === rate }"
                  role="button"
                  :aria-label="`播放速度 ${rate} 倍`"
                  :aria-pressed="playbackRate === rate"
                  @click.stop="setPlaybackRate(rate)"
                  >{{ rate }}x</cover-view
                >
              </cover-view>
            </cover-view>
          </video>
        </template></template
      >
      <view v-else class="video-placeholder">
        <button
          class="play-btn-circle"
          aria-label="播放当前章节"
          :disabled="!selected || state?.loading || state?.authorizing"
          @click="play"
        >
          <view class="play-triangle" aria-hidden="true" />
        </button>
        <text class="play-tip">{{
          state?.authorizing
            ? '正在获取播放授权…'
            : selected
              ? `待播放：${selected.title}`
              : '请选择课程章节'
        }}</text>
      </view>
      <view v-if="course && !playbackRateMenuOpen" class="validity-tag">
        <text class="tag-txt">{{ validityLabel }}</text>
      </view>
    </view>

    <view v-if="course" class="course-header-card ui-card">
      <text class="course-title">{{ course.title }}</text>
      <view class="course-badges">
        <view class="ui-tag ui-tag--primary">{{ entitlementLabel }}</view>
        <view class="ui-tag ui-tag--accent">共 {{ chapters.length }} 讲</view>
      </view>
      <text v-if="media && fullscreen">全屏播放</text>
      <text v-if="playbackRateError" role="alert" class="playback-rate-error">{{
        playbackRateError
      }}</text>
      <view class="chapter-controls">
        <button
          class="ui-btn ui-btn--primary"
          :disabled="!selected || state?.loading || state?.authorizing"
          @click="play"
        >
          播放当前章节
        </button>
        <button
          class="ui-btn ui-btn--outline"
          :disabled="currentIndex <= 0 || state?.loading"
          @click="adjacent(-1)"
        >
          上一讲
        </button>
        <button
          class="ui-btn ui-btn--outline"
          :disabled="currentIndex < 0 || currentIndex >= chapters.length - 1 || state?.loading"
          @click="adjacent(1)"
        >
          下一讲
        </button>
        <button class="ui-btn ui-btn--primary" v-if="course.entitlement !== 'ACTIVE'" @click="buy">
          {{ course.entitlement === 'EXPIRED' ? '重新购买' : '购买课程' }}
        </button>
      </view>
    </view>

    <view v-if="course" class="chapters-card ui-card">
      <view class="card-header">
        <view class="accent-line" />
        <text class="header-title">课程章节目录</text>
      </view>
      <view v-if="!chapters.length">暂无章节</view>
      <view class="chapters-list">
        <view
          v-for="(ch, index) in chapters"
          :key="ch.id"
          class="chapter-item"
          :class="{
            'chapter-item--active': state?.selectedChapterId === ch.id,
            'chapter-item--locked': !ch.canPlay && !chapterContentUnavailable(course, ch),
          }"
          @click="select(ch.id)"
        >
          <view class="item-left">
            <view v-if="state?.selectedChapterId === ch.id" class="playing-icon">
              <view class="wave-bar" />
              <view class="wave-bar wave-bar--2" />
              <view class="wave-bar" />
            </view>
            <text v-else class="ch-index">{{ String(index + 1).padStart(2, '0') }}</text>
          </view>
          <view class="item-body">
            <text class="ch-title text-ellipsis-2">{{ ch.title }}</text>
            <view class="ch-meta">
              <UiIcon name="clock" :size="12" /><text class="ch-duration">{{
                duration(ch.durationSeconds)
              }}</text>
              <view v-if="ch.isTrial" class="ui-tag ui-tag--accent mini-tag">试看</view>
              <text v-if="chapterContentUnavailable(course, ch)" class="ch-duration"
                >暂不可播放</text
              >
              <view v-if="ch.completed" class="completed-tag"> 已学完</view>
            </view>
          </view>
          <view class="item-right">
            <view v-if="!ch.canPlay && !chapterContentUnavailable(course, ch)" class="locked-box"
              ><UiIcon name="lock-keyhole" :size="16"
            /></view>
            <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.playback-rate-trigger {
  position: absolute;
  right: 12px;
  bottom: 48px;
  z-index: 2;
  width: 88px;
  height: 44px;
  line-height: 44px;
  border-radius: $radius-sm;
  background-color: rgba(0, 0, 0, 0.75);
  color: $color-text-white;
  font-size: 14px;
  text-align: center;
}
.playback-rate-dismiss {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 2;
}
.playback-rate-hover {
  opacity: 0.75;
}
.playback-rate-menu {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 3;
  width: 176px;
  padding: 8px;
  border-radius: $radius-sm;
  background-color: rgba(0, 0, 0, 0.85);
  &--fullscreen {
    top: auto;
    bottom: 48px;
    right: 12px;
    width: 88px;
  }
}
.playback-rate-row {
  display: flex;
  margin-bottom: 8px;
  &:last-child {
    margin-bottom: 0;
  }
}
.playback-rate-button {
  flex: 1;
  min-width: 44px;
  height: 44px;
  line-height: 44px;
  text-align: center;
  border-radius: $radius-sm;
  color: $color-text-white;
  font-size: 14px;
  &:not(:last-child) {
    margin-right: 8px;
  }
  &--active {
    background-color: $color-primary;
    color: $color-text-white;
    font-weight: 700;
  }
}
.playback-rate-error {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: $color-price;
}
.chapter-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 24rpx;
}
.course-learn-page {
  padding: 32rpx;
}
.chapter-controls .ui-btn {
  flex: 1;
  min-width: 140rpx;
  padding: 16rpx;
  font-size: 26rpx;
}

.video-player-container {
  position: relative;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  max-height: 70vh;
  border-radius: $radius-md;
  overflow: hidden;
  background: #111827;

  .video-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16rpx;
    background: radial-gradient(circle, #1f2937 0%, #111827 100%);

    .play-btn-circle {
      padding: 0;
      margin: 0;
      line-height: 1;
      box-sizing: border-box;
      width: 90rpx;
      height: 90rpx;
      border-radius: 50%;
      background: $color-primary;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 8rpx 24rpx rgba(40, 85, 183, 0.4);

      &::after {
        border: 0;
      }
      .play-triangle {
        width: 0;
        height: 0;
        border-top: 18rpx solid transparent;
        border-bottom: 18rpx solid transparent;
        border-left: 28rpx solid #ffffff;
        margin-left: 6rpx;
      }
    }

    .play-tip {
      font-size: 24rpx;
      color: rgba(255, 255, 255, 0.85);
      max-width: 600rpx;
      text-align: center;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
  }

  .validity-tag {
    position: absolute;
    top: 16rpx;
    right: 20rpx;
    background: rgba(0, 0, 0, 0.65);
    padding: 6rpx 16rpx;
    border-radius: $radius-full;

    .tag-txt {
      font-size: 24rpx;
      color: #fbbf24;
      font-weight: 500;
    }
  }
}

.course-header-card {
  margin: 24rpx 0;
  padding: 24rpx;

  .course-title {
    font-size: 32rpx;
    font-weight: 700;
    color: $color-text-main;
    line-height: 1.4;
    display: block;
    margin-bottom: 16rpx;
  }

  .course-badges {
    display: flex;
    flex-wrap: wrap;
    gap: 12rpx;
  }
}

.chapters-card {
  margin: 0 0 24rpx;
  padding: 24rpx;

  .card-header {
    display: flex;
    align-items: center;
    gap: 12rpx;
    padding-bottom: 20rpx;
    border-bottom: 1rpx solid $color-border-light;
    margin-bottom: 12rpx;

    .accent-line {
      width: 6rpx;
      height: 28rpx;
      background: $color-primary;
      border-radius: 3rpx;
    }

    .header-title {
      font-size: 30rpx;
      font-weight: 700;
      color: $color-text-main;
    }
  }
}

.chapters-list {
  display: flex;
  flex-direction: column;
}

.chapter-item {
  display: flex;
  align-items: center;
  padding: 24rpx 12rpx;
  border-bottom: 1rpx solid $color-border-light;
  cursor: pointer;
  transition: $transition-fast;

  &:last-child {
    border-bottom: none;
  }

  &:active {
    background-color: #f8fafd;
  }

  &--active {
    background: #f0f6ff;
    border-radius: $radius-sm;

    .ch-title {
      color: $color-primary !important;
      font-weight: 700 !important;
    }
  }

  &--locked {
    opacity: 0.6;
  }

  .item-left {
    width: 60rpx;
    display: flex;
    align-items: center;
    justify-content: center;

    .ch-index {
      font-size: 28rpx;
      font-weight: 700;
      color: $color-text-muted;
    }

    .playing-icon {
      display: flex;
      align-items: flex-end;
      gap: 4rpx;
      height: 28rpx;

      .wave-bar {
        width: 4rpx;
        height: 24rpx;
        background: $color-primary;
        border-radius: 2rpx;
        animation: wave 0.8s ease infinite alternate;

        &--2 {
          height: 14rpx;
          animation-delay: 0.4s;
        }
      }
    }
  }

  .item-body {
    flex: 1;
    min-width: 0;
    margin: 0 16rpx;
    display: flex;
    flex-direction: column;
    gap: 8rpx;

    .ch-title {
      font-size: 28rpx;
      color: $color-text-main;
      line-height: 1.35;
      font-weight: 500;
    }

    .ch-meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16rpx;

      .ch-duration {
        font-size: 24rpx;
        color: $color-text-secondary;
      }

      .mini-tag {
        font-size: 24rpx;
        padding: 2rpx 8rpx;
      }

      .completed-tag {
        font-size: 24rpx;
        color: $color-accent;
        font-weight: 500;
      }
    }
  }

  .item-right {
    color: $color-text-muted;
    .svg-arrow {
      width: 32rpx;
      height: 32rpx;
    }
  }
}

@keyframes wave {
  from {
    height: 8rpx;
  }
  to {
    height: 26rpx;
  }
}
</style>
