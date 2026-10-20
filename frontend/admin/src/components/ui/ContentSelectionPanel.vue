<script setup lang="ts">
import { computed } from 'vue';
import UiButton from './UiButton.vue';
import UiState from './UiState.vue';
import UiStatusBadge from './UiStatusBadge.vue';

export type SelectionItem = {
  key: string;
  title: string;
  typeLabel: string;
  statusLabel: string;
  coverUrl?: string | null;
  available?: boolean;
  unavailableReason?: string | null;
};

const props = withDefaults(
  defineProps<{
    items: SelectionItem[];
    selected: SelectionItem[];
    total: number;
    page?: number;
    pageSize?: number;
    keyword?: string;
    loading?: boolean;
    busy?: boolean;
    error?: string;
    emptyTitle?: string;
  }>(),
  {
    page: 1,
    pageSize: 10,
    keyword: '',
    loading: false,
    busy: false,
    error: '',
    emptyTitle: '没有找到内容',
  },
);
const emit = defineEmits<{
  search: [keyword: string];
  page: [page: number];
  toggle: [key: string, checked: boolean];
  move: [from: number, to: number];
  remove: [key: string];
  retry: [];
}>();
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));
function search(event: Event) {
  if (props.busy) return;
  const input = (event.currentTarget as HTMLFormElement).elements.namedItem(
    'keyword',
  ) as HTMLInputElement;
  emit('search', input.value.trim());
}
function statusTone(item: SelectionItem) {
  if (item.available === false) return 'danger';
  if (/草稿|处理中|审核/.test(item.statusLabel)) return 'warning';
  if (/上架|发布|就绪/.test(item.statusLabel)) return 'success';
  return 'neutral';
}
</script>

