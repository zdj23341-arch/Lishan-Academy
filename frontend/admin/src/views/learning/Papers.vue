<script setup lang="ts">
import HomeRecommendation from '../../components/HomeRecommendation.vue';
import { uiStatus } from '../../components/ui/labels';
import BookCover from '../../components/ui/BookCover.vue';
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminPaper } from '@jxsc/shared/learning/admin-types';
import { learningApi } from '../../api/learning';
import { useLearningPage } from '../../composables/useLearningPage';
const empty = () => ({
  keyword: '',
  schoolStage: '',
  grade: '',
  subject: '',
  year: '',
  status: '',
  visibility: '',
});
const query = ref(empty()),
  items = ref<AdminPaper[]>([]),
  page = ref(1),
  total = ref(0);
const { allowed, busy, error, run } = useLearningPage(() => {
  query.value = empty();
  items.value = [];
  page.value = 1;
  total.value = 0;
});
let requestedPage = 1;
async function load(next = 1) {
  requestedPage = next;
  await run(async (current) => {
    const result = await learningApi.papers({ page: next, pageSize: 20 });
    if (current()) {
      items.value = result.items;
      page.value = result.page;
      total.value = result.total;
    }
  });
}
onMounted(() => load());
</script>
<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">试卷管理</h2>
        <span class="page-desc">管理中小学各学段题库、真题试卷及附件资源</span>
      </div>
      <div class="header-actions">
        <router-link to="/" class="back-link">返回后台首页</router-link>
        <router-link to="/learning/papers/new" class="create-btn-link">
          <el-button type="primary">新建试卷</el-button>
        </router-link>
        <el-button :disabled="busy" @click="load()">刷新列表</el-button>
      </div>
    </div>

    <div v-if="!allowed" class="alert-box alert-error" role="alert">没有内容管理权限</div>

    <template v-else>
      <div v-if="error" class="alert-box alert-warning" role="alert">
        <span>{{ error }}</span>
        <el-button size="small" type="primary" :disabled="busy" @click="load(requestedPage)"
          >重试</el-button
        >
      </div>

      <div class="table-card">
        <div v-if="busy" class="loading-bar" role="status">正在读取试卷...</div>

        <div class="ls-table-scroll">
          <table class="modern-table">
            <thead>
              <tr>
                <th style="min-width: 280px">试卷标题</th>
                <th style="min-width: 200px">分类学段</th>
                <th style="min-width: 90px">年份</th>
                <th style="min-width: 160px">状态</th>
                <th style="min-width: 110px; text-align: right">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.id" class="table-row">
                <td>
                  <div class="paper-meta">
                    <BookCover :title="item.title" class="ls-row-cover" theme="paper" />
                    <div class="paper-info">
                      <span class="paper-title">{{ item.title }}</span>
                      <span class="paper-id">ID: {{ item.id }}</span>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="category-badge">
                    {{ uiStatus(item.schoolStage) }} / {{ item.grade }}年级 /
                    {{ uiStatus(item.subject) }}
                  </span>
                </td>
                <td>
                  <span class="year-text">{{ item.year || '未填写' }}</span>
                </td>
                <td>
                  <span
                    class="status-tag"
                    :class="{
                      'status-published': item.status === 'PUBLISHED',
                      'status-draft': item.status === 'DRAFT',
                      'status-unpublished': item.status === 'UNPUBLISHED',
                    }"
                  >
                    <span class="status-dot"></span>
                    <span class="status-label">
                      {{
                        item.status === 'PUBLISHED'
                          ? '已发布'
                          : item.status === 'DRAFT'
                            ? '草稿'
                            : '已下架'
                      }}
                    </span>
                  </span>
                </td>
                <td style="text-align: right">
                  <HomeRecommendation
                    :content-id="item.id"
                    kind="PAPER"
                    :disabled="busy || item.status !== 'PUBLISHED'"
                  />
                  <router-link
                    :to="`/learning/papers/${encodeURIComponent(item.id)}`"
                    class="edit-action-link"
                  >
                    <el-button type="primary" size="small" plain>编辑试卷</el-button>
                  </router-link>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="!items.length && !busy" class="empty-state">
          <div class="empty-icon"></div>
          <p class="empty-text">没有符合条件的试卷</p>
        </div>

        <div class="pagination-bar">
          <span class="total-info">
            {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}，共 {{ total }} 条
          </span>
          <div class="page-btns">
            <el-button size="small" :disabled="busy || page <= 1" @click="load(page - 1)"
              >上一页</el-button
            >
            <el-button size="small" :disabled="busy || page * 20 >= total" @click="load(page + 1)"
              >下一页</el-button
            >
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
<style lang="scss" scoped>
.ls-row-cover {
  width: 42px;
  height: 54px;
  flex-shrink: 0;
}
.ls-row-cover :deep(.ls-cover-title) {
  font-size: 14px;
  line-height: 1.3;
  letter-spacing: 0;
}
.ls-row-cover :deep(.ls-cover-art) {
  padding: 7px;
}
.ls-row-cover :deep(.ls-cover-caption) {
  display: none;
}
.raw-status {
  font-size: 11px;
  opacity: 0.65;
}
.filter-form {
  width: 100%;
}
.filter-grid {
  width: 100%;
}
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
.goods-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.goods-qty {
  font-size: 12px;
  color: #617088;
}
.amount {
  font-weight: 600;
}
.btn {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  text-decoration: none;
}
.btn--primary {
  background: #2855b7;
  color: #fff;
  border-color: #2855b7;
}
.col-checkbox {
  min-width: 36px;
}
.action-link {
  margin-right: 10px;
}
.error-icon {
  width: 18px;
  height: 18px;
}
.error-bar {
  display: flex;
  align-items: center;
  gap: 12px;
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
.alert-info {
  background: #edf3ff;
  color: #2855b7;
}
.info-icon {
  width: 18px;
  height: 18px;
}
</style>
