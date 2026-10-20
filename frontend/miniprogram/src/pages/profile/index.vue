<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import { formatShanghaiDateTime } from '@jxsc/shared';
import UiIcon from '../../components/ui/UiIcon.vue';
import { ref, onUnmounted } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useSessionStore } from '../../stores/session';
import BaseNav from '../../components/BaseNav.vue';
import { useMembership } from '../../composables/useMembership';
import { distributionApi } from '../../api/distribution';
import { session } from '../../api/session';

const { membership, stateLabel, actionLabel } = useMembership();

const store = useSessionStore();
const error = ref('');
const loading = ref(false);
const hasDistributionArchive = ref(false);
let archiveEpoch = 0;
const unsubscribe = session.subscribe(() => {
  archiveEpoch++;
  hasDistributionArchive.value = false;
});
async function refreshArchive() {
  const epoch = ++archiveEpoch;
  hasDistributionArchive.value = false;
  if (!session.get()) return;
  try {
    const summary = await distributionApi.getOptionalSummary();
    if (epoch === archiveEpoch) hasDistributionArchive.value = !!summary?.distributorId;
  } catch {
    // A missing archive does not prevent using the rest of the profile.
  }
}
onUnmounted(() => {
  archiveEpoch++;
  unsubscribe();
});

const orderBadges = [
  { label: '待付款', icon: 'wallet', tone: 'blue' as const, tab: 'PENDING_PAYMENT' },
  { label: '待发货', icon: 'package', tone: 'success' as const, tab: 'PAID' },
  { label: '待收货', icon: 'truck', tone: 'blue' as const, tab: 'SHIPPED' },
  { label: '售后', icon: 'rotate-ccw', tone: 'success' as const, tab: 'AFTERSALE' },
];

function login() {
  uni.navigateTo({ url: '/pages/login/index' });
}

async function logout() {
  if (loading.value) return;
  loading.value = true;
  try {
    await store.logout();
  } catch (cause) {
    error.value = customerMessage(cause, '退出失败，本机会话已清除');
  } finally {
    loading.value = false;
  }
}

function openOrders(tab?: string) {
  uni.navigateTo({ url: tab ? `/pages/order/list?tab=${tab}` : '/pages/order/list' });
}

function openMembership() {
  uni.navigateTo({ url: '/pages/membership/index' });
}

function openLearning() {
  uni.navigateTo({ url: '/pages/learning/index' });
}

function openAddress() {
  uni.navigateTo({ url: '/pages/address/list' });
}

function openDistribution() {
  uni.navigateTo({ url: '/pages/distribution/index' });
}
function openPapers() {
  uni.navigateTo({ url: '/pages/paper/list' });
}

function openDownloads() {
  uni.navigateTo({ url: '/pages/paper/downloads' });
}

function contactService() {
  uni.showModal({
    title: '联系客服',
    content: '如需帮助，请通过购买渠道联系力山学堂客服。',
    showCancel: false,
  });
}

onShow(() => {
  store.refresh();
  void refreshArchive();
});
</script>

