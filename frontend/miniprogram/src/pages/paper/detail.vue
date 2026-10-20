<script setup lang="ts">
import AsyncState from '../../components/AsyncState.vue';
import ContentBlocks from '../../components/ContentBlocks.vue';
import FileRow from '../../components/FileRow.vue';
import BookCover from '../../components/ui/BookCover.vue';
import UiIcon from '../../components/ui/UiIcon.vue';
import { useMembership } from '../../composables/useMembership';
import { usePaper } from '../../composables/usePaper';
import { paperTypeLabel } from '../../utils/customer-text';

const { membership, refresh: refreshMembership } = useMembership();
const {
  paper,
  loading,
  error,
  load: loadPaper,
  files,
  open,
  goMembership,
  login,
  settingsError,
  availabilityError,
  canOpenAlbumSettings,
  openAlbumSettings,
} = usePaper(() => membership.value?.state);
function schoolStageLabel(value: string | null | undefined) {
  if (value === 'PRIMARY') return '小学';
  if (value === 'JUNIOR') return '初中';
  if (value === 'SENIOR') return '高中';
  return value || '学段待确认';
}
function accessLabel(value: string | null | undefined) {
  return value === 'ACTIVE_MEMBER' ? '会员可访问' : value ? `访问条件：${value}` : '访问条件待确认';
}
function resourceLabel(value: string | null | undefined) {
  if (value === 'READY') return '资源已就绪';
  if (value === 'NOT_READY') return '资源准备中';
  return value ? `资源状态：${value}` : '资源状态待确认';
}
async function load() {
  await Promise.all([loadPaper(), refreshMembership()]);
}
</script>

<template>
  <view class="ls-mini-page paper-detail-page ui-page-container">
    <AsyncState :loading="loading" :error="error" @retry="load()" />
    <view v-if="files.error || settingsError || availabilityError" role="alert" class="ls-alert">
      <text>{{ settingsError || availabilityError || files.error }}</text>
      <button
        v-if="availabilityError || files.errorCode === 'FILE_NOT_READY'"
        data-action="refresh-files"
        @click="load"
      >
        刷新文件状态后重试
      </button>
      <button
        v-if="files.errorCode === 'ALBUM_PERMISSION_DENIED' && canOpenAlbumSettings"
        data-action="album-settings"
        @click="openAlbumSettings"
      >
        权限设置
      </button>
      <button v-if="files.errorCode === 'UNAUTHORIZED'" @click="login">登录后重试</button>
      <button
        v-if="files.errorCode === 'MEMBERSHIP_EXPIRED' || files.errorCode === 'FORBIDDEN'"
        @click="goMembership"
      >
        {{ files.errorCode === 'MEMBERSHIP_EXPIRED' ? '续费会员' : '开通会员' }}
      </button>
    </view>

    <view v-if="paper" class="detail-container">
      <BookCover
        :title="paper.title"
        :image-url="paper.coverUrl ?? null"
        theme="paper"
        class="paper-cover"
      />
      <!-- 试卷核心信息卡片 -->
      <view class="paper-header-card ui-card">
        <view class="tags-row">
          <view class="ui-tag ui-tag--primary">{{
            schoolStageLabel(paper.schoolStage || paper.stage)
          }}</view>
          <view class="ui-tag ui-tag--primary">{{ paper.grade }}年级</view>
          <view class="ui-tag ui-tag--accent">
            {{ paper.year == null ? '年份待确认' : `${paper.year}年份` }}
          </view>
          <view class="ui-tag ui-tag--accent">{{ paperTypeLabel(paper.paperType) }}</view>
          <view class="ui-tag ui-tag--vip">会员试卷</view>
        </view>

        <text class="paper-title">{{ paper.title }}</text>

        <view v-if="paper.summary" class="summary-box">
          <text class="summary-label">试卷简介：</text>
          <text class="summary-content">{{ paper.summary }}</text>
        </view>
        <view v-else class="summary-box summary-box--empty">
          <text class="summary-content">暂无公开摘要</text>
        </view>
        <view class="paper-contract-status">
          <text>{{ accessLabel(paper.accessCondition) }}</text>
          <text>{{ resourceLabel(paper.resourceStatus) }}</text>
        </view>
      </view>

      <!-- 试卷附件列表卡片（Word/PDF/图片） -->
      <view class="attachments-card ui-card">
        <view class="card-header">
          <view class="accent-line" />
          <text class="header-title">试卷附件与下载 ({{ paper.attachments.length }})</text>
        </view>

        <view v-if="paper.attachments.length" class="files-list">
          <FileRow
            v-for="file in paper.attachments"
            :key="file.id"
            v-slot="row"
            :file="file"
            :busy="files.busy"
            :membership-state="membership?.state"
            @preview="open(file, 'PREVIEW')"
            @download="open(file, 'DOWNLOAD')"
          >
            <view class="file-row">
              <!-- 文件类型图标 -->
              <view
                class="ext-badge"
                :style="{ backgroundColor: row.badge.bg, color: row.badge.color }"
              >
                <text class="ext-text">{{ row.badge.label }}</text>
              </view>

              <!-- 文件名与大小 -->
              <view class="file-info">
                <text class="file-name text-ellipsis-2">{{ file.name }}</text>
                <text class="file-size">{{ row.size }} · .{{ row.extension }}</text>
                <text class="file-size">{{ row.permission }}</text>
                <view class="file-actions">
                  <button
                    class="act-btn act-btn--preview"
                    :class="{ 'act-btn--disabled': !file.canPreview }"
                    :disabled="files.busy"
                    @click="row.preview"
                  >
                    预览
                  </button>
                  <button
                    class="act-btn act-btn--download"
                    :class="{ 'act-btn--disabled': !file.canDownload }"
                    :disabled="files.busy"
                    @click="row.download"
                  >
                    下载
                  </button>
                </view>
              </view>
            </view>
          </FileRow>
        </view>

        <view v-else class="empty-files">
          <text class="empty-tip">该试卷暂无附件提供</text>
        </view>
      </view>

      <!-- 试卷样张与试题解析预览 -->
      <view v-if="paper.contentBlocks && paper.contentBlocks.length" class="sample-card ui-card">
        <view class="card-header">
          <view class="accent-line" />
          <text class="header-title">公开样张与题型解析</text>
        </view>
        <view class="sample-content">
          <ContentBlocks :blocks="paper.contentBlocks" />
        </view>
      </view>

      <view class="permission-note">
        <UiIcon name="shield-check" :size="18" />
        <text>每次预览与下载均重新校验权限，授权失效后可重新获取。</text>
      </view>
    </view>

    <view v-if="paper" class="paper-detail-bottom-bar">
      <button class="paper-detail-membership-btn" @click="goMembership">查看会员权益</button>
    </view>
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.paper-detail-page {
  padding: 32rpx 32rpx calc(152rpx + env(safe-area-inset-bottom));
}
.paper-cover {
  margin-bottom: 24rpx;
}

