<script setup lang="ts">
import BookCover from '../../components/ui/BookCover.vue';
import UiIcon from '../../components/ui/UiIcon.vue';
import HomeRecommendation from '../../components/HomeRecommendation.vue';
import { onMounted, ref } from 'vue';
import { ElButton, ElInput, ElAlert, ElTag } from 'element-plus';
import { formatFen } from '@jxsc/shared';
import type { AdminProduct } from '@jxsc/shared/content/admin-types';
import { contentApi } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';

const { busy, error, message, run } = useContentEditor();
const items = ref<AdminProduct[]>([]);
const page = ref(1);
const total = ref(0);
const keyword = ref('');
const type = ref('');
const status = ref('');
const coverUrls = ref<Record<string, string>>({});
const coverError = ref('');
async function resolveCovers(products: AdminProduct[]) {
  const missing = new Set(
    products
      .map((item) => item.coverAssetId)
      .filter((id): id is string => !!id && !coverUrls.value[id]),
  );
  if (!missing.size) return;
  coverError.value = '';
  try {
    let current = 1;
    while (missing.size) {
      const result = await contentApi.assets({
        purpose: 'PUBLIC_IMAGE',
        status: 'READY',
        page: current,
        pageSize: 100,
      });
      for (const asset of result.items)
        if (asset.publicUrl) {
          coverUrls.value[asset.id] = asset.publicUrl;
          missing.delete(asset.id);
        }
      if (!result.items.length || result.page * result.pageSize >= result.total) break;
      current++;
    }
  } catch {
    coverError.value = '部分商品封面暂未加载，可重新查询';
  }
}

async function query(next: number) {
  const result = await contentApi.products({
    keyword: keyword.value,
    type: type.value || undefined,
    status: status.value || undefined,
    page: next,
    pageSize: 15,
  });
  items.value = result.items;
  page.value = result.page;
  total.value = result.total;
  await resolveCovers(result.items);
}

async function load(next = 1) {
  await run(() => query(next));
}

async function publish(item: AdminProduct, value: boolean) {
  await run(
    async () => {
      await contentApi.setProductPublished(item.id, value, item.version);
      await query(page.value);
    },
    value ? '商品已上架' : '商品已下架',
  );
}

function typeName(t: string) {
  switch (t) {
    case 'COURSE':
      return '课程';
    case 'BOOK':
      return '图书';
    case 'MEMBERSHIP':
      return '会员';
    default:
      return t;
  }
}

function statusTag(s: string): { label: string; type: 'success' | 'warning' | 'info' } {
  switch (s) {
    case 'ON_SALE':
      return { label: '已上架', type: 'success' };
    case 'OFF_SALE':
      return { label: '已下架', type: 'info' };
    case 'DRAFT':
      return { label: '草稿', type: 'warning' };
    default:
      return { label: s, type: 'info' };
  }
}

function resetFilters() {
  keyword.value = '';
  type.value = '';
  status.value = '';
  return load(1);
}

onMounted(() => load());
</script>

