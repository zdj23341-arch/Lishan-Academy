<script setup lang="ts">
import UiIcon from '../../components/ui/UiIcon.vue';
import { ref } from 'vue';
import { onLoad, onReachBottom } from '@dcloudio/uni-app';
import { usePaperList } from '../../composables/usePaperList';
import AsyncState from '../../components/AsyncState.vue';
import AppSearchBar from '../../components/AppSearchBar.vue';
import type { PaperCard } from '../../../../shared/src/content/types';
import { paperTypeLabel } from '../../utils/customer-text';

const keyword = ref('');
const grade = ref('');
const schoolStage = ref('');
const year = ref('');
const subject = ref('');
const paperType = ref('');
const advancedFiltersOpen = ref(false);

const gradeOptions = [
  { label: '全部年级', value: '' },
  { label: '七年级', value: '7' },
  { label: '八年级', value: '8' },
  { label: '九年级(中考)', value: '9' },
];

const schoolStageOptions = [
  { label: '全部学段', value: '' },
  { label: '小学', value: 'PRIMARY' },
  { label: '初中', value: 'JUNIOR' },
  { label: '高中', value: 'SENIOR' },
];
const subjectOptions = [
  { label: '全部学科', value: '' },
  { label: '语文', value: 'CHINESE' },
  { label: '数学', value: 'MATH' },
  { label: '英语', value: 'ENGLISH' },
];
const paperTypeOptions = [
  { label: '全部类型', value: '' },
  { label: '练习', value: 'PRACTICE' },
  { label: '期中期末', value: 'FINAL_EXAM' },
  { label: '真题', value: 'PAST_EXAM' },
];
const yearOptions = [
  { label: '全部年份', value: '' },
  { label: '2026最新', value: '2026' },
  { label: '2025真题', value: '2025' },
  { label: '历年精选', value: '2024' },
];
const schoolStageLabels = schoolStageOptions.map((option) => option.label);
const gradeLabels = gradeOptions.map((option) => option.label);
const yearLabels = yearOptions.map((option) => option.label);
const subjectLabels = subjectOptions.map((option) => option.label);
const paperTypeLabels = paperTypeOptions.map((option) => option.label);
const subjectDisplayLabels: Record<string, string> = {
  CHINESE: '语文',
  MATH: '数学',
  ENGLISH: '英语',
};
type FilterOption = { label: string; value: string };
type SelectorChangeEvent = { detail?: { value?: number | string } };

const { state, reset, more, retry } = usePaperList();

function schoolStageLabel(value: string | null | undefined) {
  if (value === 'PRIMARY') return '小学';
  if (value === 'JUNIOR') return '初中';
  if (value === 'SENIOR') return '高中';
  return value || '学段待确认';
}

function apply() {
  void reset({
    keyword: keyword.value,
    grade: grade.value || undefined,
    schoolStage: schoolStage.value || undefined,
    year: year.value || undefined,
    subject: subject.value || undefined,
    paperType: paperType.value || undefined,
  });
}

function open(id: string) {
  uni.navigateTo({ url: `/pages/paper/detail?id=${encodeURIComponent(id)}` });
}

function selectGrade(val: string) {
  grade.value = val;
  apply();
}

function selectYear(val: string) {
  year.value = val;
  apply();
}

function selectedIndex(options: FilterOption[], value: string) {
  const index = options.findIndex((option) => option.value === value);
  return index < 0 ? 0 : index;
}

function filterLabel(options: FilterOption[], value: string) {
  return options.find((option) => option.value === value)?.label ?? options[0]?.label ?? '';
}

function selectedValue(options: FilterOption[], event: SelectorChangeEvent) {
  const index = Number(event.detail?.value);
  return Number.isInteger(index) && index >= 0 ? (options[index]?.value ?? '') : '';
}

function selectSchoolStage(event: SelectorChangeEvent) {
  schoolStage.value = selectedValue(schoolStageOptions, event);
  apply();
}

function selectGradeOption(event: SelectorChangeEvent) {
  selectGrade(selectedValue(gradeOptions, event));
}

