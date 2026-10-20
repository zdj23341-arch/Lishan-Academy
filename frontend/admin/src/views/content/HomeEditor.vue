<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElButton, ElInput, ElAlert, ElTag } from 'element-plus';
import { ApiError, type HomeView, type Target } from '@jxsc/shared';
import type { HomeDraft, HomeSectionInput } from '@jxsc/shared/content/admin-types';
import { moveItem, validateHomeDraft, validateHomePublish } from '@jxsc/shared/content/validation';
import { contentApi } from '../../api/content';
import InternalTargetEditor from '../../components/InternalTargetEditor.vue';
import AssetPicker from '../../components/AssetPicker.vue';
import ContentPicker from '../../components/ContentPicker.vue';
import HomeDisplayMode from '../../components/ui/HomeDisplayMode.vue';

const draft = ref<HomeDraft | null>(null);
const editing = ref(new Set<string>());
const expandedGroups = ref(new Set<'banners' | 'shortcuts'>());
function toggleGroup(group: 'banners' | 'shortcuts') {
  if (busy.value) return;
  if (expandedGroups.value.has(group)) expandedGroups.value.delete(group);
  else expandedGroups.value.add(group);
}
function toggleEdit(group: Group, id: string) {
  if (busy.value) return;
  const key = `${group}:${id}`;
  if (editing.value.has(key)) editing.value.delete(key);
  else editing.value.add(key);
}
const targetLabels: Record<string, string> = {
  PRODUCT_LIST: '商品列表',
  PRODUCT: '商品详情',
  COURSE_DETAIL: '课程详情',
  ARTICLE: '图文详情',
  MEMBERSHIP: '会员中心',
  MEMBERSHIP_CENTER: '会员中心',
  PAPER_LIST: '试卷列表',
  LEARNING: '我的学习',
  ORDER_LIST: '我的订单',
  LOGISTICS: '订单物流',
};
function targetLabel(target: Target | null) {
  return target ? targetLabels[target.type] || '站内页面' : '未设置';
}
const preview = ref<HomeView | null>(null);
const previewRows = ref<{ section: string; title: string; reason: string }[]>([]);
async function explainPreview(view: HomeView, source: HomeDraft) {
  const { contentSelectionSource } = await import('../../api/content-selection');
  const rows = await Promise.all(
    source.sections.map(async (section) => {
      const resolved = view.sections.find((value) => value.id === section.id);
      const visible = [
        ...(resolved?.products ?? []),
        ...(resolved?.papers ?? []),
        ...(resolved?.article ? [resolved.article] : []),
      ];
      const ids =
        section.selectionMode === 'AUTO'
          ? (section.pinnedIds ?? [])
          : section.type === 'PAPER_GRID'
            ? (section.paperIds ?? [])
            : section.type === 'ARTICLE_CARD'
              ? section.articleId
                ? [section.articleId]
                : []
              : section.productIds;
      const kind =
        section.type === 'PAPER_GRID'
          ? 'PAPER'
          : section.type === 'BOOK_GRID'
            ? 'BOOK'
            : section.type === 'COURSE_GRID'
              ? 'COURSE'
              : section.type === 'ARTICLE_CARD'
                ? 'ARTICLE'
                : 'PRODUCT';
      const configured = await Promise.all(
        ids.map(async (id) => {
          const shown = visible.find((value) => value.id === id);
          if (shown) return { section: section.title, title: shown.title, reason: '已展示' };
          try {
            const option = await contentSelectionSource.resolve(kind, id);
            return {
              section: section.title,
              title: option.title,
              reason: !section.enabled
                ? '栏目未启用'
                : !option.available
                  ? option.reason || '内容当前不可用'
                  : section.selectionMode === 'AUTO' &&
                      visible.length >= (section.displayLimit ?? 4)
                    ? `未进入本次预览的前 ${section.displayLimit ?? 4} 条`
                    : '未纳入服务端预览，具体原因待后端核对',
            };
          } catch {
            return {
              section: section.title,
              title: '原关联内容暂不可读',
              reason: !section.enabled
                ? '栏目未启用'
                : '状态读取失败，可能无权限、已删除或网络异常；请重试预览',
            };
          }
        }),
      );
      return [
        ...configured,
        ...visible
          .filter((value) => !ids.includes(value.id))
          .map((value) => ({
            section: section.title,
            title: value.title,
            reason: '已展示（自动补入）',
          })),
      ];
    }),
  );
  previewRows.value = rows.flat();
}
const saved = ref('');
const requesting = ref(false);
const uploads = ref(new Set<string>());
const busy = computed(() => requesting.value || uploads.value.size > 0);