<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <h1 class="brand-title">商品管理</h1>
        <p class="page-desc">管理课程、图书与会员商品，查看售价和上架状态</p>
      </div>
      <div class="header-user">
        <router-link to="/content/products/new">
          <el-button type="primary"><UiIcon name="plus" :size="16" />新建商品</el-button>
        </router-link>
      </div>
    </header>

    <main class="admin-content">
      <p v-if="coverError" role="alert">{{ coverError }}</p>
      <div v-if="error" class="mb-4">
        <el-alert :title="error" type="error" show-icon :closable="false" />
      </div>
      <div v-if="message" class="mb-4">
        <el-alert :title="message" type="success" show-icon :closable="false" />
      </div>

      <!-- 搜索过滤卡片 -->
      <div class="admin-card filter-card">
        <form class="filter-form" @submit.prevent="load(1)">
          <div class="form-item">
            <span class="label">商品关键词</span>
            <el-input v-model="keyword" placeholder="搜索标题或ID" style="width: 220px" clearable />
          </div>

          <div class="form-item">
            <span class="label">商品类型</span>
            <select v-model="type" class="ui-select">
              <option value="">全部类型</option>
              <option value="COURSE">视频课程</option>
              <option value="BOOK">实体图书</option>
              <option value="MEMBERSHIP">试卷会员</option>
            </select>
          </div>

          <div class="form-item">
            <span class="label">上架状态</span>
            <select v-model="status" class="ui-select">
              <option value="">全部状态</option>
              <option value="ON_SALE">在售中</option>
              <option value="OFF_SALE">已下架</option>
              <option value="DRAFT">草稿</option>
            </select>
          </div>

          <el-button type="primary" native-type="submit" :loading="busy">查询</el-button>
          <el-button :disabled="busy" @click="resetFilters">重置</el-button>
        </form>
      </div>

      <!-- 商品列表卡片 -->
      <div class="admin-card">
        <div class="table-wrap">
          <div class="ls-table-scroll">
            <table class="admin-table">
              <thead>
                <tr>
                  <th style="width: 45%">商品</th>
                  <th style="width: 18%">类型</th>
                  <th style="width: 120px">售价</th>
                  <th style="width: 100px">状态</th>
                  <th style="width: 180px; text-align: right">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in items" :key="item.id">
                  <td>
                    <div class="product-summary">
                      <div class="thumb-cell">
                        <BookCover
                          :title="item.title"
                          :image-url="item.coverAssetId ? coverUrls[item.coverAssetId] : null"
                        />
                      </div>
                      <div class="title-cell">
                        <span class="p-title">{{ item.title }}</span>
                        <span class="p-subtitle">{{ item.subtitle || '—' }}</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <el-tag size="small" effect="plain">{{ typeName(item.type) }}</el-tag>
                  </td>
                  <td>
                    <span class="price-val">¥{{ formatFen(item.priceFen) }}</span>
                  </td>
                  <td>
                    <el-tag size="small" :type="statusTag(item.status).type">
                      {{ statusTag(item.status).label }}
                    </el-tag>
                  </td>
                  <td style="text-align: right">
                    <HomeRecommendation
                      v-if="item.type === 'BOOK' || item.type === 'COURSE'"
                      :content-id="item.id"
                      :kind="item.type"
                      :disabled="busy || item.status !== 'ON_SALE'"
                    />
                    <router-link :to="`/content/products/${encodeURIComponent(item.id)}`">
                      <el-button size="small">编辑详情</el-button>
                    </router-link>
                    <el-button
                      size="small"
                      :type="item.status === 'ON_SALE' ? 'warning' : 'success'"
                      plain
                      :disabled="busy"
                      @click="publish(item, item.status !== 'ON_SALE')"
                    >
                      {{ item.status === 'ON_SALE' ? '下架' : '上架' }}
                    </el-button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="!items.length && !busy" class="empty-data">
          <span>暂无匹配的商品数据</span>
        </div>

        <!-- 底部分页 -->
        <div class="pagination-bar">
          <span class="total-info">共 {{ total }} 件商品 · 当前第 {{ page }} 页</span>
          <div class="pager-btns">
            <el-button size="small" :disabled="busy || page <= 1" @click="load(page - 1)"
              >上一页</el-button
            >
            <el-button size="small" :disabled="busy || page * 15 >= total" @click="load(page + 1)"
              >下一页</el-button
            >
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}

.mb-4 {
  margin-bottom: 16px;
}

.filter-card {
  padding: 16px 20px;
}

.filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;

  .form-item {
    display: flex;
    flex: 1 1 160px;
    min-width: 140px;
    flex-direction: column;
    align-items: stretch;
    gap: 6px;

    .label {
      font-size: 13px;
      color: #617088;
      white-space: nowrap;
    }
  }
  :deep(.el-input) {
    width: 100% !important;
  }
}

.ui-select {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border-radius: 6px;
  border: 1px solid #e5ebf3;
  background: #fff;
  font-size: 13px;
  color: #18283e;
}

.table-wrap {
  overflow-x: auto;
}

.admin-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  th {
    background: #f4f7fb;
    color: #617088;
    font-size: 12px;
    font-weight: 500;
    text-align: left;
    padding: 12px 14px;
    border-bottom: 1px solid #e5ebf3;
  }

  td {
    padding: 14px;
    border-bottom: 1px solid #f3f4f6;
    vertical-align: middle;
  }

  tr:hover td {
    background: #f4f7fb;
  }
}
.filter-card {
  margin-bottom: 0;
  border-radius: 12px 12px 0 0;
}
.filter-card + .admin-card {
  border-top: 0;
  border-radius: 0 0 12px 12px;
}
.product-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.thumb-cell {
  width: 56px;
  height: 42px;
  border-radius: 4px;
  overflow: hidden;
  background: #e5ebf3;

  .thumb-img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .thumb-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    color: #6b7280;
    background: #edf3ff;
  }
}

.title-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;

  .p-title {
    font-weight: 600;
    color: #1f2937;
  }

  .p-id {
    font-size: 11px;
    color: #9ca3af;
  }
  .p-subtitle {
    overflow: hidden;
    color: #617088;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.price-val {
  font-weight: 600;
  color: #18283e;
}

.empty-data {
  padding: 40px;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #e5ebf3;

  .total-info {
    font-size: 13px;
    color: #6b7280;
  }

  .pager-btns {
    display: flex;
    gap: 8px;
  }
}
.thumb-cell :deep(.ls-cover) {
  height: 100%;
}
.thumb-cell :deep(.ls-cover-art) {
  padding: 6px;
}
.thumb-cell :deep(.ls-cover-title) {
  font-size: 13px;
  letter-spacing: 0;
}
.thumb-cell :deep(.ls-cover-caption) {
  display: none;
}
</style>