function selectYearOption(event: SelectorChangeEvent) {
  selectYear(selectedValue(yearOptions, event));
}

function selectSubject(event: SelectorChangeEvent) {
  subject.value = selectedValue(subjectOptions, event);
  apply();
}

function selectPaperType(event: SelectorChangeEvent) {
  paperType.value = selectedValue(paperTypeOptions, event);
  apply();
}

function subjectLabel(value: string | null | undefined) {
  if (!value) return '全部学科';
  if (/[\u3400-\u9fff]/.test(value)) return value;
  return subjectDisplayLabels[value.toUpperCase()] ?? '其他学科';
}

function attachmentFormatLabel(value: string) {
  const extension = value.trim().replace(/^\./, '').toUpperCase();
  if (['DOC', 'DOCX'].includes(extension)) return 'Word';
  if (['XLS', 'XLSX'].includes(extension)) return 'Excel';
  if (['JPG', 'JPEG', 'PNG', 'WEBP', 'GIF', 'BMP'].includes(extension)) return '图片';
  return extension || '其他格式';
}

function attachmentSummary(paper: PaperCard) {
  const files = paper.attachments;
  if (files && !files.length) return '暂无附件';
  const formats = paper.attachmentFormats?.length
    ? paper.attachmentFormats
    : (files?.map((file) => file.extension) ?? []);
  if (!formats.length) return files ? '暂无附件' : '附件信息待确认';
  const summary = [...new Set(formats.map(attachmentFormatLabel))].join(' / ');
  return files ? `${summary} · ${files.length}份` : summary;
}

function attachmentAccessLabel(paper: PaperCard) {
  if (paper.resourceStatus === 'NOT_READY') return '资源准备中';
  if (paper.resourceStatus && paper.resourceStatus !== 'READY') return '资源状态待确认';
  if (!paper.attachments) return '附件权限待确认';
  if (!paper.attachments.length) return '暂无可用原文件';
  const canPreview = paper.attachments.some((file) => file.canPreview);
  const canDownload = paper.attachments.some((file) => file.canDownload);
  if (canPreview && canDownload) return '可预览 · 可下载';
  if (canPreview) return '可预览';
  if (canDownload) return '可下载';
  return paper.accessCondition === 'ACTIVE_MEMBER' ? '需有效会员' : '暂无可用原文件';
}

onLoad((options) => {
  keyword.value = options?.keyword ?? '';
  grade.value = options?.grade ?? '';
  schoolStage.value = options?.schoolStage ?? '';
  year.value = options?.year ?? '';
  subject.value = options?.subject ?? '';
  paperType.value = options?.paperType ?? options?.type ?? '';
  advancedFiltersOpen.value = Boolean(subject.value || paperType.value);
  apply();
});

onReachBottom(() => {
  if (state.value.hasMore && !state.value.loading) {
    void more();
  }
});
</script>

