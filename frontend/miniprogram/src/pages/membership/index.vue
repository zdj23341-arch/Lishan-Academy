<script setup lang="ts">
import UiIcon from '../../components/ui/UiIcon.vue';
import { computed, ref, watch } from 'vue';
import PriceText from '../../components/PriceText.vue';
import AsyncState from '../../components/AsyncState.vue';
import { useMembership } from '../../composables/useMembership';
import { session } from '../../api/session';
import { formatFen, formatShanghaiDateTime } from '@jxsc/shared';

const { membership, plans, loading, error, refresh, stateLabel, actionLabel } = useMembership();
const selectedPlanId = ref('');
const navigating = ref(false);
const navigationError = ref('');
const selectedPlan = computed(() =>
  plans.value.find((plan) => plan.productId === selectedPlanId.value),
);
watch(plans, (value) => {
  if (!value.length) return;
  if (!value.some((plan) => plan.productId === selectedPlanId.value))
    selectedPlanId.value =
      value.find((plan) => plan.durationMonths === 12)?.productId ?? value[0]?.productId ?? '';
});
async function buyMembership() {
  const plan = selectedPlan.value;
  if (!plan || loading.value || navigating.value) return;
  navigating.value = true;
  navigationError.value = '';
  const checkout = `/pages/order/confirm?productId=${encodeURIComponent(plan.productId)}&quantity=1`;
  const guest = !session.get();
  if (guest) {
    // This explicit purchase choice supersedes a stale automatic-login target.
    session.takeReturnTo();
    session.rememberReturnTo(checkout);
  }
  try {
    await uni.navigateTo({ url: guest ? '/pages/login/index' : checkout });
  } catch {
    if (guest) session.takeReturnTo();
    navigationError.value = guest ? '登录页打开失败，请重试' : '购买页打开失败，请重试';
  } finally {
    navigating.value = false;
  }
}
const selectedExpiryMessage = computed(() => {
  const plan = selectedPlan.value;
  if (!plan) return '';
  const projectedAt =
    'projectedExpiresAt' in plan && typeof plan.projectedExpiresAt === 'string'
      ? formatShanghaiDateTime(plan.projectedExpiresAt)
      : '';
  const suffix = projectedAt ? `预计至 ${projectedAt}` : '预计日期以服务端返回结果为准';
  if (membership.value?.state === 'ACTIVE') return `续费从当前到期日顺延，${suffix}`;
  if (membership.value?.state === 'EXPIRED') return `重新开通后有效期，${suffix}`;
  return `开通后有效期，${suffix}`;
});
</script>

<template>
  <view class="ls-mini-page membership-page ui-page-container">
    <AsyncState
      :loading="loading"
      :error="error"
      :empty="!plans.length"
      empty-text="暂时没有可购买的会员套餐，请稍后再来"
      @retry="refresh"
    />
    <text v-if="navigationError" role="alert" class="ls-alert">{{ navigationError }}</text>
    <view class="vip-header-card">
      <view class="vip-badge-pill">
        <UiIcon name="crown" tone="warning" />
        <text class="vip-text">试卷会员</text>
      </view>
      <text class="vip-title">好资料，备课更从容。</text>
      <view class="vip-description">
        <text>有效期内下载全部会员试卷</text>
        <text>Word、PDF、图片，按需选择</text>
      </view>
    </view>

    <view class="plans-grid">
      <view
        v-for="plan in plans"
        :key="plan.productId"
        class="plan-card"
        :class="{ 'plan-card--active': selectedPlanId === plan.productId }"
        @click="selectedPlanId = plan.productId"
      >
        <text class="plan-title">{{ plan.durationMonths === 6 ? '半年会员' : '一年会员' }}</text>
        <text class="plan-price">¥{{ formatFen(plan.priceFen) }}</text>
        <text class="plan-duration">有效期 {{ plan.durationMonths }} 个月</text>
      </view>
    </view>

    <view class="member-summary ui-card">
      <view class="member-summary-head">
        <text class="summary-title">我的会员</text>
        <text v-if="membership" class="status-chip">{{ stateLabel }}</text>
      </view>
      <view class="vip-info-box">
        <text class="vip-main-status">
          {{
            membership?.state === 'ACTIVE' || membership?.state === 'EXPIRED'
              ? '标准试卷会员'
              : membership
                ? '未开通会员'
                : stateLabel
          }}
        </text>
        <view v-if="membership?.startsAt" class="vip-date-tip">
          生效时间：{{ formatShanghaiDateTime(membership.startsAt) }}
        </view>
        <text v-if="membership?.expiresAt" class="vip-date-tip">
          {{ membership.state === 'EXPIRED' ? '有效期已于：' : '当前有效期至：' }}
          {{ formatShanghaiDateTime(membership.expiresAt) }}
        </text>
        <text v-else class="vip-date-tip">
          {{ membership ? '开通后可访问会员试卷资料' : stateLabel }}
        </text>
      </view>
      <text v-if="membership && selectedPlan" class="renewal-note">
        {{ selectedExpiryMessage }}
      </text>
    </view>

    <view class="benefits-section ui-card">
      <text class="benefits-title">权益说明</text>
      <text class="benefit-line">包含会员试卷原文件预览与下载；已保存到设备的文件可继续使用。</text>
      <text class="benefit-line">视频课程需单独付费；会员不包含实体图书。</text>
    </view>

    <view class="bottom-action-bar">
      <PriceText
        v-if="selectedPlan"
        class="bottom-plan-price"
        :fen="selectedPlan.priceFen"
        size="lg"
      />
      <button
        class="ui-btn ui-btn--vip full-buy-btn"
        :disabled="loading || navigating || !selectedPlan"
        :loading="navigating"
        @click="buyMembership"
      >
        {{ actionLabel }}
      </button>
    </view>
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.membership-page {
  padding: 32rpx 32rpx calc(140rpx + env(safe-area-inset-bottom));
}

