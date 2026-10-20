<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import { formatShanghaiDateTime } from '@jxsc/shared';
import type { AdminArticle } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';
import AssetPicker from '../../components/AssetPicker.vue';
import ContentBlockEditor from '../../components/ContentBlockEditor.vue';
import ContentDraftPreview from '../../components/ContentDraftPreview.vue';
import BookCover from '../../components/ui/BookCover.vue';

const route = useRoute();
const router = useRouter();
const { busy, error, message, run, invalidate } = useContentEditor();
const coverUploading = ref(false);
const contentUploading = ref(false);
const uploading = computed(() => coverUploading.value || contentUploading.value);
const currentId = ref<string | null>(
  route.params.id === 'new' ? null : String(route.params.id ?? ''),
);
const isRoutedEditor = computed(() => route.params.id !== undefined);
const items = ref<AdminArticle[]>([]);
const page = ref(1);
const total = ref(0);
const keyword = ref('');
const status = ref('');
const form = ref<AdminArticle | null>(null);
const saved = ref('');
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== saved.value);

function emptyArticle(): AdminArticle {
  return {
    id: '',
    version: 0,
    title: '',
    coverAssetId: null,
    contentBlocks: [{ type: 'TEXT', text: '' }],
    status: 'DRAFT',
  };
}

function accept(value: AdminArticle) {
  form.value = value;
  currentId.value = value.id || null;
  saved.value = JSON.stringify(value);
}

async function load() {
  await run(async () => {
    const id = currentId.value;
    if (id) accept(await contentApi.article(id));
    else accept(emptyArticle());
  });
}

async function query(next = 1) {
  const value = await contentApi.articles({
    keyword: keyword.value,
    status: status.value || undefined,
    page: next,
    pageSize: 20,
  });
  items.value = value.items;
  page.value = value.page;
  total.value = value.total;
}

async function loadList(next = 1) {
  await run(() => query(next));
}

async function editLegacy(item?: AdminArticle) {
  if (busy.value || uploading.value) return;
  if (dirty.value && !window.confirm('丢弃当前未保存修改？')) return;
  if (item) {
    await run(async () => accept(await contentApi.article(item.id)));
  } else accept(emptyArticle());
}

function collapseLegacy() {
  if (busy.value || uploading.value) return;
  if (dirty.value && !window.confirm('丢弃当前未保存修改并收起编辑？')) return;
  form.value = null;
}

async function save() {
  if (uploading.value || !form.value) return;
  await run(async (current) => {
    const { id, ...body } = form.value!;
    const value = await contentApi.saveArticle(currentId.value || id || null, body);
    if (!current()) return;
    accept(value);
    if (route.params.id === 'new')
      await router.replace(`/content/articles/${encodeURIComponent(value.id)}`);
  }, '文章已保存');
}

function leaveEditor() {
  if (busy.value || uploading.value) return;
  void router.push('/content/articles');
}

onBeforeRouteLeave(() => !dirty.value || window.confirm('有未保存修改，确定离开？'));
onBeforeRouteUpdate((to, from) => {
  if (to.fullPath === from.fullPath) return true;
  if (!dirty.value) return true;
  if (busy.value || uploading.value) return false;
  return window.confirm('有未保存修改，确定离开？');
});

watch(
  () => route.params.id,
  (value, previous) => {
    if (value === previous) return;
    invalidate();
    currentId.value = value === 'new' ? null : String(value ?? '');
    form.value = null;
    saved.value = '';
    void load();
  },
);
onMounted(() => (isRoutedEditor.value ? load() : loadList()));
</script>