function uploadBusy(id: string, value: boolean) {
  if (value) uploads.value.add(id);
  else uploads.value.delete(id);
}

const message = ref('');
const error = ref('');
const reason = ref('');
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== saved.value);
const defaultTarget = (): Target => ({ type: 'PRODUCT_LIST', id: null, filters: {} });

let nextId = 0;
const id = () => `home-${Date.now()}-${++nextId}`;

async function run(action: () => Promise<void>) {
  if (busy.value) return;
  requesting.value = true;
  error.value = '';
  message.value = '';
  try {
    await action();
  } catch (cause) {
    error.value =
      cause instanceof ApiError
        ? [cause.message, ...Object.values(cause.fieldErrors)].join('；')
        : cause instanceof Error
          ? cause.message
          : '操作失败';
  } finally {
    requesting.value = false;
  }
}

function accept(value: HomeDraft) {
  draft.value = value;
  saved.value = JSON.stringify(value);
}

async function load() {
  await run(async () => {
    const value = await contentApi.homeDraft();
    accept(value);
    preview.value = null;
    previewRows.value = [];
    message.value = '已读取草稿';
  });
}

async function save() {
  await run(async () => {
    if (!draft.value) return;
    const errors = validateHomeDraft(draft.value);
    if (Object.keys(errors).length) throw new Error(Object.values(errors).join('；'));
    accept(await contentApi.saveHome(JSON.parse(JSON.stringify(draft.value))));
    preview.value = null;
    previewRows.value = [];
    message.value = '草稿已保存，公开首页尚未更新';
  });
}

async function showPreview() {
  await run(async () => {
    preview.value = await contentApi.previewHome();
    if (draft.value && preview.value.revision === draft.value.revision)
      await explainPreview(preview.value, JSON.parse(saved.value) as HomeDraft);
    else previewRows.value = [];
    message.value = dirty.value
      ? '当前预览仅包含已保存内容，未保存修改不在预览中'
      : '已显示保存草稿的预览';
  });
}

async function publish() {
  if (!draft.value) return;
  if (dirty.value) {
    error.value = '请先保存草稿再发布';
    return;
  }
  const errors = validateHomePublish(draft.value);
  if (!reason.value.trim()) errors.reason = '请填写发布原因';
  if (Object.keys(errors).length) {
    error.value = Object.values(errors).join('；');
    return;
  }
  await run(async () => {
    const result = await contentApi.publishHome(draft.value!.revision, reason.value.trim());
    accept({ ...draft.value!, revision: result.revision });
    preview.value = result;
    previewRows.value = [];
    message.value = `首页版本 ${result.revision} 已发布`;
  });
}

function add(group: 'banners' | 'shortcuts' | 'sections') {
  if (busy.value || !draft.value) return;
  if (group !== 'sections') expandedGroups.value.add(group);
  const base = { id: id(), enabled: true, sortNo: draft.value[group].length };
  editing.value.add(`${group}:${base.id}`);
  if (group === 'banners')
    draft.value.banners.push({ ...base, title: '', assetId: '', target: defaultTarget() });
  else if (group === 'shortcuts')
    draft.value.shortcuts.push({ ...base, label: '', assetId: '', target: defaultTarget() });
  else
    draft.value.sections.push({
      ...base,
      title: '',
      type: 'PRODUCT_GRID',
      moreTarget: null,
      productIds: [],
      articleId: null,
      paperIds: [],
    });
}