<template>
  <view class="ls-mini-page paper-list-page ui-page-container">
    <!-- 头部搜索与筛选胶囊 -->
    <view class="sticky-header">
      <AppSearchBar v-model="keyword" placeholder="搜索试卷名称或关键词" @submit="apply" />

      <!-- 主筛选保持在首屏，低频维度按需展开。 -->
      <view class="filter-capsules-bar">
        <view class="primary-filter-row">
          <picker
            mode="selector"
            aria-label="学段"
            :range="schoolStageLabels"
            :value="selectedIndex(schoolStageOptions, schoolStage)"
            @change="selectSchoolStage"
          >
            <view class="filter-select">
              <text class="filter-select__label">{{
                filterLabel(schoolStageOptions, schoolStage)
              }}</text>
              <UiIcon name="chevron-right" class="filter-chevron" :size="14" />
            </view>
          </picker>
          <picker
            mode="selector"
            aria-label="年级"
            :range="gradeLabels"
            :value="selectedIndex(gradeOptions, grade)"
            @change="selectGradeOption"
          >
            <view class="filter-select">
              <text class="filter-select__label">{{ filterLabel(gradeOptions, grade) }}</text>
              <UiIcon name="chevron-right" class="filter-chevron" :size="14" />
            </view>
          </picker>
          <picker
            mode="selector"
            aria-label="年份"
            :range="yearLabels"
            :value="selectedIndex(yearOptions, year)"
            @change="selectYearOption"
          >
            <view class="filter-select">
              <text class="filter-select__label">{{ filterLabel(yearOptions, year) }}</text>
              <UiIcon name="chevron-right" class="filter-chevron" :size="14" />
            </view>
          </picker>
        </view>

        <view class="filter-meta-row">
          <text class="subject-summary">学科：{{ subjectLabel(subject) }}</text>
          <view class="filter-meta-actions">
            <text class="ui-tag ui-tag--vip">会员原文件</text>
            <button
              class="more-filters-toggle"
              aria-label="更多筛选"
              :aria-expanded="advancedFiltersOpen"
              @click="advancedFiltersOpen = !advancedFiltersOpen"
            >
              {{ advancedFiltersOpen ? '收起筛选' : '更多筛选' }}
            </button>
          </view>
        </view>

        <view v-if="advancedFiltersOpen" class="advanced-filter-row">
          <view class="advanced-filter-item">
            <text class="advanced-filter-label">学科</text>
            <picker
              mode="selector"
              aria-label="学科"
              :range="subjectLabels"
              :value="selectedIndex(subjectOptions, subject)"
              @change="selectSubject"
            >
              <view class="filter-select filter-select--secondary">
                <text class="filter-select__label">{{ filterLabel(subjectOptions, subject) }}</text>
                <UiIcon name="chevron-right" class="filter-chevron" :size="14" />
              </view>
            </picker>
          </view>
          <view class="advanced-filter-item">
            <text class="advanced-filter-label">试卷类型</text>
            <picker
              mode="selector"
              aria-label="试卷类型"
              :range="paperTypeLabels"
              :value="selectedIndex(paperTypeOptions, paperType)"
              @change="selectPaperType"
            >
              <view class="filter-select filter-select--secondary">
                <text class="filter-select__label">{{
                  filterLabel(paperTypeOptions, paperType)
                }}</text>
                <UiIcon name="chevron-right" class="filter-chevron" :size="14" />
              </view>
            </picker>
          </view>
        </view>
      </view>
    </view>

    <!-- 列表展示区 -->
    <view class="paper-list-body">
      <view class="result-stats">
        <text class="stats-text">
          共 <text class="highlight">{{ state.total }}</text> 份符合条件的试卷
        </text>
      </view>

      <AsyncState
        :loading="state.loading && !state.items.length"
        :error="state.error?.message"
        :empty="!state.items.length && !state.loading"
        empty-text="暂无符合条件的试卷资料，换个年级或年份试试"
        @retry="retry"
      />

      <view v-if="state.items.length" class="paper-cards-wrap ui-card">
        <view
          v-for="paper in state.items"
          :key="paper.id"
          class="paper-card"
          @click="open(paper.id)"
        >
          <!-- 左侧文件图标 -->
          <view class="paper-icon-box">
            <UiIcon name="file-text" class="svg-paper" :size="18" />
          </view>

          <!-- 右侧信息 -->
          <view class="paper-info">
            <text class="paper-title text-ellipsis-2">{{ paper.title }}</text>
            <text class="paper-meta">
              {{ schoolStageLabel(paper.schoolStage || paper.stage) }} · {{ paper.grade }}年级 ·
              {{ subjectLabel(paper.subject) }} ·
              {{ paper.year == null ? '年份待确认' : `${paper.year}年` }}
            </text>
            <view class="paper-tags">
              <view class="ui-tag ui-tag--accent">{{ paperTypeLabel(paper.paperType) }}</view>
              <view class="ui-tag ui-tag--vip">{{ attachmentSummary(paper) }}</view>
              <view class="ui-tag ui-tag--primary">{{ attachmentAccessLabel(paper) }}</view>
            </view>
          </view>
          <UiIcon name="chevron-right" class="paper-open-icon" :size="18" />
        </view>
      </view>

      <view v-if="state.items.length" class="paper-access-note">
        试卷摘要免费浏览，原文件预览与下载需要有效会员
      </view>

      <!-- 触底状态 -->
      <view v-if="state.items.length" class="load-more-status">
        <text v-if="state.loading" class="tip-txt">正在载入更多试卷…</text>
        <text v-else-if="state.hasMore" class="tip-txt tip-txt--btn" @click="more"
          >点击加载更多</text
        >
        <text v-else class="tip-txt">—— 已展示全部试卷资料 ——</text>
      </view>
    </view>
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.paper-list-page {
  background-color: $color-bg-page;
}

