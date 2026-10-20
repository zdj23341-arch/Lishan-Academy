<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import { ElDialog } from 'element-plus';
import type { ProductType } from '@jxsc/shared';
import type { ContentKind, ContentOption, ContentSelectionSource } from '../api/content-selection';

const props = defineProps<{
  modelValue: string[];
  kind: ContentKind;
  single?: boolean;
  disabled?: boolean;
  source?: ContentSelectionSource;
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();
const opened = ref(false),
  loading = ref(false),
  error = ref(''),
  keyword = ref('');
const page = ref(1),
  total = ref(0),
  pageSize = ref(10);
const type = ref<ProductType | ''>('');
const rows = ref<ContentOption[]>([]),
  selected = ref<string[]>([]);
const known = ref<Record<string, ContentOption>>({});
let sequence = 0,
  resolving = 0;
const defaultSource: ContentSelectionSource = {
  search: async (...args) =>
    (await import('../api/content-selection')).contentSelectionSource.search(...args),
  resolve: async (...args) =>
    (await import('../api/content-selection')).contentSelectionSource.resolve(...args),
};
const source = () => props.source ?? defaultSource;
function unavailable(id: string): ContentOption {
  return {
    id,
    title: '原关联内容暂不可读',
    kind: props.kind,
    status: '待核对',
    available: false,
    reason: '内容可能已删除、无权限或网络异常；可重试或移除',
    coverUrl: null,
  };
}
async function resolve(ids: string[]) {
  const ticket = ++resolving;
  const entries = await Promise.all(
    ids.map(async (id) => {
      try {
        return [id, await source().resolve(props.kind, id)] as const;
      } catch {
        return [id, unavailable(id)] as const;
      }
    }),
  );
  if (ticket === resolving) known.value = { ...known.value, ...Object.fromEntries(entries) };
}
watch(
  () => [props.kind, ...props.modelValue],
  () => {
    cancel();
    known.value = {};
    void resolve(props.modelValue);
  },
  { immediate: true },
);
async function load(next = page.value) {
  const ticket = ++sequence;
  loading.value = true;
  error.value = '';
  page.value = next;
  rows.value = [];
  try {
    const query = {
      keyword: keyword.value.trim(),
      page: next,
      pageSize: 10,
      ...(type.value ? { type: type.value } : {}),
    };
    const result = await source().search(props.kind, query);
    if (ticket !== sequence || !opened.value) return;
    if (
      !Array.isArray(result.items) ||
      !Number.isInteger(result.total) ||
      result.total < 0 ||
      result.page !== next ||
      result.pageSize < 1
    )
      throw new Error('候选内容分页异常，请重试');
    rows.value = result.items;
    total.value = result.total;
    pageSize.value = result.pageSize;
    for (const item of result.items) known.value[item.id] = item;
  } catch (cause) {
    if (ticket === sequence)
      error.value = cause instanceof Error ? cause.message : '内容读取失败，请重试';
  } finally {
    if (ticket === sequence) loading.value = false;
  }
}
function open() {
  if (props.disabled) return;
  selected.value = [...new Set(props.modelValue)];
  keyword.value = '';
  type.value = '';
  opened.value = true;
  void resolve(selected.value);
  void load(1);
}
function cancel() {
  opened.value = false;
  sequence++;
  loading.value = false;
}
function toggle(item: ContentOption, event: Event) {
  if (!item.available || props.disabled) return;
  if ((event.target as HTMLInputElement).checked)
    selected.value = props.single ? [item.id] : [...new Set([...selected.value, item.id])];
  else selected.value = selected.value.filter((id) => id !== item.id);
}
function move(index: number, delta: number) {
  const next = index + delta;
  if (next < 0 || next >= selected.value.length) return;
  const copy = [...selected.value];
  [copy[index], copy[next]] = [copy[next]!, copy[index]!];
  selected.value = copy;
}
function confirm() {
  if (props.disabled || loading.value) return;
  emit('update:modelValue', [...selected.value]);
  cancel();
}
onUnmounted(() => {
  sequence++;
  resolving++;
});
</script>
<template>
  <div class="content-picker">
    <ul v-if="modelValue.length" class="content-picker__summary">
      <li v-for="id in modelValue" :key="id">
        {{ known[id]?.title ?? '正在读取关联内容…' }}
        <span v-if="known[id] && !known[id]!.available" role="status">
          — {{ known[id]!.reason }}</span
        >
      </li>
    </ul>
    <button type="button" :disabled="disabled" @click="open">选择内容</button>
    <el-dialog
      :model-value="opened"
      title="选择首页内容"
      width="760px"
      class="ls-content-dialog"
      append-to-body
      @close="cancel"
    >
      <form @submit.prevent="load(1)">
        <label
          >按名称搜索 <input v-model="keyword" type="search" maxlength="200" :disabled="disabled"
        /></label>
        <label v-if="kind === 'PRODUCT'"
          >商品类型
          <select v-model="type" @change="load(1)">
            <option value="">全部</option>
            <option value="BOOK">图书</option>
            <option value="COURSE">课程</option>
            <option value="MEMBERSHIP">会员</option>
          </select></label
        >
        <button type="submit" :disabled="disabled">搜索</button>
      </form>
      <p v-if="loading" role="status">正在读取内容…</p>
      <p v-if="error" role="alert">
        {{ error }} <button type="button" @click="load()">重试</button>
      </p>
      <template v-if="!loading && !error">
        <p>共 {{ total }} 条可选内容</p>
        <ul class="content-picker__results">
          <li v-for="item in rows" :key="item.id">
            <label>
              <input
                type="checkbox"
                :checked="selected.includes(item.id)"
                :disabled="disabled || !item.available"
                @change="toggle(item, $event)"
              />
              <img v-if="item.coverUrl" :src="item.coverUrl" alt="" loading="lazy" />
              <span
                >{{ item.title
                }}<small
                  >{{
                    {
                      BOOK: '图书',
                      COURSE: '课程',
                      MEMBERSHIP: '会员',
                      PAPER: '试卷',
                      ARTICLE: '图文',
                      COURSE_DETAIL: '课程',
                    }[item.kind] ?? '商品'
                  }}
                  · {{ item.status }}</small
                ><small v-if="item.reason">{{ item.reason }}</small></span
              >
            </label>
          </li>
        </ul>
        <p v-if="!rows.length">暂无匹配内容，请更换关键词或先发布内容。</p>
        <nav aria-label="候选内容分页" class="content-picker__pagination">
          <button type="button" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
          <span>{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</span>
          <button type="button" :disabled="page * pageSize >= total" @click="load(page + 1)">
            下一页
          </button>
        </nav>
      </template>
      <h3>已选 {{ selected.length }} 项</h3>
      <p>确认后才更新当前表单；保存草稿并发布首页后生效。</p>
      <ol class="content-picker__selected">
        <li v-for="(id, index) in selected" :key="id">
          <span
            >{{ known[id]?.title ?? '正在读取…'
            }}<small v-if="known[id]?.reason">{{ known[id]?.reason }}</small></span
          >
          <button
            type="button"
            :aria-label="`上移${known[id]?.title}`"
            :disabled="index === 0"
            @click="move(index, -1)"
          >
            上移
          </button>
          <button
            type="button"
            :aria-label="`下移${known[id]?.title}`"
            :disabled="index === selected.length - 1"
            @click="move(index, 1)"
          >
            下移
          </button>
          <button
            type="button"
            :aria-label="`移除${known[id]?.title}`"
            @click="selected = selected.filter((value) => value !== id)"
          >
            移除
          </button>
        </li>
      </ol>
      <template #footer
        ><button type="button" @click="cancel">取消</button
        ><button type="button" :disabled="disabled || loading" @click="confirm">
          确认选择
        </button></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.content-picker {
  min-width: 0;
  width: 100%;
}
.content-picker > button {
  min-width: 104px;
  padding: 6px 14px;
  border: 1px solid #cbd8eb;
  border-radius: 6px;
  background: #fff;
  color: #2855b7;
  font-weight: 600;
}
.content-picker__summary {
  display: grid;
  gap: 6px;
  margin: 0 0 10px;
  padding: 0;
  list-style: none;
  overflow-wrap: anywhere;
}
.content-picker__summary li {
  padding: 8px 10px;
  border: 1px solid #e1e8f1;
  border-radius: 6px;
  background: #f8faff;
  color: #344968;
  font-size: 13px;
}
.content-picker__results {
  list-style: none;
  padding: 0;
}
.content-picker__results li {
  padding: 12px 8px;
  border-bottom: 1px solid var(--ls-line, #ddd);
}
.content-picker__results label,
.content-picker__selected li,
.content-picker__pagination {
  display: flex;
  align-items: center;
  gap: 12px;
}
.content-picker__results img {
  width: 56px;
  height: 56px;
  object-fit: cover;
}
.content-picker__results span,
.content-picker__selected span {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.content-picker__selected {
  padding-left: 20px;
}
.content-picker__selected li {
  padding: 8px 0;
  flex-wrap: wrap;
}
.content-picker__pagination {
  justify-content: center;
  flex-wrap: wrap;
  padding: 12px 0;
}
small {
  display: block;
}
form {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
form label {
  display: grid;
  gap: 6px;
  color: #344968;
  font-size: 13px;
}
form input[type='search'],
form select {
  min-height: 36px;
  padding: 0 9px;
  border: 1px solid #cbd5e3;
  border-radius: 5px;
  font: inherit;
}
input[type='search'] {
  max-width: 100%;
}
button {
  cursor: pointer;
  min-height: 36px;
}
button:disabled {
  cursor: default;
}
button:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
</style>
<style>
.ls-content-dialog {
  max-width: calc(100vw - 32px);
}
.ls-content-dialog .el-dialog__body {
  max-height: min(64vh, 640px);
  overflow: auto;
}
</style>