.vip-header-card {
  padding: 48rpx 40rpx;
  border-radius: $radius-lg;
  background: $color-vip-card-bg;
  color: $color-text-main;
  margin-bottom: 24rpx;
}

.vip-badge-pill {
  display: flex;
  align-items: center;
  gap: 8rpx;
  color: $color-vip-gold;
  font-size: 26rpx;
}

.status-chip {
  font-size: 24rpx;
  color: $color-accent;
  background: $color-accent-light;
  border-radius: $radius-xs;
  padding: 4rpx 12rpx;
}

.vip-title {
  display: block;
  margin-top: 24rpx;
  color: $color-vip-dark;
  font-family: STSong, SimSun, serif;
  font-size: 50rpx;
  line-height: 1.45;
}

.vip-description {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  margin-top: 16rpx;
  color: $color-text-sub;
  font-size: 24rpx;
  line-height: 1.55;
}

.plans-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
  margin-bottom: 32rpx;
}

.plan-card {
  display: flex;
  min-width: 0;
  min-height: 230rpx;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12rpx;
  padding: 34rpx 26rpx;
  border: 2rpx solid $color-border;
  border-radius: $radius-md;
  background: $color-bg-card;
  box-sizing: border-box;
  cursor: pointer;
}

.plan-card--active {
  border-color: $color-primary;
  background: $color-primary-light;
}

.plan-title {
  font-size: 26rpx;
  line-height: 1.35;
  color: $color-text-main;
}

.plan-price {
  font-size: 58rpx;
  font-weight: 650;
  color: $color-text-main;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}

.plan-duration {
  font-size: 22rpx;
  line-height: 1.4;
  color: $color-text-sub;
}

.member-summary,
.benefits-section {
  margin-bottom: 24rpx;
  padding: 24rpx;
}

.member-summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.summary-title,
.benefits-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: $color-text-main;
}

.vip-info-box {
  margin-top: 20rpx;
}

.vip-main-status {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  line-height: 1.4;
  color: $color-text-main;
}

.vip-date-tip {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: $color-text-sub;
  line-height: 1.5;
}

.renewal-note {
  display: block;
  margin-top: 16rpx;
  padding: 12rpx 16rpx;
  border-radius: $radius-xs;
  background: $color-primary-light;
  color: $color-text-sub;
  font-size: 22rpx;
  line-height: 1.5;
}

.benefits-section {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.benefit-line {
  display: block;
  font-size: 22rpx;
  color: $color-text-sub;
  line-height: 1.55;
}

.bottom-action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: white;
  border-top: 1rpx solid $color-border;
  padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));
}

.bottom-plan-price {
  flex: 0 0 auto;
  color: $color-price;
}

.full-buy-btn {
  flex: 1;
  min-width: 0;
  background: $color-primary;
  color: white;
  min-height: 88rpx;
}
</style>
