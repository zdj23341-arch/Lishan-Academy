<script setup lang="ts">
import BookCover from '../../components/ui/BookCover.vue';
import UiIcon from '../../components/ui/UiIcon.vue';
import { computed, ref, watch } from 'vue';
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app';
import { canLearn, purchaseError, referralManager } from '@jxsc/shared';
import { catalog } from '../../composables/useCatalog';
import { useAccountCatalogDetail } from '../../composables/useAccountCatalogDetail';
import { distributionApi } from '../../api/distribution';
import AsyncState from '../../components/AsyncState.vue';
import ContentBlocks from '../../components/ContentBlocks.vue';
import PriceText from '../../components/PriceText.vue';
import CourseChapterPreview from '../../components/CourseChapterPreview.vue';

const {
  data: product,
  loading,
  error,
  load,
} = useAccountCatalogDetail(catalog.product, {
  missingIdMessage: '缺少商品编号',
});
const quantity = ref('1');
const currentSharePath = ref('');
watch(product, (currentProduct) => {
  currentSharePath.value = '';
  if (!currentProduct) return;
  const title =
    currentProduct.type === 'COURSE'
      ? '课程详情'
      : currentProduct.type === 'BOOK'
        ? '图书详情'
        : '会员详情';
  uni.setNavigationBarTitle?.({ title });
});

const quantityNum = computed(() => Number(quantity.value));

const quantityError = computed(() =>
  product.value ? purchaseError(product.value, quantityNum.value) : '',
);

function changeQty(delta: number) {
  const next = quantityNum.value + delta;
  if (next >= 1) {
    quantity.value = String(next);
  }
}

function buy() {
  if (product.value && !loading.value && !error.value && !quantityError.value) {
    uni.navigateTo({
      url: `/pages/order/confirm?productId=${encodeURIComponent(product.value.id)}&quantity=${quantityNum.value}`,
    });
  }
}

function openCourse() {
  const value = product.value;
  if (
    value?.type === 'COURSE' &&
    !loading.value &&
    !error.value &&
    value.course.courseId &&
    (canLearn(value) || value.allowedActions.includes('TRIAL'))
  ) {
    uni.navigateTo({
      url: `/pages/course/learn?id=${encodeURIComponent(value.course.courseId)}&productId=${encodeURIComponent(value.id)}`,
    });
  }
}

onShareAppMessage(() => {
  const p = product.value;
  return {
    title: p?.title || '推荐好物',
    path: currentSharePath.value || `/pages/product/detail?id=${encodeURIComponent(p?.id || '')}`,
  };
});

async function onShare() {
  const currentProduct = product.value;
  if (!currentProduct || loading.value || error.value) return;
  try {
    const res = await distributionApi.createReferralCode(currentProduct.id);
    if (product.value !== currentProduct) return;
    currentSharePath.value = res.miniProgramPath;
    uni.showModal({
      title: '推广专属链接已就绪',
      content: '已为您绑定专属推广码，点击右上角【...】发送给好友即可完成归因推广！',
      showCancel: false,
    });
  } catch {
    if (product.value !== currentProduct) return;
    currentSharePath.value = `/pages/product/detail?id=${encodeURIComponent(currentProduct.id)}`;
    uni.showToast({ title: '已就绪普通分享，点击右上角【...】转发给好友', icon: 'none' });
  }
}

