<script setup lang="ts">
import { displayDate } from '../../presentation/date';
import BookCover from '../../components/ui/BookCover.vue';
import { computed, onUnmounted, ref, shallowRef } from 'vue';
import { onHide, onLoad, onReachBottom, onShow, onUnload } from '@dcloudio/uni-app';
import { createPager, type CourseFilters, type CourseView } from '@jxsc/shared';
import { learningApi } from '../../api/learning';
import { session } from '../../api/session';
import BaseNav from '../../components/BaseNav.vue';
import UiState from '../../components/ui/UiState.vue';

type Status = NonNullable<CourseFilters['status']>;
const activeTab = ref<Status>('IN_PROGRESS');
const tabs: { label: string; value: Status }[] = [
  { label: '学习中', value: 'IN_PROGRESS' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已过期', value: 'EXPIRED' },
];
const loggedIn = ref(!!session.get());
const makePager = () =>
  createPager<CourseView, Status>((status, page) =>
    learningApi.learningCourses({ status, page, pageSize: 10 }),
  );
let pager = makePager();
let generation = 0;
let disposed = false;
let failedAction: 'refresh' | 'more' = 'refresh';
const state = shallowRef({ ...pager.state });
const courses = computed(() =>
  state.value.items.map((course) => {
    const id = course.id;
    const completed =
      course.completedChapters ?? course.chapters?.filter((ch) => ch.completed).length ?? 0;
    const total = course.totalChapters ?? course.chapters?.length ?? 0;
    const entitlement = course.status === 'EXPIRED' ? 'EXPIRED' : (course.entitlement ?? 'ACTIVE');
    return {
      ...course,
      id,
      courseId: id,
      completed,
      total,
      entitlement,
      chapters: course.chapters ?? [],
      percent: total ? Math.round((completed / total) * 100) : 0,
    };
  }),
);
function reset() {
  generation++;
  pager = makePager();
  state.value = { ...pager.state };
}
async function run(kind: 'refresh' | 'more') {
  loggedIn.value = !!session.get();
  if (disposed || !loggedIn.value) return;
  const current = pager;
  if (kind === 'more' && (current.state.loading || !current.state.hasMore || !current.state.page))
    return;
  const ticket = ++generation;
  failedAction = kind;
  const pending =
    kind === 'more'
      ? current.loadMore()
      : current.state.page
        ? current.refresh()
        : current.reset(activeTab.value);
  state.value = { ...current.state };
  await pending;
  if (!disposed && ticket === generation && current === pager) state.value = { ...current.state };
}
function changeTab(value: Status) {
  if (activeTab.value === value) return;
  activeTab.value = value;
  reset();
  void run('refresh');
}
function openCourse(id: string) {
  if (session.get())
    uni.navigateTo({
      url: `/pages/course/learn?id=${encodeURIComponent(id)}&productId=${encodeURIComponent(courses.value.find((item) => item.id === id)?.productId ?? '')}`,
    });
}
function browseCourses() {
  uni.navigateTo({ url: '/pages/product/list?type=COURSE' });
}
function login() {
  session.rememberReturnTo('/pages/learning/index');
  uni.navigateTo({ url: '/pages/login/index' });
}
const unsubscribe = session.subscribe((value) => {
  loggedIn.value = !!value;
  reset();
});
function hide() {
  // Detach pending reads while retaining the last successful display for this account.
  generation++;
  const prior = state.value;
  pager = makePager();
  Object.assign(pager.state, prior, { page: 0, loading: false, error: null });
  state.value = { ...pager.state };
}
function dispose() {
  if (!disposed) {
    disposed = true;
    reset();
    unsubscribe();
  }
}
onLoad((options) => {
  activeTab.value = tabs.find((tab) => tab.value === options?.status)?.value ?? 'IN_PROGRESS';
  reset();
});
onShow(() => {
  void run('refresh');
});
onReachBottom(() => {
  void run('more');
});
onHide(hide);
onUnload(dispose);
onUnmounted(dispose);
</script>

<template>
  <view class="ls-mini-page learning-page ui-page-container">
    <view class="learning-heading">
      <text>我的课程</text>
      <text v-if="loggedIn && state.page" class="course-count">共 {{ state.total }} 门</text>
    </view>
    <view v-if="tabs.length" class="learning-tabs">
      <button
        v-for="t in tabs"
        :key="t.value"
        class="tab-item"
        :class="{ 'tab-item--active': activeTab === t.value }"
        :aria-pressed="activeTab === t.value"
        hover-class="tab-item--pressed"
        @click="changeTab(t.value)"
      >
        <text class="tab-label">{{ t.label }}</text>
      </button>
    </view>
    <view class="courses-wrap">
      <UiState
        v-if="!loggedIn"
        kind="denied"
        title="登录后查看我的课程"
        action-label="去登录"
        @action="login"
      />
      <UiState v-if="state.loading" kind="loading" title="正在加载课程…" />
      <UiState
        v-if="state.error"
        kind="error"
        title="课程列表加载失败，请重试"
        action-label="重试加载"
        @action="run(failedAction)"
      />
      <view
        v-for="c in courses"
        :key="c.id"
        class="enrolled-card ui-card"
        @click="openCourse(c.id)"
      >
        <view class="card-top">
          <view class="course-cover-box">
            <BookCover :title="c.title" theme="jade" />
          </view>
          <view class="course-body">
            <text class="course-title text-ellipsis-2">{{ c.title }}</text>
            <text class="last-chapter text-ellipsis">{{
              c.entitlement === 'EXPIRED'
                ? '课程权益已过期'
                : c.entitlement === 'ACTIVE'
                  ? '课程权益有效'
                  : '暂无已购权益'
            }}</text>
            <text class="expire-tip">{{
              c.expiresAt ? `有效至 ${displayDate(c.expiresAt)}` : ''
            }}</text>
          </view>
        </view>
        <view class="progress-wrap">
          <view class="progress-bar-bg"
            ><view class="progress-bar-fill" :style="{ width: `${c.percent}%` }"
          /></view>
          <view class="card-bottom">
            <text class="progress-txt"
              >已完成 {{ c.percent }}% · {{ c.completed }}/{{ c.total }} 讲</text
            >
            <button class="ui-btn ui-btn--primary continue-btn" @click.stop="openCourse(c.id)">
              {{ c.entitlement === 'EXPIRED' ? '查看课程' : '继续学习' }}
            </button>
          </view>
        </view>
      </view>
      <button
        v-if="loggedIn && state.hasMore && state.page && !state.error"
        :disabled="state.loading"
        @click="run('more')"
      >
        加载更多
      </button>
      <UiState
        v-if="loggedIn && !state.loading && !state.error && !courses.length"
        kind="empty"
        title="当前分类暂无课程"
        action-label="去选课"
        @action="browseCourses"
      />
    </view>
    <BaseNav active-tab="/pages/learning/index" />
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.learning-page {
  background-color: $color-bg-page;
}
.learning-heading {
  display: flex;
  justify-content: space-between;
  gap: 20rpx;
  padding: 32rpx 32rpx 0;
  font-size: 32rpx;
  font-weight: 600;
}
.course-count {
  font-size: 24rpx;
  color: $color-text-sub;
  font-weight: 400;
}

.study-dashboard {
  margin: 24rpx;
  padding: 32rpx 28rpx;
  background: #edf3ff;
  color: #18283e;

  .dash-title {
    font-size: 26rpx;
    color: #617088;
    margin-bottom: 24rpx;
  }

  .dash-stats {
    display: flex;
    justify-content: space-around;

    .stat-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6rpx;

      .num {
        font-size: 44rpx;
        font-weight: 700;
        color: #18283e;
        font-family: -apple-system, 'DIN Alternate', sans-serif;

        .unit {
          font-size: 24rpx;
          margin-left: 4rpx;
        }
      }

      .desc {
        font-size: 24rpx;
        color: #617088;
      }
    }
  }
}

