<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import { formatShanghaiDateTime } from '@jxsc/shared';
import type { AdminArticle } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';

const { busy, error, message, run } = useContentEditor();
const items = ref<AdminArticle[]>([]);
const page = ref(1);
const total = ref(0);
const keyword = ref('');
const status = ref('');

async function query(next: number) {
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

async function load(next = 1) {
  await run(() => query(next));
}

onMounted(() => load());
</script>

<template>
  <div class="articles-page-container">
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">图文管理</h2>
        <span class="page-desc">管理教学文章，发布到商城内容楼层</span>
      </div>
      <div class="header-actions">
        <router-link to="/content/articles/new">
          <el-button type="primary" :disabled="busy">新建图文</el-button>
        </router-link>
        <el-button :disabled="busy" @click="load()">刷新列表</el-button>
      </div>
    </div>

    <div v-if="error" class="alert-box alert-error" role="alert">{{ error }}</div>
    <div v-if="message" class="alert-box alert-success" role="status">{{ message }}</div>

    <div class="filter-card">
      <form class="filter-form" @submit.prevent="load(1)">
        <div class="filter-grid">
          <div class="filter-item">
            <label class="filter-label" for="article-keyword">文章标题</label>
            <input
              id="article-keyword"
              v-model="keyword"
              class="modern-input"
              placeholder="输入标题关键词搜索"
            />
          </div>
          <div class="filter-item">
            <label class="filter-label" for="article-status">发布状态</label>
            <select id="article-status" v-model="status" class="modern-select">
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

    <div class="table-card">
      <div class="ls-table-scroll">
        <table class="modern-table">
          <thead>
            <tr>
              <th style="width: 40%">标题</th>
              <th style="width: 20%">分类</th>
              <th style="width: 14%">状态</th>
              <th style="width: 18%">更新时间</th>
              <th style="width: 8%; text-align: right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id" class="table-row">
              <td>
                <span class="article-title">{{ item.title }}</span>
              </td>
              <td>{{ item.categoryName || '未分类' }}</td>
              <td>
                <span
                  class="status-pill"
                  :class="{
                    'status-published': item.status === 'PUBLISHED',
                    'status-draft': item.status === 'DRAFT',
                    'status-unpublished': item.status === 'UNPUBLISHED',
                  }"
                >
                  {{
                    item.status === 'PUBLISHED'
                      ? '已发布'
                      : item.status === 'DRAFT'
                        ? '草稿'
                        : '已撤回'
                  }}
                </span>
              </td>
              <td>{{ item.updatedAt ? formatShanghaiDateTime(item.updatedAt) : '—' }}</td>
              <td style="text-align: right">
                <router-link :to="`/content/articles/${encodeURIComponent(item.id)}`">
                  <el-button type="primary" link size="small" :disabled="busy">编辑</el-button>
                </router-link>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="!items.length && !busy" class="empty-state"><p>没有匹配文章</p></div>

      <div class="pagination-bar">
        <span class="total-info">{{ page }} 页，共 {{ total }} 条</span>
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
  </div>
</template>

<style lang="scss" scoped>
.filter-form,
.filter-grid {
  width: 100%;
}
.filter-card {
  margin-bottom: 0;
  border-radius: 12px 12px 0 0;
}
.article-title {
  font-weight: 600;
}
.table-card {
  border-top: 0;
  border-radius: 0 0 12px 12px;
}
.modern-table :deep(th) {
  color: #617088;
  background: #f4f7fb;
  font-size: 12px;
  font-weight: 500;
}
.alert-box {
  padding: 12px 14px;
  border-radius: 8px;
  margin: 12px 0;
}
</style>