onLoad(async (options) => {
  const productId = options?.id ?? '';
  quantity.value = '1';
  currentSharePath.value = '';

  const refCode = options?.ref || (options?.scene ? decodeURIComponent(options.scene) : null);
  if (refCode && productId) {
    const generation = referralManager.context();
    try {
      let visitorKey = '';
      try {
        visitorKey = uni.getStorageSync(`jxsc.visitor_key:${referralManager.namespace()}`) || '';
      } catch {
        // 忽略存储异常
      }
      if (!visitorKey) {
        visitorKey = 'vis_' + Math.random().toString(36).slice(2);
        try {
          uni.setStorageSync(`jxsc.visitor_key:${referralManager.namespace()}`, visitorKey);
        } catch {
          // 忽略存储异常
        }
      }
      const res = await distributionApi.resolveReferral(refCode, visitorKey);
      if (generation !== referralManager.context()) return;
      if (res && String(res.productId) === productId && res.attributionToken) {
        referralManager.saveAttribution(productId, res.attributionToken, res.expiresAt);
        if (referralManager.authenticated())
          await referralManager.claimPending((token) => distributionApi.claimReferral(token));
      }
    } catch {
      if (generation === referralManager.context())
        uni.showToast({ title: '推荐信息暂未认领，下单前可重试或不使用推荐继续', icon: 'none' });
    }
  }
});
</script>