.sticky-header {
  position: sticky;
  top: 0;
  z-index: 90;
  background-color: #ffffff;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.04);
}

.filter-capsules-bar {
  padding: 12rpx 24rpx 16rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.primary-filter-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
}

.filter-select {
  min-width: 0;
  min-height: 88rpx;
  padding: 0 14rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
  border: 1rpx solid #e5ebf3;
  border-radius: $radius-sm;
  background: #ffffff;

  &--secondary {
    background: #f8faff;
  }
}

.filter-select__label {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 23rpx;
  color: $color-text-main;
}

.filter-chevron {
  transform: rotate(90deg);
}

.filter-meta-row,
.filter-meta-actions {
  display: flex;
  align-items: center;
}

.filter-meta-row {
  min-height: 56rpx;
  justify-content: space-between;
  gap: 12rpx;
}

.subject-summary {
  min-width: 0;
  color: $color-text-secondary;
  font-size: 24rpx;
}

.filter-meta-actions {
  flex-shrink: 0;
  gap: 8rpx;
}

.more-filters-toggle {
  min-height: 88rpx;
  margin: 0;
  padding: 0 8rpx;
  border: 0;
  background: transparent;
  color: $color-primary;
  font-size: 24rpx;
  line-height: 1.2;

  &::after {
    border: 0;
  }
}

.advanced-filter-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
  padding: 4rpx 0 8rpx;
}

.advanced-filter-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.advanced-filter-label {
  color: $color-text-secondary;
  font-size: 22rpx;
}

.paper-list-body {
  padding: 8px 16px 20px;
}

.result-stats {
  padding: 8rpx 4rpx 16rpx;

  .stats-text {
    font-size: 24rpx;
    color: $color-text-secondary;

    .highlight {
      color: $color-primary;
      font-weight: 700;
      margin: 0 4rpx;
    }
  }
}

.paper-cards-wrap {
  display: flex;
  flex-direction: column;
  padding: 16px;
  margin-bottom: 16px;
  border-radius: 13px;
}

.paper-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 90px;
  padding: 14px 0;
  border-bottom: 1px solid $color-border;
  cursor: pointer;
  transition: $transition-fast;

  &:last-child {
    border-bottom: 0;
  }

  &:active {
    opacity: 0.92;
  }

  .paper-icon-box {
    width: 40px;
    height: 48px;
    border-radius: $radius-sm;
    background: #edf3ff;
    color: $color-primary;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;

    .svg-paper {
      width: 48rpx;
      height: 48rpx;
    }
  }

  .paper-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 10rpx;

    .paper-title {
      font-size: 28rpx;
      font-weight: 600;
      color: $color-text-main;
      line-height: 1.35;
    }

    .paper-meta {
      color: $color-text-secondary;
      font-size: 23rpx;
      line-height: 1.4;
    }

    .paper-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 8rpx;
    }
  }
}

.paper-open-icon {
  flex-shrink: 0;
  color: $color-text-muted;
}

.paper-access-note {
  margin: 22rpx 0 4rpx;
  color: $color-text-secondary;
  font-size: 22rpx;
  line-height: 1.5;
  text-align: center;
}

.load-more-status {
  padding: 36rpx 0;
  text-align: center;

  .tip-txt {
    font-size: 24rpx;
    color: $color-text-muted;

    &--btn {
      color: $color-primary;
      cursor: pointer;
    }
  }
}
</style>
