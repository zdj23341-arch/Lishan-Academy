<script setup lang="ts">
import { ref } from 'vue';
import ContentSelectionPanel, { type SelectionItem } from './ContentSelectionPanel.vue';
import HomeDisplayMode from './HomeDisplayMode.vue';
import CourseFlowSteps from './CourseFlowSteps.vue';
import UploadFileStatus from './UploadFileStatus.vue';
import UiStatusBadge from './UiStatusBadge.vue';

const mode = ref<'automatic' | 'manual'>('manual');
const count = ref(4);
const state = ref('normal');
const keyword = ref('');
const current = ref<'basics' | 'chapters' | 'preview' | 'publish'>('chapters');
const items: SelectionItem[] = [
  {
    key: 'sample:1',
    title: '初中语文 · 阅读与表达提升课',
    typeLabel: '课程',
    statusLabel: '已上架',
  },
  {
    key: 'sample:2',
    title: '七年级语文期末综合复习：阅读理解与写作专项训练（长名称示例）',
    typeLabel: '课程',
    statusLabel: '已上架',
  },
  {
    key: 'sample:3',
    title: '古诗词阅读入门',
    typeLabel: '课程',
    statusLabel: '已下架',
    available: false,
    unavailableReason: '内容已下架，公开首页会跳过，请替换或移除。',
  },
];
const selected = ref<SelectionItem[]>(items.filter((_, index) => index !== 1));
function toggle(key: string, checked: boolean) {
  if (checked && !selected.value.some((item) => item.key === key))
    selected.value.push(items.find((item) => item.key === key)!);
  if (!checked) selected.value = selected.value.filter((item) => item.key !== key);
}
function move(from: number, to: number) {
  const list = [...selected.value];
  list.splice(to, 0, ...list.splice(from, 1));
  selected.value = list;
}
</script>
<template>
  <main class="ls-operation-showcase">
    <header>
      <div>
        <p class="ls-operation-eyebrow">力山学堂 · 后台操作简化</p>
        <h1>内容发布与首页推荐</h1>
        <p>UI 交接样板 · 示例数据，仅演示界面状态，不会保存或发布业务内容。</p>
      </div>
      <UiStatusBadge label="ADM-U01" tone="blue" />
    </header>
    <section>
      <h2>首页栏目</h2>
      <HomeDisplayMode v-model="mode" v-model:count="count" :busy="state === 'busy'" />
    </section>
    <section>
      <div class="ls-operation-section-title">
        <div>
          <h2>按名称选择内容</h2>
          <p>已选内容保留在右侧，可使用上移、下移调整顺序。</p>
        </div>
        <label
          >查看状态<select v-model="state">
            <option value="normal">正常</option>
            <option value="loading">加载中</option>
            <option value="empty">空结果</option>
            <option value="error">加载失败</option>
            <option value="conflict">保存冲突</option>
            <option value="busy">保存中</option>
          </select></label
        >
      </div>
      <p v-if="state === 'conflict'" class="ls-operation-alert" role="alert">
        其他管理员已更新草稿。你的修改仍保留，请先核对最新内容再保存。
      </p>
      <ContentSelectionPanel
        :items="state === 'empty' ? [] : items.filter((item) => item.title.includes(keyword))"
        :selected="selected"
        :total="state === 'empty' ? 0 : items.filter((item) => item.title.includes(keyword)).length"
        :keyword="keyword"
        :loading="state === 'loading'"
        :busy="state === 'busy'"
        :error="state === 'error' ? '网络暂不可用，已选内容仍保留。' : ''"
        @search="keyword = $event"
        @toggle="toggle"
        @move="move"
        @remove="selected = selected.filter((item) => item.key !== $event)"
        @retry="state = 'normal'"
      />
    </section>
    <section>
      <h2>连续创建课程</h2>
      <CourseFlowSteps
        :current="current"
        :completed="['basics']"
        :locked="['publish']"
        @select="current = $event as typeof current"
      />
      <p class="ls-operation-help">观看有效期是购买后可观看的天数；视频时长在章节内分别显示。</p>
    </section>
    <section>
      <h2>文件与上传反馈</h2>
      <div class="ls-operation-files">
        <UploadFileStatus name="第一讲 · 阅读方法.mp4" state="uploading" /><UploadFileStatus
          name="第二讲 · 写作技巧.mp4"
          state="processing"
        /><UploadFileStatus name="七年级语文期末试卷.pdf" state="ready" /><UploadFileStatus
          name="综合复习课 · 名称较长的文件在窄屏仍然完整可读.docx"
          state="failed"
          reason="网络中断。检查网络后可重试，已填写内容会保留。"
          can-retry
        /><UploadFileStatus name="原试卷附件.pdf" state="disabled" />
      </div>
      <p class="ls-operation-help">试卷介绍可公开浏览；私有原始附件仍按有效会员权限下载。</p>
    </section>
  </main>
</template>
<style scoped>
.ls-operation-showcase {
  max-width: 1160px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}
.ls-operation-showcase > header,
.ls-operation-section-title {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.ls-operation-showcase h1 {
  margin: 6px 0 12px;
  font-size: 26px;
  color: #24334b;
}
.ls-operation-showcase h2 {
  font-size: 17px;
  margin: 0 0 16px;
  color: #24334b;
}
.ls-operation-showcase p {
  font-size: 14px;
  line-height: 1.6;
  color: #596981;
}
.ls-operation-eyebrow {
  color: #2855b7 !important;
  margin: 0;
}
.ls-operation-showcase > section {
  margin-top: 28px;
  padding: 24px;
  background: #fff;
  border: 1px solid #e1e8f1;
  border-radius: 12px;
}
.ls-operation-section-title label {
  display: grid;
  gap: 6px;
  flex: 0 0 140px;
  font-size: 13px;
  color: #596981;
}
.ls-operation-section-title select {
  height: 36px;
  padding: 0 8px;
  border: 1px solid #cbd5e3;
  border-radius: 5px;
  background: #fff;
}
.ls-operation-files {
  display: grid;
  gap: 12px;
}
.ls-operation-help {
  margin: 14px 0 0;
}
.ls-operation-alert {
  padding: 12px;
  background: #fff5f1;
  border-left: 3px solid #b5473a;
}
@media (max-width: 760px) {
  .ls-operation-showcase {
    padding: 20px 16px 40px;
  }
  .ls-operation-showcase > section {
    padding: 16px;
  }
  .ls-operation-section-title {
    flex-wrap: wrap;
  }
}
</style>