<template>
  <view class="ls-mini-page product-detail-page ui-page-container">
    <AsyncState :loading="loading" :error="error" @retry="load()" />

    <view v-if="product" class="detail-container">
      <!-- 顶部沉浸封面 -->
      <view class="detail-hero-box">
        <BookCover
          :title="product.title"
          :image-url="product.coverUrl"
          :theme="product.type === 'BOOK' ? 'sand' : 'blue'"
        />

        <!-- 下架水印 -->
        <view v-if="product.status === 'OFF_SALE'" class="offsale-banner">
          <text class="offsale-text">已下架 · 仅保留已有权益访问</text>
        </view>
      </view>

      <!-- 核心价格与标题卡片 -->
      <view class="price-title-card ui-card">
        <text class="detail-title">{{ product.title }}</text>
        <view class="price-row">
          <PriceText :fen="product.priceFen" :original="product.originalPriceFen" size="hero" />
          <view class="type-tag-wrap">
            <view
              class="ui-tag"
              :class="{
                'ui-tag--primary': product.type === 'COURSE',
                'ui-tag--accent': product.type === 'BOOK',
                'ui-tag--vip': product.type === 'MEMBERSHIP',
              }"
            >
              {{
                product.type === 'COURSE'
                  ? '视频课程'
                  : product.type === 'BOOK'
                    ? '实体图书'
                    : '试卷会员'
              }}
            </view>
          </view>
        </view>

        <view class="detail-subtitle-row">
          <text v-if="product.subtitle" class="detail-subtitle">{{ product.subtitle }}</text>
          <button class="detail-share-button" type="button" @click="onShare">
            <UiIcon name="share-2" :size="16" />
            <text>分享</text>
          </button>
        </view>
      </view>

      <!-- 图书专属属性卡片 -->
      <view v-if="product.type === 'BOOK'" class="spec-card ui-card">
        <view class="spec-item">
          <text class="spec-label">库存状态</text>
          <text class="spec-value">
            剩余可售 <text class="highlight">{{ product.book.availableStock }}</text> 本
            <text class="limit-tip">（每单限购 {{ product.book.quantityLimit }} 本）</text>
          </text>
        </view>

        <view class="spec-item">
          <text class="spec-label">配送说明</text>
          <text class="spec-value">{{ product.book.shippingSummary || '运费以结算页为准' }}</text>
        </view>

        <!-- 数量加减计数器 -->
        <view class="spec-item spec-item--stepper">
          <text class="spec-label">购买数量</text>
          <view class="stepper-box">
            <button class="step-btn" :disabled="quantityNum <= 1" @click="changeQty(-1)">-</button>
            <input class="step-input" v-model="quantity" type="number" />
            <button
              class="step-btn"
              :disabled="
                quantityNum >= product.book.availableStock ||
                quantityNum >= product.book.quantityLimit
              "
              @click="changeQty(1)"
            >
              +
            </button>
          </view>
        </view>

        <view v-if="quantityError" class="spec-error">
          <text class="error-text"> {{ quantityError }}</text>
        </view>
      </view>

      <!-- 课程专属属性卡片 -->
      <view v-else-if="product.type === 'COURSE'" class="spec-card ui-card">
        <view class="course-badge-row">
          <view class="course-prop">
            <text class="prop-label">观看有效期</text>
            <text class="prop-val highlight">
              {{
                product.course.validityType === 'PERMANENT'
                  ? '永久有效'
                  : `${product.course.validityDays} 天`
              }}
            </text>
          </view>
          <view class="course-prop">
            <text class="prop-label">包含内容</text>
            <text class="prop-val">共 {{ product.course.chaptersSummary }} 章录播视频</text>
          </view>
          <view class="course-prop">
            <text class="prop-label">内容时长</text>
            <text class="prop-val">
              {{
                product.course.durationMinutes != null
                  ? `${product.course.durationMinutes} 分钟内容`
                  : '时长待课程章节统计'
              }}
            </text>
          </view>
        </view>

        <view class="notice-box">
          <text class="notice-text">
            提示：本课程为独立付费录播教学课程，享有独立观看权益；试卷会员不包含此课程。</text
          >
        </view>

        <!-- 学习行动入口 -->
        <view v-if="canLearn(product)" class="learning-action-box">
          <button class="ui-btn ui-btn--accent full-btn" @click="openCourse">
            <UiIcon name="play" tone="success" :size="18" /> 进入学习
          </button>
        </view>
      </view>

      <!-- 会员专属属性卡片 -->
      <view v-else-if="product.type === 'MEMBERSHIP'" class="spec-card ui-card vip-spec-card">
        <view class="vip-spec-header">
          <text class="vip-tag-gold">试卷会员</text>
          <text class="vip-duration">{{ product.membership.durationMonths }} 个自然月会员</text>
        </view>
        <view class="vip-privilege-list">
          <view class="privilege-item">
            <UiIcon name="file-text" tone="warning" :size="18" />
            <text class="p-text">有效期内访问会员试卷原文件</text>
          </view>
          <view class="privilege-item">
            <UiIcon name="download" tone="warning" :size="18" />
            <text class="p-text">预览与下载能力依设备和文件格式而定</text>
          </view>
          <view class="privilege-item">
            <UiIcon name="clock" tone="warning" :size="18" />
            <text class="p-text">续费有效期以结算时服务端结果为准</text>
          </view>
        </view>
      </view>

      <CourseChapterPreview
        v-if="product.type === 'COURSE' && product.course.courseId"
        :course-id="product.course.courseId"
        :product-id="product.id"
        :owned="canLearn(product)"
      />

      <!-- 图文详情介绍区 -->
      <view class="detail-content-card ui-card">
        <view class="section-title-bar">
          <view class="accent-line" />
          <text class="section-title">{{
            product.type === 'COURSE' ? '课程介绍' : '商品介绍'
          }}</text>
        </view>
        <view class="content-body">
          <ContentBlocks :blocks="product.contentBlocks" />
        </view>
      </view>

      <!-- 底部悬浮购买/操作栏 (吸底) -->
      <view class="bottom-action-bar">
        <PriceText class="bottom-price" :fen="product.priceFen" size="lg" />

        <view class="main-actions">
          <!-- 试看按钮（如果是课程且支持试看） -->
          <button
            v-if="product.allowedActions.includes('TRIAL')"
            class="ui-btn ui-btn--outline trial-btn"
            @click="openCourse"
          >
            免费试看
          </button>

          <button
            v-if="canLearn(product)"
            class="ui-btn buy-btn"
            data-action="learn-owned-course"
            :disabled="loading || !!error"
            @click="openCourse"
          >
            进入学习
          </button>

          <!-- 立即购买/开通按钮 -->
          <button
            v-else-if="product.allowedActions.includes('BUY')"
            class="ui-btn buy-btn"
            :class="product.type === 'MEMBERSHIP' ? 'ui-btn--vip' : 'ui-btn--price'"
            :disabled="!!quantityError"
            @click="buy"
          >
            {{ product.type === 'MEMBERSHIP' ? '立即开通会员' : '立即购买' }}
          </button>

          <!-- 不允许购买提示 -->
          <view v-else class="disabled-tip-box">
            <text class="disabled-tip-text">当前暂不可购买或已下架</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.product-detail-page {
  padding: 32rpx 32rpx calc(160rpx + env(safe-area-inset-bottom));
  background-color: $color-bg-page;
}