type Group = 'banners' | 'shortcuts' | 'sections';

function setMode(item: HomeSectionInput, mode: 'automatic' | 'manual') {
  const automatic = mode === 'automatic';
  item.selectionMode = automatic ? 'AUTO' : 'MANUAL';
  item.displayLimit ??= 4;
  if (!automatic) item.pinnedIds = [];
}

function setSectionType(item: HomeSectionInput) {
  if (!['BOOK_GRID', 'COURSE_GRID', 'PAPER_GRID'].includes(item.type)) {
    item.selectionMode = 'MANUAL';
    item.pinnedIds = [];
  }
}

function reorder(group: Group, from: number, to: number) {
  if (busy.value || !draft.value || to < 0 || to >= draft.value[group].length) return;
  if (group === 'banners') draft.value.banners = moveItem(draft.value.banners, from, to);
  else if (group === 'shortcuts') draft.value.shortcuts = moveItem(draft.value.shortcuts, from, to);
  else draft.value.sections = moveItem(draft.value.sections, from, to);
  draft.value[group].forEach((item, index) => {
    item.sortNo = index;
  });
}

function remove(group: Group, index: number) {
  if (busy.value) return;
  draft.value?.[group].splice(index, 1);
  draft.value?.[group].forEach((item, i) => {
    item.sortNo = i;
  });
}

let dragging: { group: Group; index: number } | null = null;

function drop(group: Group, index: number) {
  if (!busy.value && dragging?.group === group) reorder(group, dragging.index, index);
  dragging = null;
}

onBeforeRouteLeave(() => !dirty.value || window.confirm('有未保存修改，确定离开？'));
onMounted(load);
</script>

