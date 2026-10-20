<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminAsset, AssetPurpose } from '@jxsc/shared/content/admin-types';
import { contentApi, uploadServices } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';
import AssetUpload from '../../components/AssetUpload.vue';
const { busy, error, message, run } = useContentEditor();
function fileSize(bytes: number) {
  return bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
    : `${(bytes / 1024).toFixed(1)} KiB`;
}
const uploading = ref(false);
const items = ref<AdminAsset[]>([]),
  page = ref(1),
  total = ref(0),
  purpose = ref<AssetPurpose>('PUBLIC_IMAGE'),
  reason = ref('');
async function query(next: number) {
  const result = await contentApi.assets({
    page: next,
    pageSize: 20,
  });
  items.value = result.items;
  page.value = result.page;
  total.value = result.total;
}
async function load(next = 1) {
  await run(() => query(next));
}
async function disable(asset: AdminAsset) {
  if (!reason.value.trim()) {
    error.value = '请先填写停用原因';
    return;
  }
  await run(async () => {
    await contentApi.disableAsset(asset.id, asset.version, reason.value.trim());
    await query(page.value);
  }, '资源已停用');
}
onMounted(() => load());
</script>
<template>
  <main class="ls-admin-page">
    <header class="page-header">
      <div>
        <h1>资源管理</h1>
        <p class="page-desc">管理公开图片、试卷附件与课程视频，上传完成后可在内容编辑页引用</p>
      </div>
      <router-link class="ls-button" to="/">返回后台</router-link>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="busy" role="status">处理中</p>
    <section class="admin-card asset-upload-card" aria-label="上传资源">
      <div class="filter-row">
        <label
          >上传用途
          <select v-model="purpose" :disabled="uploading">
            <option value="PUBLIC_IMAGE">公开图片</option>
            <option value="PAPER_ATTACHMENT">试卷附件</option>
            <option value="COURSE_VIDEO">课程视频</option>
          </select></label
        >
      </div>
      <AssetUpload
        :key="purpose"
        :services="uploadServices"
        :purpose="purpose"
        @busy="uploading = $event"
        @ready="load()"
      />
    </section>
    <section class="table-card" aria-label="资源列表">
      <div class="admin-card__header">
        <div>
          <h2>资源列表</h2>
          <p class="page-desc">共 {{ total }} 个资源 · 当前展示全部用途的分页结果</p>
        </div>
        <el-button :disabled="busy" @click="load()">刷新资源</el-button>
      </div>
      <div class="filter-row">
        <label>停用原因 <input v-model="reason" placeholder="停用资源前请填写原因" /></label>
        <span class="page-desc">停用后不可再被新内容引用，请确认后操作。</span>
      </div>
      <ul class="asset-grid" aria-label="素材卡片">
        <li v-for="asset in items" :key="asset.id" class="asset-card">
          <div class="asset-visual">
            <img
              v-if="asset.purpose === 'PUBLIC_IMAGE' && asset.publicUrl"
              class="asset-preview"
              :src="asset.publicUrl"
              :alt="asset.name"
              loading="lazy"
            />
            <span v-else class="asset-file-type" aria-hidden="true">{{ asset.extension }}</span>
          </div>
          <div class="asset-card-body">
            <h3 class="asset-name">{{ asset.name }}</h3>
            <p class="asset-description">
              {{ uiStatus(asset.purpose) }} · {{ asset.extension }} / {{ fileSize(asset.bytes) }}
            </p>
            <div class="asset-card-actions">
              <span
                class="ls-badge"
                :class="{
                  'ls-badge--success': asset.status === 'READY',
                  'ls-badge--warning': asset.status === 'PENDING' || asset.status === 'UPLOADED',
                  'ls-badge--danger': asset.status === 'REJECTED',
                }"
                >{{ uiStatus(asset.status) }}</span
              >
              <el-button :disabled="busy || asset.status === 'DISABLED'" @click="disable(asset)"
                >停用</el-button
              >
            </div>
            <details>
              <summary>素材详情</summary>
              <p>编号：{{ asset.id }} · 版本：{{ asset.version }}</p>
              <p>
                素材状态：{{ uiStatus(asset.status) }} · 检查状态：{{ uiStatus(asset.scanStatus) }}
              </p>
            </details>
          </div>
        </li>
      </ul>
      <p v-if="!items.length && !busy" class="empty-state">没有匹配资源</p>
      <div class="pagination-bar">
        <span>第 {{ page }} 页，共 {{ total }} 条</span>
        <div class="pagination-actions">
          <el-button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</el-button>
          <el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)"
            >下一页</el-button
          >
        </div>
      </div>
    </section>
  </main>
</template>
<style lang="scss" scoped>
.asset-upload-card :deep(.asset-upload) {
  margin-bottom: 0;
}
.asset-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.asset-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  background: white;
}
.asset-visual {
  position: relative;
  display: grid;
  place-items: center;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: #f4f7fb;
}
.asset-preview,
.asset-file-type {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.asset-preview {
  object-fit: contain;
}
.asset-file-type {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  color: #617088;
}
.asset-card-body {
  padding: 14px;
}
.asset-name {
  margin: 0 0 7px;
  overflow-wrap: anywhere;
  font-weight: 600;
}
.asset-description {
  margin: 0 0 12px;
  color: #617088;
  font-size: 12px;
}
.asset-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.asset-card details {
  margin-top: 8px;
  color: #617088;
}
.asset-card details p {
  margin: 8px 0 0;
  overflow-wrap: anywhere;
}
.admin-card__header h2 {
  margin-bottom: 5px;
}
.admin-card__header p {
  margin-bottom: 0;
}
@media (max-width: 736px) {
  .asset-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  .admin-card__header {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