<template>
  <view class="ls-mini-page profile-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert">{{ error }}</view>
    <!-- 用户头像信息背景头 -->
    <view class="user-hero-card">
      <view class="user-info-row">
        <view class="avatar-box">
          <text class="avatar-text">
            {{ (store.current?.user?.nickname || '客').slice(0, 1) }}
          </text>
        </view>

        <view class="user-meta">
          <text class="nickname">{{ store.current?.user?.nickname || '学习者' }}</text>
          <text class="user-sub">
            {{
              store.current?.user ? `账号编号：${store.current.user.id}` : '登录同步学习进度与订单'
            }}
          </text>
        </view>

        <view class="auth-action">
          <button v-if="!store.current" class="ui-btn ui-btn--primary auth-btn" @click="login">
            一键登录
          </button>
          <button v-else class="logout-btn" :disabled="loading" @click="logout">退出</button>
        </view>
      </view>
    </view>

    <!-- 会员黑金卡片横幅 -->
    <view class="vip-banner-card ui-card" @click="openMembership">
      <view class="vip-left">
        <view class="vip-text-wrap">
          <text class="vip-title">标准试卷会员</text>
          <text class="vip-desc">{{
            membership?.expiresAt
              ? `${stateLabel} · 有效期至 ${formatShanghaiDateTime(membership.expiresAt)}`
              : stateLabel
          }}</text>
        </view>
      </view>
      <button class="vip-btn">{{ actionLabel }}</button>
    </view>

    <!-- 订单快捷入口卡片 -->
    <view class="order-hub-card ui-card">
      <view class="hub-header" @click="openOrders()">
        <text class="hub-title">我的订单</text>
        <view class="view-all">
          <text class="view-all-text">全部订单</text>
          <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
        </view>
      </view>

      <view class="order-grid">
        <view v-for="b in orderBadges" :key="b.tab" class="order-item" @click="openOrders(b.tab)">
          <view class="order-icon" :class="`order-icon--${b.tone}`">
            <UiIcon :name="b.icon" :tone="b.tone" :size="20" />
          </view>
          <text class="item-label">{{ b.label }}</text>
        </view>
      </view>
    </view>

    <!-- 核心功能与服务列表卡片 -->
    <view class="service-menu-card ui-card">
      <view class="menu-item" @click="openLearning">
        <UiIcon name="graduation-cap" />
        <text class="menu-title">我的学习</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <view
        v-if="hasDistributionArchive"
        class="menu-item"
        data-action="distribution"
        @click="openDistribution"
      >
        <UiIcon name="share-2" />
        <text class="menu-title">分销中心</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <view class="menu-item" @click="openAddress">
        <UiIcon name="map-pin" />
        <text class="menu-title">收货地址</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <view class="menu-item" @click="openPapers">
        <UiIcon name="files" />
        <text class="menu-title">会员试卷</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <view class="menu-item" data-action="downloads" @click="openDownloads">
        <UiIcon name="download" />
        <text class="menu-title">我的下载</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>

      <view class="menu-item" @click="contactService">
        <UiIcon name="messages-square" />
        <text class="menu-title">联系客服</text>
        <UiIcon name="chevron-right" class="svg-arrow" :size="18" />
      </view>
    </view>

    <!-- 底部 TabBar -->
    <BaseNav active-tab="/pages/profile/index" />
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.profile-page {
  padding: 24rpx 32rpx calc(140rpx + env(safe-area-inset-bottom));
}
.user-hero-card {
  padding: 16rpx 4rpx 32rpx;
}
.user-info-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.avatar-box {
  flex-shrink: 0;
  width: 108rpx;
  height: 108rpx;
  border-radius: 50%;
  background: $color-primary-light;
  color: $color-primary;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40rpx;
  font-weight: 700;
}
.user-meta {
  flex: 1;
  min-width: 0;
}
.nickname {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
}
.user-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: $color-text-sub;
}
.auth-btn,
.logout-btn {
  padding: 12rpx 20rpx;
  font-size: 24rpx;
  background: $color-primary-light;
  color: $color-primary;
  border-radius: $radius-sm;
}
.logout-btn:after {
  border: 0;
}
.vip-banner-card {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 184rpx;
  padding: 40rpx;
  box-sizing: border-box;
  background: $color-vip-card-bg;
}
.vip-left {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex: 1;
  min-width: 0;
}
.vip-text-wrap {
  min-width: 0;
}
.vip-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
}
.vip-desc {
  display: block;
  font-size: 24rpx;
  color: $color-text-sub;
  margin-top: 8rpx;
  line-height: 1.7;
}
.vip-btn {
  flex-shrink: 0;
  font-size: 24rpx;
  padding: 12rpx 20rpx;
  background: white;
  color: $color-primary;
  border-radius: $radius-sm;
}
.vip-btn:after {
  border: 0;
}
.order-hub-card {
  padding: 28rpx;
}
.hub-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.hub-title {
  font-size: 30rpx;
  font-weight: 600;
}
.view-all {
  display: flex;
  align-items: center;
  color: $color-text-sub;
  font-size: 24rpx;
}
.order-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8rpx;
  padding-top: 24rpx;
}
.order-item {
  min-height: 100rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
}
.item-label {
  font-size: 24rpx;
  color: $color-text-sub;
}
.service-menu-card {
  /* Icons match the frozen menu; permission-dependent entries remain conditional. */
  padding: 8rpx 28rpx;
}
.order-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80rpx;
  height: 80rpx;
  border-radius: 24rpx;
  background: $color-primary-light;
}
.order-icon--success {
  background: #eaf7f0;
}
.menu-item {
  display: flex;
  align-items: center;
  min-height: 108rpx;
  gap: 20rpx;
  padding: 20rpx 0;
  border-bottom: 1rpx solid $color-border;
}
.menu-item:last-child {
  border-bottom: 0;
}
.menu-title {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
}
</style>