.detail-hero-box {
  position: relative;
  width: 100%;
  height: auto;
  aspect-ratio: 3 / 4;
  border-radius: $radius-md;
  margin-bottom: 24rpx;
  background-color: #edf3ff;
  overflow: hidden;

  .hero-image {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: contain;
    background: #edf3ff;
  }

  .hero-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #2855b7;
    color: #ffffff;
    padding: 40rpx;

    .placeholder-text {
      font-size: 36rpx;
      font-weight: 700;
      text-align: center;
    }
  }

  .offsale-banner {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    background: rgba(0, 0, 0, 0.75);
    padding: 16rpx 24rpx;
    text-align: center;

    .offsale-text {
      color: #ffffff;
      font-size: 26rpx;
      font-weight: 600;
    }
  }
}

.price-title-card {
  margin: 0 0 24rpx;
  padding: 32rpx;
  position: relative;
  z-index: 10;

  .price-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20rpx;
  }

  .detail-title {
    font-size: 34rpx;
    font-weight: 700;
    color: $color-text-main;
    line-height: 1.4;
    display: block;
    margin-bottom: 12rpx;
  }

  .detail-subtitle {
    font-size: 26rpx;
    color: $color-text-secondary;
    line-height: 1.45;
  }

  .detail-subtitle-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16rpx;
  }

  .detail-subtitle-row .detail-subtitle {
    flex: 1;
    min-width: 0;
  }

  .detail-share-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 8rpx;
    min-width: 88rpx;
    min-height: 88rpx;
    margin: -20rpx -12rpx -20rpx 0;
    padding: 0 12rpx;
    border: 0;
    border-radius: $radius-xs;
    background: transparent;
    color: $color-primary;
    font-size: 24rpx;

    &::after {
      border: 0;
    }
  }
}

.spec-card {
  margin: 0 0 24rpx;
  padding: 28rpx;

  .spec-item {
    display: flex;
    align-items: flex-start;
    padding: 16rpx 0;
    border-bottom: 1rpx solid $color-border-light;

    &:last-child {
      border-bottom: none;
    }

    &--stepper {
      justify-content: space-between;
    }

    .spec-label {
      width: 130rpx;
      font-size: 26rpx;
      color: $color-text-secondary;
      flex-shrink: 0;
    }

    .spec-value {
      flex: 1;
      min-width: 0;
      font-size: 26rpx;
      color: $color-text-main;

      .highlight {
        color: $color-price;
        font-weight: 700;
      }

      .limit-tip {
        color: $color-text-secondary;
        font-size: 24rpx;
      }
    }
  }

  .stepper-box {
    display: inline-flex;
    align-items: center;
    border: 1rpx solid $color-border;
    border-radius: $radius-sm;
    overflow: hidden;

    .step-btn {
      width: 88rpx;
      min-height: 88rpx;
      line-height: 88rpx;
      text-align: center;
      background: #f8f9fa;
      font-size: 32rpx;
      font-weight: 700;
      color: $color-text-main;
      border: none;
      border-radius: 0;

      &::after {
        display: none;
      }
      &:active {
        background: #eceff3;
      }
    }

    .step-input {
      width: 88rpx;
      height: 88rpx;
      text-align: center;
      font-size: 28rpx;
      font-weight: 600;
      background: #ffffff;
    }
  }

  .spec-error {
    margin-top: 16rpx;
    padding: 12rpx 20rpx;
    background: #fff0ee;
    border-radius: $radius-xs;

    .error-text {
      color: $color-price;
      font-size: 24rpx;
      font-weight: 500;
    }
  }
}