.learning-tabs {
  display: flex;
  gap: 8px;
  padding: 16px 16px 0;
}

.tab-item {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  margin: 0;
  text-align: center;
  padding: 8px 12px;
  border-radius: 6px;
  background: $color-bg-card;
  line-height: 1.5;
  cursor: pointer;

  &::after {
    border: 0;
  }

  .tab-label {
    font-size: 12px;
    color: $color-text-sub;
  }

  &--active {
    background: $color-primary;
    .tab-label {
      color: #ffffff;
      font-weight: 500;
    }
  }
  &--pressed {
    opacity: 0.8;
  }
}

.courses-wrap {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.enrolled-card {
  padding: 16px;
  margin-bottom: 0;
  border-radius: 13px;
  cursor: pointer;

  .card-top {
    display: flex;
    gap: 12px;
    margin-bottom: 26px;
  }

  .course-cover-box {
    width: 63px;
    height: 84px;
    border-radius: $radius-sm;
    background: #f0f3f7;
    overflow: hidden;
    flex-shrink: 0;

    .cover-img {
      width: 100%;
      height: 100%;
    }

    .cover-placeholder {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: $color-primary-light;
      color: $color-primary;
      font-size: 24rpx;
      font-weight: 600;
    }
  }

  .course-body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 8px;

    .course-title {
      font-size: 14px;
      font-weight: 600;
      color: $color-text-main;
      line-height: 1.35;
    }

    .last-chapter {
      font-size: 12px;
      color: $color-primary;
    }

    .expire-tip {
      font-size: 12px;
      color: $color-text-secondary;
    }
  }

  .card-bottom {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 26px;
    gap: 12px;

    .continue-btn {
      flex-shrink: 0;
      min-height: 44px;
      margin: 0;
      padding: 8px 14px;
      border: 1px solid $color-primary;
      border-radius: 8px;
      box-shadow: none;
      font-size: 14px;
      font-weight: 400;
      line-height: 1.55;
    }
  }
}

.progress-wrap {
  min-width: 0;
}
.progress-bar-bg {
  height: 5px;
  background: $color-border;
  border-radius: 5px;
  overflow: hidden;
}
.progress-bar-fill {
  height: 100%;
  background: $color-accent;
  border-radius: 5px;
}
.progress-txt {
  min-width: 0;
  font-size: 12px;
  color: $color-text-secondary;
}

.empty-wrap {
  padding: 80rpx 40rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;

  .empty-txt {
    font-size: 26rpx;
    color: $color-text-secondary;
  }

  .empty-btn {
    height: 72rpx;
    padding: 0 48rpx;
    font-size: 28rpx;
  }
}
.course-cover-box :deep(.ls-cover) {
  height: 100%;
}
.course-cover-box :deep(.ls-cover-title) {
  font-size: 32rpx;
}
.course-cover-box :deep(.ls-cover-caption) {
  display: none;
}
.course-cover-box :deep(.ls-cover-brand) {
  display: none;
}
.course-cover-box :deep(.ls-cover-art) {
  padding: 16rpx 18rpx 16rpx 26rpx;
}
.course-cover-box :deep(.ls-cover-title) {
  font-size: 28rpx;
  letter-spacing: 0;
}
</style>