<template>
  <section class="ls-selection" aria-label="内容选择">
    <form class="ls-selection-search" role="search" @submit.prevent="search">
      <label
        >按名称搜索
        <input
          name="keyword"
          type="search"
          :value="keyword"
          placeholder="输入课程、图书或试卷名称"
          :disabled="busy"
      /></label>
      <UiButton type="submit" kind="secondary" :disabled="busy">搜索</UiButton>
    </form>

    <div class="ls-selection-layout">
      <div class="ls-selection-results" :aria-busy="loading">
        <UiState
          v-if="loading"
          kind="loading"
          title="正在查找内容"
          description="请稍候，搜索结果会显示在这里。"
        />
        <UiState
          v-else-if="error"
          kind="error"
          title="内容暂时无法读取"
          :description="error"
          action-label="重新加载"
          @action="emit('retry')"
        />
        <UiState
          v-else-if="!items.length"
          kind="empty"
          :title="emptyTitle"
          description="换个名称试试，或清除搜索条件。"
        />
        <ul v-else class="ls-selection-list">
          <li
            v-for="item in items"
            :key="item.key"
            class="ls-selection-option"
            :class="{ 'is-unavailable': item.available === false }"
          >
            <input
              type="checkbox"
              :aria-label="`选择${item.title}`"
              :checked="selected.some((value) => value.key === item.key)"
              :disabled="busy || item.available === false"
              @change="emit('toggle', item.key, ($event.target as HTMLInputElement).checked)"
            />
            <img
              v-if="item.coverUrl"
              :src="item.coverUrl"
              :alt="`${item.title}封面`"
              loading="lazy"
            />
            <span v-else class="ls-selection-cover" aria-hidden="true">{{ item.typeLabel }}</span>
            <span class="ls-selection-meta"
              ><strong>{{ item.title }}</strong
              ><span
                ><span>{{ item.typeLabel }}</span
                ><UiStatusBadge :label="item.statusLabel" :tone="statusTone(item)" /></span
              ><small v-if="item.unavailableReason" class="ls-selection-unavailable">{{
                item.unavailableReason
              }}</small></span
            >
          </li>
        </ul>
        <nav v-if="!loading && !error && total" class="ls-selection-pages" aria-label="内容分页">
          <span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span>
          <UiButton
            kind="secondary"
            :disabled="busy || page <= 1"
            aria-label="上一页内容"
            @click="emit('page', page - 1)"
            >上一页</UiButton
          >
          <UiButton
            kind="secondary"
            :disabled="busy || page >= pages"
            aria-label="下一页内容"
            @click="emit('page', page + 1)"
            >下一页</UiButton
          >
        </nav>
      </div>

      <aside class="ls-selection-chosen" aria-label="已选内容及顺序">
        <header>
          <strong>已选内容</strong><span>{{ selected.length }} 项</span>
        </header>
        <p v-if="!selected.length" class="ls-selection-hint">
          选择内容后会在这里排列，顺序就是首页展示顺序。
        </p>
        <ol v-else>
          <li
            v-for="(item, index) in selected"
            :key="item.key"
            :class="{ 'is-unavailable': item.available === false }"
          >
            <span class="ls-selection-order">{{ index + 1 }}</span>
            <span class="ls-selection-chosen-title"
              ><strong>{{ item.title }}</strong
              ><small v-if="item.available === false">{{
                item.unavailableReason || '当前内容不可用，请替换'
              }}</small></span
            >
            <span class="ls-selection-controls">
              <button
                type="button"
                :aria-label="`上移${item.title}`"
                :disabled="busy || index === 0"
                @click="emit('move', index, index - 1)"
              >
                上移
              </button>
              <button
                type="button"
                :aria-label="`下移${item.title}`"
                :disabled="busy || index === selected.length - 1"
                @click="emit('move', index, index + 1)"
              >
                下移
              </button>
              <button
                type="button"
                :aria-label="`移除${item.title}`"
                :disabled="busy"
                @click="emit('remove', item.key)"
              >
                移除
              </button>
            </span>
          </li>
        </ol>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.ls-selection {
  min-width: 0;
  color: #24334b;
}
.ls-selection-search {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.ls-selection-search label {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  font-weight: 600;
  white-space: nowrap;
}
.ls-selection-search input {
  flex: 1;
  min-width: 0;
  height: 40px;
  padding: 0 12px;
  border: 1px solid #cbd5e3;
  border-radius: 6px;
  font: inherit;
}
.ls-selection-search input:focus-visible,
.ls-selection button:focus-visible,
.ls-selection input[type='checkbox']:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
.ls-selection-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(240px, 0.85fr);
  gap: 16px;
  align-items: start;
}
.ls-selection-results,
.ls-selection-chosen {
  min-width: 0;
  border: 1px solid #e1e8f1;
  border-radius: 9px;
  background: #fff;
}
.ls-selection-results {
  padding: 12px;
}
.ls-selection-list,
.ls-selection-chosen ol {
  list-style: none;
  padding: 0;
  margin: 0;
}
.ls-selection-option {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 76px;
  padding: 10px 6px;
  border-bottom: 1px solid #edf1f6;
}
.ls-selection-option:last-child {
  border-bottom: 0;
}
.ls-selection-option input {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
}
.ls-selection-option img,
.ls-selection-cover {
  width: 56px;
  height: 56px;
  flex: 0 0 auto;
  border-radius: 5px;
  object-fit: cover;
}
.ls-selection-cover {
  display: grid;
  place-items: center;
  background: #edf3fd;
  color: #2855b7;
  font-size: 12px;
}
.ls-selection-meta {
  display: grid;
  min-width: 0;
  gap: 7px;
}
.ls-selection-meta > strong,
.ls-selection-chosen-title strong {
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.ls-selection-meta > span {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #596981;
  font-size: 13px;
}
.ls-selection-unavailable,
.ls-selection-chosen-title small {
  color: #b5473a;
  line-height: 1.4;
}
.is-unavailable {
  background: #fff9f7;
}
.ls-selection-pages {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 12px;
  color: #596981;
  font-size: 13px;
}
.ls-selection-pages > span {
  margin-right: auto;
}
.ls-selection-chosen {
  overflow: hidden;
}
.ls-selection-chosen header {
  display: flex;
  justify-content: space-between;
  padding: 14px;
  background: #f4f7fc;
  border-bottom: 1px solid #e1e8f1;
}
.ls-selection-chosen header span,
.ls-selection-hint {
  color: #596981;
  font-size: 13px;
}
.ls-selection-hint {
  padding: 0 14px;
  line-height: 1.6;
}
.ls-selection-chosen li {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid #edf1f6;
}
.ls-selection-order {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #edf3fd;
  color: #2855b7;
  font-size: 12px;
}
.ls-selection-chosen-title {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.ls-selection-controls {
  grid-column: 2;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.ls-selection-controls button {
  min-height: 32px;
  padding: 4px 8px;
  color: #2855b7;
  background: #fff;
  border: 1px solid #d8e2f0;
  border-radius: 5px;
  cursor: pointer;
}
.ls-selection-controls button:disabled {
  color: #8793a4;
  cursor: not-allowed;
}
@media (max-width: 760px) {
  .ls-selection-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .ls-selection-search {
    align-items: stretch;
    flex-wrap: wrap;
  }
  .ls-selection-search label {
    flex-basis: 100%;
  }
}
</style>
