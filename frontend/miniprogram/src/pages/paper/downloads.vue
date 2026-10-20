<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { formatShanghaiDateTime } from '@jxsc/shared';
import AsyncState from '../../components/AsyncState.vue';
import UiIcon from '../../components/ui/UiIcon.vue';
import { downloadApi, type DownloadRecord } from '../../api/downloads';
import { useProtectedFiles } from '../../composables/useProtectedFiles';
import { customerMessage } from '../../utils/customer-text';
import { session } from '../../api/session';

const records = ref<DownloadRecord[]>([]);
const loading = ref(false);
const error = ref('');
const loggedIn = ref(!!session.get());
const { controller, files } = useProtectedFiles();
let generation = 0;
const unsubscribe = session.subscribe(() => {
  generation++;
  loggedIn.value = !!session.get();
  records.value = [];
  error.value = '';
  controller.clear();
});
onUnmounted(() => {
  generation++;
  controller.dispose();
  unsubscribe();
});

function unavailableLabel(reason: string | null) {
  switch (reason) {
    case 'MEMBERSHIP_REQUIRED':
      return '会员已过期，续费后可重新下载';
    case 'PAPER_UNAVAILABLE':
      return '该试卷已下架';
    case 'FILE_REMOVED':
      return '文件已从试卷中移除';
    case 'FILE_NOT_READY':
      return '文件正在准备中';
    default:
      return '';
  }
}

async function load() {
  const ticket = ++generation;
  error.value = '';
  loggedIn.value = !!session.get();
  if (!loggedIn.value) {
    records.value = [];
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await downloadApi.recent();
    if (ticket === generation) records.value = result;
  } catch (reason) {
    if (ticket === generation) error.value = customerMessage(reason, '下载记录加载失败');
  } finally {
    if (ticket === generation) loading.value = false;
  }
}

async function reopen(record: DownloadRecord) {
  if (files.busy || !record.canDownload) return;
  await controller.open(
    record.paperId,
    {
      id: record.fileId,
      name: record.fileName,
      extension: record.extension,
      mime: record.mime,
      bytes: record.bytes,
    },
    'DOWNLOAD',
  );
}

function openPaper(record: DownloadRecord) {
  if (!record.canDownload) return;
  uni.navigateTo({ url: `/pages/paper/detail?id=${encodeURIComponent(record.paperId)}` });
}

function goPapers() {
  uni.navigateTo({ url: '/pages/paper/list' });
}

function goMembership() {
  uni.navigateTo({ url: '/pages/membership/index' });
}

function login() {
  session.rememberReturnTo('/pages/paper/downloads');
  uni.navigateTo({ url: '/pages/login/index' });
}

onShow(() => {
  void load();
});
onPullDownRefresh(async () => {
  await load();
  uni.stopPullDownRefresh();
});
</script>

<template>
  <view class="ls-mini-page downloads-page ui-page-container">
    <view v-if="!loggedIn" class="empty-card ui-card">
      <UiIcon name="files" tone="blue" :size="40" />
      <text class="empty-title">登录后查看下载记录</text>
      <text class="empty-desc">会员期内下载过的试卷会保存在这里，可随时重新下载</text>
      <button class="ui-btn ui-btn--primary" @click="login">去登录</button>
    </view>

    <template v-else>
      <AsyncState :loading="loading && !records.length" :error="error" @retry="load()" />
      <view v-if="files.error" role="alert" class="ls-alert">
        <text>{{ files.error }}</text>
        <button
          v-if="files.errorCode === 'MEMBERSHIP_EXPIRED' || files.errorCode === 'FORBIDDEN'"
          @click="goMembership"
        >
          去开通会员
        </button>
      </view>

      <view v-if="records.length" class="list-head">
        <text class="list-title">最近下载</text>
        <text class="list-hint">按最近一次打开时间排序</text>
      </view>

      <view
        v-for="record in records"
        :key="`${record.paperId}-${record.fileId}`"
        class="download-card ui-card"
      >
        <view class="record-main">
          <view class="record-title-row" @click="openPaper(record)">
            <text class="record-title">{{ record.paperTitle }}</text>
            <UiIcon v-if="record.canDownload" name="chevron-right" class="svg-arrow" :size="16" />
          </view>
          <text class="record-file">{{ record.fileName }}</text>
          <view class="record-meta">
            <text class="meta-item">
              {{ record.action === 'DOWNLOAD' ? '下载' : '预览' }} ·
              {{ formatShanghaiDateTime(record.lastAt) }}
            </text>
            <text v-if="record.times > 1" class="meta-item">共 {{ record.times }} 次</text>
          </view>
        </view>
        <view class="record-action">
          <button
            v-if="record.canDownload"
            class="ui-btn ui-btn--primary reopen-btn"
            :disabled="files.busy"
            @click="reopen(record)"
          >
            {{ files.busy && files.fileId === record.fileId ? '准备中…' : '重新下载' }}
          </button>
          <text v-else class="record-unavailable">{{
            unavailableLabel(record.unavailableReason)
          }}</text>
        </view>
      </view>

      <view v-if="!loading && !error && !records.length" class="empty-card ui-card">
        <UiIcon name="files" tone="blue" :size="40" />
        <text class="empty-title">还没有下载记录</text>
        <text class="empty-desc">在试卷详情页下载或预览后，这里会显示最近记录</text>
        <button class="ui-btn ui-btn--primary" @click="goPapers">去试卷库</button>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.downloads-page {
  padding: 24rpx 32rpx calc(40rpx + env(safe-area-inset-bottom));
}
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 8rpx 4rpx 16rpx;
}
.list-title {
  font-size: 30rpx;
  font-weight: 700;
}
.list-hint {
  font-size: 22rpx;
  color: $color-text-sub;
}
.download-card {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.record-main {
  flex: 1;
  min-width: 0;
}
.record-title-row {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.record-title {
  font-size: 28rpx;
  font-weight: 600;
  color: $color-text-main;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record-file {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: $color-text-sub;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record-meta {
  display: flex;
  gap: 16rpx;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: $color-text-sub;
}
.record-action {
  flex-shrink: 0;
  max-width: 240rpx;
}
.reopen-btn {
  padding: 10rpx 22rpx;
  font-size: 24rpx;
}
.record-unavailable {
  font-size: 22rpx;
  color: $color-text-sub;
  text-align: right;
}
.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  padding: 64rpx 32rpx;
  text-align: center;
}
.empty-title {
  font-size: 30rpx;
  font-weight: 700;
}
.empty-desc {
  font-size: 24rpx;
  color: $color-text-sub;
}
</style>