// 课程属性
.course-badge-row {
  display: flex;
  gap: 16rpx;
  margin-bottom: 20rpx;

  .course-prop {
    flex: 1;
    min-width: 0;
    background: #f4f8fe;
    padding: 20rpx;
    border-radius: $radius-sm;
    display: flex;
    flex-direction: column;
    gap: 8rpx;

    .prop-label {
      font-size: 24rpx;
      color: $color-text-secondary;
    }

    .prop-val {
      font-size: 28rpx;
      font-weight: 700;
      color: $color-text-main;

      &.highlight {
        color: $color-primary;
      }
    }
  }
}

.notice-box {
  background: #fffbf2;
  border: 1rpx solid #ffe6af;
  padding: 16rpx 20rpx;
  border-radius: $radius-sm;
  margin-bottom: 20rpx;

  .notice-text {
    font-size: 24rpx;
    color: #b45309;
    line-height: 1.45;
  }
}

.learning-action-box {
  margin-top: 10rpx;

  .full-btn {
    width: 100%;
    height: 76rpx;
    font-size: 28rpx;
  }
}

// VIP 卡片
.vip-spec-card {
  background: $color-vip-card-bg;
  color: $color-text-main;

  .vip-spec-header {
    display: flex;
    align-items: center;
    gap: 16rpx;
    margin-bottom: 20rpx;

    .vip-tag-gold {
      background: $color-vip-gradient;
      color: #382405;
      font-size: 24rpx;
      font-weight: 700;
      padding: 4rpx 14rpx;
      border-radius: 999rpx;
    }

    .vip-duration {
      font-size: 30rpx;
      font-weight: 700;
      color: $color-warning;
    }
  }

  .vip-privilege-list {
    display: flex;
    flex-direction: column;
    gap: 12rpx;

    .privilege-item {
      display: flex;
      align-items: center;
      gap: 12rpx;

      .p-icon {
        color: $color-warning;
        font-weight: 700;
        font-size: 24rpx;
      }

      .p-text {
        font-size: 24rpx;
        color: $color-text-sub;
      }
    }
  }
}

// 图文详情
.detail-content-card {
  margin: 0 0 24rpx;
  padding: 28rpx;

  .section-title-bar {
    display: flex;
    align-items: center;
    gap: 12rpx;
    margin-bottom: 24rpx;

    .accent-line {
      width: 6rpx;
      height: 28rpx;
      background: $color-primary;
      border-radius: 3rpx;
    }

    .section-title {
      font-size: 30rpx;
      font-weight: 700;
      color: $color-text-main;
    }
  }
}

// 吸底操作栏
.bottom-action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 900;
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(20px);
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: $shadow-nav;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;

  .bottom-price {
    flex: 0 0 auto;
  }

  .main-actions {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 12rpx;

    .trial-btn {
      padding: 12rpx 16rpx;
      font-size: 26rpx;
    }

    .buy-btn {
      background: $color-primary;
      color: #fff;
      box-shadow: none;
      flex: 1;
      height: 76rpx;
      max-width: 380rpx;
      font-size: 28rpx;
      padding: 12rpx 16rpx;
    }

    .disabled-tip-box {
      padding: 16rpx;
      background: #f3f4f6;
      border-radius: $radius-full;
      text-align: center;
      flex: 1;

      .disabled-tip-text {
        font-size: 24rpx;
        color: $color-text-muted;
      }
    }
  }
}
.detail-hero-box :deep(.ls-cover) {
  height: 100%;
  border-radius: $radius-md;
}
.detail-hero-box :deep(.ls-cover-title) {
  font-size: 58rpx;
}
</style>