<template>
  <div class="admin-layout home-editor">
    <!-- 顶部导航栏 -->
    <header class="admin-header">
      <div class="header-brand">
        <h1 class="brand-title">首页装修</h1>
        <span class="page-desc">配置轮播、快捷入口和内容模块，核对草稿后发布</span>
      </div>

      <div class="header-user">
        <el-tag v-if="draft" :type="dirty ? 'warning' : 'success'" effect="plain">
          {{ dirty ? '有未保存修改' : '草稿已同步' }}
        </el-tag>
        <details v-if="draft">
          <summary>版本信息</summary>
          草稿版本：{{ draft.revision }}
        </details>
        <el-button :disabled="busy" size="small" @click="load">丢弃本地改动</el-button>
      </div>
    </header>

    <main class="admin-content" :inert="busy">
      <!-- 提示反馈 -->
      <div v-if="error" class="mb-4">
        <el-alert :title="error" type="error" show-icon :closable="false" />
      </div>
      <div v-if="message" class="mb-4">
        <el-alert :title="message" type="success" show-icon :closable="false" />
      </div>

      <template v-if="draft">
        <div class="content-editor-layout">
          <div class="home-editor-main admin-card home-modules">
            <div class="admin-card__header">
              <h2 class="card-title">首页模块</h2>
              <el-tag :type="dirty ? 'warning' : 'success'" effect="light">{{
                dirty ? '未保存更改' : '草稿已保存'
              }}</el-tag>
            </div>
            <!-- 轮播图管理卡片 -->
            <section class="home-module-group">
              <div class="module-group-summary">
                <div>
                  <h3>轮播横幅</h3>
                  <span class="sub-desc">{{ draft.banners.length }} 张横幅 · 配置图片与跳转</span>
                </div>
                <el-button
                  text
                  type="primary"
                  :disabled="busy"
                  :aria-expanded="expandedGroups.has('banners')"
                  aria-controls="home-banner-config"
                  @click="toggleGroup('banners')"
                  >{{ expandedGroups.has('banners') ? '收起轮播' : '配置轮播' }}</el-button
                >
              </div>
              <div
                id="home-banner-config"
                v-show="expandedGroups.has('banners')"
                class="module-group-config"
              >
                <div class="module-config-toolbar">
                  <span class="sub-desc">拖拽或点击上下移调整顺序</span
                  ><el-button size="small" @click="add('banners')">+ 添加轮播</el-button>
                </div>
                <div class="editor-grid">
                  <div
                    v-for="(item, index) in draft.banners"
                    :key="item.id"
                    class="draggable-item-card"
                    draggable="true"
                    @dragstart="dragging = { group: 'banners', index }"
                    @dragover.prevent
                    @drop.prevent="drop('banners', index)"
                  >
                    <div class="item-header">
                      <span class="badge-index">轮播 {{ index + 1 }}</span>
                      <label class="checkbox-label">
                        <input v-model="item.enabled" type="checkbox" /> 启用展示
                      </label>
                      <div class="item-actions">
                        <el-button
                          size="small"
                          :disabled="index === 0"
                          @click="reorder('banners', index, index - 1)"
                          >↑ 上移</el-button
                        >
                        <el-button
                          size="small"
                          :disabled="index === draft.banners.length - 1"
                          @click="reorder('banners', index, index + 1)"
                          >↓ 下移</el-button
                        >
                        <el-button
                          size="small"
                          type="danger"
                          plain
                          @click="remove('banners', index)"
                          >删除</el-button
                        >
                      </div>
                    </div>

                    <div class="item-summary">
                      <strong
                        >{{ item.title || '未命名轮播' }} · {{ targetLabel(item.target) }}</strong
                      ><el-button
                        size="small"
                        :disabled="busy"
                        :aria-expanded="editing.has('banners:' + item.id)"
                        :aria-label="`${editing.has('banners:' + item.id) ? '收起' : '编辑'}轮播 ${index + 1} 配置`"
                        @click="toggleEdit('banners', item.id)"
                        >{{
                          editing.has('banners:' + item.id) ? '收起配置' : '编辑配置'
                        }}</el-button
                      >
                    </div>
                    <div v-show="editing.has('banners:' + item.id)" class="item-fields">
                      <AssetPicker v-model="item.assetId" @busy="uploadBusy(item.id, $event)" />
                      <div class="field-row">
                        <span class="field-label">横幅标题</span>
                        <el-input v-model="item.title" placeholder="如：名师春季精读课限时特惠" />
                      </div>
                      <div class="field-row">
                        <span class="field-label">跳转目标</span>
                        <InternalTargetEditor v-model="item.target" :disabled="busy" />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <!-- 快捷入口管理卡片 -->
            <section class="home-module-group">
              <div class="module-group-summary">
                <div>
                  <h3>快捷入口</h3>
                  <span class="sub-desc"
                    >{{ draft.shortcuts.length }} 个入口 · 配置图标与站内目标</span
                  >
                </div>
                <el-button
                  text
                  type="primary"
                  :disabled="busy"
                  :aria-expanded="expandedGroups.has('shortcuts')"
                  aria-controls="home-shortcut-config"
                  @click="toggleGroup('shortcuts')"
                  >{{ expandedGroups.has('shortcuts') ? '收起入口' : '配置入口' }}</el-button
                >
              </div>
              <div
                id="home-shortcut-config"
                v-show="expandedGroups.has('shortcuts')"
                class="module-group-config"
              >
                <div class="module-config-toolbar">
                  <span class="sub-desc">排序、图标与目标以已发布配置为准</span
                  ><el-button size="small" @click="add('shortcuts')">+ 添加入口</el-button>
                </div>
                <div class="editor-grid-cols-2">
                  <div
                    v-for="(item, index) in draft.shortcuts"
                    :key="item.id"
                    class="draggable-item-card"
                    draggable="true"
                    @dragstart="dragging = { group: 'shortcuts', index }"
                    @dragover.prevent
                    @drop.prevent="drop('shortcuts', index)"
                  >
                    <div class="item-header">
                      <span class="badge-index">入口 {{ index + 1 }}</span>
                      <label class="checkbox-label">
                        <input v-model="item.enabled" type="checkbox" /> 启用
                      </label>
                      <div class="item-actions">
                        <el-button
                          size="small"
                          :disabled="index === 0"
                          :aria-label="`上移入口 ${index + 1}`"
                          @click="reorder('shortcuts', index, index - 1)"
                          >↑</el-button
                        >
                        <el-button
                          size="small"
                          :disabled="index === draft.shortcuts.length - 1"
                          :aria-label="`下移入口 ${index + 1}`"
                          @click="reorder('shortcuts', index, index + 1)"
                          >↓</el-button
                        >
                        <el-button
                          size="small"
                          type="danger"
                          plain
                          :aria-label="`删除入口 ${index + 1}`"
                          @click="remove('shortcuts', index)"
                          >×</el-button
                        >
                      </div>
                    </div>

                    <div class="item-summary">
                      <strong
                        >{{ item.label || '未命名入口' }} · {{ targetLabel(item.target) }}</strong
                      ><el-button
                        size="small"
                        :disabled="busy"
                        :aria-expanded="editing.has('shortcuts:' + item.id)"
                        :aria-label="`${editing.has('shortcuts:' + item.id) ? '收起' : '编辑'}入口 ${index + 1} 配置`"
                        @click="toggleEdit('shortcuts', item.id)"
                        >{{
                          editing.has('shortcuts:' + item.id) ? '收起配置' : '编辑配置'
                        }}</el-button
                      >
                    </div>
                    <div v-show="editing.has('shortcuts:' + item.id)" class="item-fields">
                      <AssetPicker v-model="item.assetId" @busy="uploadBusy(item.id, $event)" />
                      <div class="field-row">
                        <span class="field-label">入口名称</span>
                        <el-input v-model="item.label" placeholder="如：视频课程、七年级" />
                      </div>
                      <div class="field-row">
                        <span class="field-label">跳转目标</span>
                        <InternalTargetEditor v-model="item.target" :disabled="busy" />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <!-- 内容模块楼层管理 -->
            <section class="home-section-group">
              <div class="module-config-toolbar">
                <div>
                  <h3>内容模块</h3>
                  <span class="sub-desc">{{ draft.sections.length }} 个模块 · 按顺序展示</span>
                </div>
                <el-button type="primary" size="small" @click="add('sections')"
                  >+ 添加楼层</el-button
                >
              </div>

              <div class="editor-grid">
                <div
                  v-for="(item, index) in draft.sections"
                  :key="item.id"
                  class="draggable-item-card home-section-row"
                  :class="{ 'is-editing': editing.has('sections:' + item.id) }"
                  draggable="true"
                  @dragstart="dragging = { group: 'sections', index }"
                  @dragover.prevent
                  @drop.prevent="drop('sections', index)"
                >
                  <div v-show="editing.has('sections:' + item.id)" class="item-header">
                    <span class="badge-index">模块 {{ index + 1 }}</span>
                    <label class="checkbox-label">
                      <input v-model="item.enabled" type="checkbox" /> 启用展示
                    </label>
                    <div class="item-actions">
                      <el-button
                        size="small"
                        :disabled="index === 0"
                        @click="reorder('sections', index, index - 1)"
                        >↑ 上移</el-button
                      >
                      <el-button
                        size="small"
                        :disabled="index === draft.sections.length - 1"
                        @click="reorder('sections', index, index + 1)"
                        >↓ 下移</el-button
                      >
                      <el-button size="small" type="danger" plain @click="remove('sections', index)"
                        >删除模块</el-button
                      >
                    </div>
                  </div>

                  <div class="item-summary">
                    <strong
                      >{{ item.title || '未命名楼层' }} ·
                      {{
                        {
                          PRODUCT_GRID: '商品列表',
                          BOOK_GRID: '图书专栏',
                          COURSE_GRID: '课程专栏',
                          PAPER_GRID: '试卷专栏',
                          MEMBERSHIP_ENTRY: '会员入口',
                          ARTICLE_CARD: '图文卡片',
                        }[item.type]
                      }}</strong
                    ><el-button
                      size="small"
                      :disabled="busy"
                      :aria-expanded="editing.has('sections:' + item.id)"
                      :aria-label="`${editing.has('sections:' + item.id) ? '收起' : '编辑'}模块 ${index + 1} 配置`"
                      @click="toggleEdit('sections', item.id)"
                      >{{ editing.has('sections:' + item.id) ? '收起配置' : '编辑配置' }}</el-button
                    >
                  </div>
                  <div v-show="editing.has('sections:' + item.id)" class="item-fields">
                    <div class="field-row">
                      <span class="field-label">模块标题</span>
                      <el-input v-model="item.title" placeholder="如：热销精品课程" />
                    </div>
                    <div class="field-row">
                      <span class="field-label">模块形式</span>
                      <select v-model="item.type" class="ui-select" @change="setSectionType(item)">
                        <option value="PRODUCT_GRID">商品列表</option>
                        <option value="BOOK_GRID">图书专栏</option>
                        <option value="COURSE_GRID">课程专栏</option>
                        <option value="PAPER_GRID">试卷专栏</option>
                        <option value="MEMBERSHIP_ENTRY">会员入口</option>
                        <option value="ARTICLE_CARD">图文卡片</option>
                      </select>
                    </div>
                    <fieldset v-if="['BOOK_GRID', 'COURSE_GRID', 'PAPER_GRID'].includes(item.type)">
                      <legend>栏目展示规则</legend>
                      <HomeDisplayMode
                        :model-value="item.selectionMode === 'AUTO' ? 'automatic' : 'manual'"
                        :count="item.displayLimit ?? 4"
                        :busy="busy"
                        @update:model-value="setMode(item, $event)"
                        @update:count="item.displayLimit = $event"
                      />
                      <template v-if="item.selectionMode === 'AUTO'">
                        <p>
                          有效置顶优先，随后按首次发布顺序补足；已下架内容会跳过。保存草稿并发布首页后生效。
                        </p>
                        <ContentPicker
                          :model-value="item.pinnedIds ?? []"
                          :kind="
                            item.type === 'PAPER_GRID'
                              ? 'PAPER'
                              : item.type === 'BOOK_GRID'
                                ? 'BOOK'
                                : 'COURSE'
                          "
                          :disabled="busy"
                          @update:model-value="item.pinnedIds = $event"
                        />
                        <p
                          v-if="(item.pinnedIds?.length ?? 0) > (item.displayLimit ?? 4)"
                          role="status"
                        >
                          置顶数超过展示数量，部分置顶暂不展示；请核对保存后的预览。
                        </p>
                      </template>
                      <p v-else>保留人工选择和顺序，不自动补入其他内容。</p>
                    </fieldset>
                    <div v-if="item.type === 'ARTICLE_CARD'" class="field-row">
                      <span class="field-label">关联图文</span>
                      <ContentPicker
                        :model-value="item.articleId ? [item.articleId] : []"
                        kind="ARTICLE"
                        single
                        :disabled="busy"
                        @update:model-value="item.articleId = $event[0] ?? null"
                      />
                    </div>
                    <div
                      v-else-if="item.type === 'PAPER_GRID' && item.selectionMode !== 'AUTO'"
                      class="field-row"
                    >
                      <span class="field-label">精选试卷</span>
                      <ContentPicker
                        :model-value="item.paperIds ?? []"
                        kind="PAPER"
                        :disabled="busy"
                        @update:model-value="item.paperIds = $event"
                      />
                    </div>
                    <div
                      v-else-if="
                        item.selectionMode !== 'AUTO' &&
                        ['PRODUCT_GRID', 'BOOK_GRID', 'COURSE_GRID'].includes(item.type)
                      "
                      class="field-row"
                    >
                      <span class="field-label">精选内容</span>
                      <ContentPicker
                        v-model="item.productIds"
                        :kind="
                          item.type === 'BOOK_GRID'
                            ? 'BOOK'
                            : item.type === 'COURSE_GRID'
                              ? 'COURSE'
                              : item.type === 'MEMBERSHIP_ENTRY'
                                ? 'MEMBERSHIP'
                                : 'PRODUCT'
                        "
                        :disabled="busy"
                      />
                    </div>
                    <div class="field-row">
                      <span class="field-label">查看更多目标</span>
                      <div class="more-target-box">
                        <template v-if="item.moreTarget">
                          <InternalTargetEditor v-model="item.moreTarget" :disabled="busy" />
                          <el-button size="small" @click="item.moreTarget = null"
                            >清除跳转</el-button
                          >
                        </template>
                        <el-button v-else size="small" @click="item.moreTarget = defaultTarget()"
                          >+ 配置查看更多链接</el-button
                        >
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <!-- 底部吸底控制栏 -->
          </div>
          <aside class="content-editor-sidebar">
            <div class="publish-control-bar">
              <h3>发布设置</h3>
              <p class="publish-help">
                {{
                  dirty ? '有未保存修改，请先保存草稿。' : '当前草稿已保存，可预览后发布。'
                }}预览读取服务端已保存版本，发布后用户端才会更新。
              </p>
              <div class="publish-left">
                <span class="field-label">发布说明：</span>
                <el-input v-model="reason" placeholder="如：更新首页七年级暑假名师课专题" />
              </div>

              <div class="publish-right">
                <el-button :disabled="busy" @click="save">保存草稿</el-button>
                <el-button :disabled="busy" type="info" plain @click="showPreview"
                  >预览已保存版本</el-button
                >
                <el-button :disabled="busy || dirty" type="primary" @click="publish"
                  >正式发布上线</el-button
                >
              </div>
            </div>

            <!-- 预览区 -->
            <div v-if="preview" class="admin-card preview-section">
              <div class="admin-card__header">
                <h2 class="card-title">预览效果 · 版本 {{ preview.revision }}</h2>
              </div>
              <div class="preview-content">
                <section aria-label="内容展示结果">
                  <h3>本次服务端结果（非实时公开首页）</h3>
                  <p>
                    只核对已保存版本；未保存改动不在此结果中。内容状态会随上下架变化，可重新预览。
                  </p>
                  <ul>
                    <li v-for="(row, index) in previewRows" :key="index">
                      {{ row.section }} · {{ row.title }} — {{ row.reason }}
                    </li>
                  </ul>
                </section>
                <div class="preview-block">
                  <h4>轮播横幅 ({{ preview.banners.length }})</h4>
                  <div class="preview-pills">
                    <span v-for="b in preview.banners" :key="b.id" class="p-pill">{{
                      b.title
                    }}</span>
                  </div>
                </div>
                <div class="preview-block">
                  <h4>快捷入口 ({{ preview.shortcuts.length }})</h4>
                  <div class="preview-pills">
                    <span v-for="s in preview.shortcuts" :key="s.id" class="p-pill">{{
                      s.label
                    }}</span>
                  </div>
                </div>
                <div class="preview-block">
                  <h4>内容模块 ({{ preview.sections.length }})</h4>
                  <div class="preview-pills">
                    <span v-for="section in preview.sections" :key="section.id" class="p-pill">{{
                      section.title
                    }}</span>
                  </div>
                </div>
              </div>
            </div>
          </aside>
        </div>
      </template>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.home-editor-main {
  min-width: 0;
}
.home-modules > .admin-card__header {
  margin-bottom: 8px;
}
.home-module-group {
  border-bottom: 1px solid var(--ls-line);
}
.module-group-summary,
.module-config-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}
.module-group-summary {
  min-height: 82px;
  padding: 18px 0;
}
.module-group-summary > div {
  min-width: 0;
}
.module-group-summary h3,
.module-config-toolbar h3 {
  font-size: 14px;
  margin: 0;
}
.module-group-summary > .el-button {
  flex-shrink: 0;
  min-height: 36px;
}
.module-group-config {
  padding-bottom: 18px;
}
.module-config-toolbar {
  padding: 16px 0;
  flex-wrap: wrap;
}
.home-section-group .editor-grid {
  gap: 0;
}
.draggable-item-card.home-section-row {
  background: white;
  border: 0;
  border-bottom: 1px solid var(--ls-line);
  border-radius: 0;
  padding: 18px 0;
}
.home-section-row .item-summary {
  padding: 0;
  min-height: 44px;
}
.home-section-row.is-editing {
  padding: 16px;
  background: var(--ls-tint);
  border-radius: 8px;
}
.item-fields {
  padding-top: 16px;
}
.home-modules .sub-desc {
  color: var(--ls-sub);
}
@media (max-width: 736px) {
  .module-group-summary {
    gap: 8px;
  }
  .home-section-row .item-summary {
    flex-wrap: wrap;
    gap: 8px;
  }
}
.home-editor .content-editor-sidebar .publish-control-bar {
  position: static;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  margin: 0 0 18px;
  padding: 18px;
  box-shadow: none;
  gap: 12px;
}
.home-editor .content-editor-sidebar .publish-left {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  flex: none;
  gap: 8px;
}
.home-editor .content-editor-sidebar .publish-left .el-input {
  width: 100%;
  flex: none;
}
.home-editor .content-editor-sidebar .publish-right {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.home-editor .content-editor-sidebar .publish-right .el-button {
  margin: 0;
  min-height: 38px;
}
.home-editor .content-editor-sidebar h3,
.home-editor .content-editor-sidebar p {
  margin: 0;
}
.home-editor .preview-section {
  margin-top: 0;
  border: 1px solid var(--ls-line);
}
.item-media-summary {
  margin-top: 12px;
}
.item-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0 0;
}
.item-summary strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 500;
}
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}