<template>
  <div class="articles-page-container">
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">{{ isRoutedEditor ? '图文编辑' : '图文管理' }}</h2>
        <span class="page-desc">维护力山学堂图文干货、阅读指南及通知公告内容</span>
      </div>
      <div class="header-actions">
        <router-link v-if="isRoutedEditor" to="/content/articles" class="back-link"
          >← 返回图文列表</router-link
        >
        <router-link v-else to="/" class="back-link">← 返回工作台</router-link>
        <el-button
          v-if="!isRoutedEditor"
          type="primary"
          :disabled="busy || uploading"
          @click="editLegacy()"
          >+ 新建图文</el-button
        >
        <el-button v-if="isRoutedEditor" :disabled="busy || uploading" @click="load"
          >重新读取</el-button
        >
        <el-button v-else :disabled="busy" @click="loadList()">刷新列表</el-button>
      </div>
    </div>

    <div v-if="error" class="alert-box alert-error" role="alert">{{ error }}</div>
    <div v-if="message" class="alert-box alert-success" role="status">{{ message }}</div>

    <div v-if="!form && !isRoutedEditor" class="filter-card">
      <form class="filter-form" @submit.prevent="loadList(1)">
        <div class="filter-grid">
          <div class="filter-item">
            <label class="filter-label" for="legacy-article-keyword">文章标题</label>
            <input
              id="legacy-article-keyword"
              v-model="keyword"
              class="modern-input"
              placeholder="输入标题关键词搜索"
            />
          </div>
          <div class="filter-item">
            <label class="filter-label" for="legacy-article-status">发布状态</label>
            <select id="legacy-article-status" v-model="status" class="modern-select">
              <option value="">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="PUBLISHED">已发布</option>
              <option value="UNPUBLISHED">已撤回</option>
            </select>
          </div>
          <div class="filter-actions-inline">
            <el-button native-type="submit" type="primary" :disabled="busy">筛选</el-button>
          </div>
        </div>
      </form>
    </div>

    <div v-if="!form && !isRoutedEditor" class="table-card">
      <div class="ls-table-scroll">
        <table class="modern-table">
          <thead>
            <tr>
              <th style="width: 55%">标题 / 文章ID</th>
              <th style="width: 25%">状态</th>
              <th style="width: 20%; text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" class="table-row">
              <td>
                <div class="article-meta">
                  <BookCover :title="item.title" class="ls-row-cover" />
                  <div class="article-info">
                    <span class="article-title">{{ item.title }}</span>
                    <span class="article-id">ID: {{ item.id }}</span>
                  </div>
                </div>
              </td>
              <td>
                {{
                  item.status === 'PUBLISHED'
                    ? '已发布'
                    : item.status === 'DRAFT'
                      ? '草稿'
                      : '已撤回'
                }}
              </td>
              <td style="text-align: right">
                <el-button
                  type="primary"
                  link
                  size="small"
                  :disabled="busy"
                  @click="editLegacy(item)"
                >
                  编辑
                </el-button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!items.length && !busy" class="empty-state"><p>没有匹配文章</p></div>
      <div class="pagination-bar">
        <span class="total-info">{{ page }} 页，共 {{ total }} 条</span>
        <div class="page-btns">
          <el-button size="small" :disabled="busy || page <= 1" @click="loadList(page - 1)"
            >上一页</el-button
          >
          <el-button size="small" :disabled="busy || page * 20 >= total" @click="loadList(page + 1)"
            >下一页</el-button
          >
        </div>
      </div>
    </div>

    <div v-if="form" class="content-editor-layout">
      <section class="editor-card">
        <div class="editor-header">
          <h3 class="editor-title">
            {{ currentId ? '编辑图文' : '新建图文' }}
            <span class="dirty-badge" :class="{ 'dirty-badge--active': dirty }">
              · {{ dirty ? '未保存' : currentId ? '已保存' : '新草稿（尚未保存）' }}
            </span>
          </h3>
          <el-button
            size="small"
            :disabled="busy || uploading"
            @click="isRoutedEditor ? leaveEditor() : collapseLegacy()"
          >
            {{ isRoutedEditor ? '返回列表' : '收起编辑' }}
          </el-button>
        </div>

        <form id="article-editor-form" class="editor-form" @submit.prevent="save">
          <fieldset class="editor-fieldset" :disabled="busy || uploading">
            <details class="ls-secondary-details">
              <summary>更多信息（选填）</summary>
              <div class="form-row">
                <label class="form-label"
                  >作者署名<input
                    name="authorName"
                    class="modern-input"
                    v-model="form.authorName"
                    maxlength="100"
                    placeholder="请输入作者署名（选填）"
                /></label>
              </div>
              <div class="form-row">
                <label class="form-label"
                  >图文分类<input
                    name="categoryName"
                    class="modern-input"
                    v-model="form.categoryName"
                    maxlength="80"
                    placeholder="请输入图文分类（选填）"
                /></label>
              </div>
              <p v-if="form.updatedAt">更新时间：{{ formatShanghaiDateTime(form.updatedAt) }}</p>
            </details>
            <div class="form-row">
              <label class="form-label">
                文章标题 <span class="required">*</span>
                <input
                  v-model="form.title"
                  class="modern-input"
                  maxlength="100"
                  required
                  placeholder="请输入图文标题（100字内）"
                />
              </label>
            </div>

            <div class="form-row">
              <span class="form-label">封面图片</span>
              <AssetPicker v-model="form.coverAssetId" @busy="coverUploading = $event" />
            </div>

            <div class="form-row">
              <span class="form-label">正文内容块</span>
              <ContentBlockEditor v-model="form.contentBlocks" @busy="contentUploading = $event" />
            </div>
          </fieldset>
        </form>
      </section>
      <aside class="content-editor-sidebar">
        <section class="admin-card">
          <h3>发布设置</h3>
          <label
            >发布状态<select
              v-model="form.status"
              class="modern-select"
              :disabled="busy || uploading"
            >
              <option value="DRAFT">草稿</option>
              <option value="PUBLISHED">发布</option>
              <option value="UNPUBLISHED">撤回</option>
            </select></label
          >
          <p class="publish-help">
            保存时应用所选状态；选择发布后保存，内容将对用户可见。草稿和撤回内容不公开展示。
          </p>
          <div class="publish-actions">
            <el-button
              type="primary"
              native-type="submit"
              form="article-editor-form"
              :disabled="busy || uploading"
              >保存图文</el-button
            >
          </div>
        </section>
        <ContentDraftPreview
          :title="form.title"
          :asset-id="form.coverAssetId"
          :blocks="form.contentBlocks"
        />
      </aside>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.form-row {
  margin: 18px 0;
}
.form-label {
  width: 100%;
}
.editor-fieldset {
  border: 0;
  padding: 0;
}
.editor-card {
  margin-top: 24px;
}
.dirty-badge {
  font-size: 12px;
  color: #617088;
}
.dirty-badge--active {
  color: #976014;
}
.alert-box {
  padding: 12px 14px;
  border-radius: 8px;
  margin: 12px 0;
}
.filter-form,
.filter-grid {
  width: 100%;
}
.article-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}
.article-title {
  font-weight: 600;
}
.article-id {
  display: block;
  color: #617088;
  font-size: 12px;
}
.ls-row-cover {
  width: 42px;
  height: 54px;
  flex-shrink: 0;
}
.ls-row-cover :deep(.ls-cover-caption) {
  display: none;
}
</style>