.paper-header-card {
  padding: 28rpx 24rpx;

  .tags-row {
    display: flex;
    gap: 12rpx;
    flex-wrap: wrap;
    margin-bottom: 16rpx;
  }

  .paper-title {
    font-size: 34rpx;
    font-weight: 700;
    color: $color-text-main;
    line-height: 1.4;
    display: block;
    margin-bottom: 16rpx;
  }

  .summary-box {
    background: #f9fafc;
    border-radius: $radius-sm;
    padding: 16rpx 20rpx;
    line-height: 1.5;

    .summary-label {
      font-size: 24rpx;
      font-weight: 600;
      color: $color-text-sub;
    }

    .summary-content {
      font-size: 24rpx;
      color: $color-text-secondary;
    }

    &--empty {
      .summary-content {
        color: $color-text-muted;
      }
    }
  }

  .paper-contract-status {
    display: flex;
    flex-wrap: wrap;
    gap: 16rpx;
    margin-top: 16rpx;

    text {
      font-size: 24rpx;
      color: $color-primary;
    }
  }
}

.attachments-card,
.sample-card {
  padding: 24rpx;

  .card-header {
    display: flex;
    align-items: center;
    gap: 12rpx;
    padding-bottom: 20rpx;
    border-bottom: 1rpx solid $color-border-light;
    margin-bottom: 16rpx;

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

.files-list {
  display: flex;
  flex-direction: column;
}

.file-row {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid $color-border-light;

  &:last-child {
    border-bottom: none;
  }

  .ext-badge {
    width: 80rpx;
    height: 80rpx;
    border-radius: $radius-sm;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24rpx;
    font-weight: 700;
    flex-shrink: 0;
  }

  .file-info {
    flex: 1;
    min-width: 0;
    margin: 0 16rpx;
    display: flex;
    flex-direction: column;
    gap: 6rpx;

    .file-name {
      font-size: 26rpx;
      font-weight: 600;
      color: $color-text-main;
      line-height: 1.35;
    }

    .file-size {
      font-size: 24rpx;
      color: $color-text-muted;
    }
  }

  .file-actions {
    display: flex;
    gap: 12rpx;

    .act-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 88rpx;
      margin: 0;
      line-height: 1.5;
      padding: 0 20rpx;
      border-radius: $radius-sm;
      font-size: 24rpx;
      font-weight: 600;
      border: none;
      outline: none;

      &::after {
        display: none;
      }

      &--preview {
        background: $color-primary-light;
        color: $color-primary;
      }

      &--download {
        background: #2855b7;
        color: #ffffff;
      }

      &--disabled {
        background: #f3f4f6 !important;
        color: #b5b9c2 !important;
      }
    }
  }
}

.empty-files {
  padding: 40rpx;
  text-align: center;
  .empty-tip {
    font-size: 24rpx;
    color: $color-text-secondary;
  }
}

.sample-content {
  padding-top: 10rpx;
}
.permission-note {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  border-radius: $radius-sm;
  background: $color-primary-light;
  color: $color-text-sub;
  font-size: 24rpx;
  line-height: 1.6;
}

.paper-detail-bottom-bar {
  position: fixed;
  z-index: 20;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid $color-border;
  background: #fff;
  box-sizing: border-box;
}

.paper-detail-membership-btn {
  width: 100%;
  min-height: 88rpx;
  margin: 0;
  padding: 0 24rpx;
  border: 0;
  border-radius: $radius-sm;
  background: $color-primary;
  color: #fff;
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1.5;

  &::after {
    display: none;
  }
}
</style>