.sub-desc {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 4px;
  display: block;
}

.mb-4 {
  margin-bottom: 16px;
}

.editor-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.editor-grid-cols-2 {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.draggable-item-card {
  background: #fafafc;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  padding: 16px;

  .item-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px dashed #e5ebf3;

    .badge-index {
      font-weight: 700;
      color: #374151;
      font-size: 14px;
    }

    .checkbox-label {
      font-size: 13px;
      color: #4b5563;
      display: flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
    }

    .item-actions {
      display: flex;
      gap: 6px;
    }
  }

  .item-fields {
    display: flex;
    flex-direction: column;
    gap: 12px;

    .field-row {
      display: flex;
      align-items: center;
      gap: 12px;

      .field-label {
        width: 100px;
        font-size: 13px;
        color: #6b7280;
        flex-shrink: 0;
      }
    }
  }
}

.ui-select {
  height: 32px;
  padding: 0 12px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  background: #fff;
  font-size: 13px;
  color: #606266;
}

.publish-control-bar {
  position: sticky;
  bottom: 0;
  z-index: 99;
  background: #ffffff;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  padding: 16px 24px;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24px;

  .publish-left {
    display: flex;
    align-items: center;
    gap: 12px;

    .field-label {
      font-size: 13px;
      color: #374151;
      font-weight: 600;
    }
  }

  .publish-right {
    display: flex;
    gap: 12px;
  }
}

.preview-section {
  margin-top: 24px;
  border: 2px solid #2855b7;
}

.preview-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.preview-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;

  .p-pill {
    background: #edf3ff;
    color: #2855b7;
    padding: 4px 12px;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 500;
  }
}
</style>
