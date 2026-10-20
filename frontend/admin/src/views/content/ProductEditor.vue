<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { ElButton } from 'element-plus';
import { formatFen } from '@jxsc/shared';
import type { AdminProduct, AdminCategory, ProductForm } from '@jxsc/shared/content/admin-types';
import { productWrite } from '@jxsc/shared/content/validation';
import { contentApi } from '../../api/content';
import { useContentEditor } from '../../composables/useContentEditor';
import AssetPicker from '../../components/AssetPicker.vue';
import ContentBlockEditor from '../../components/ContentBlockEditor.vue';
import ContentDraftPreview from '../../components/ContentDraftPreview.vue';
const route = useRoute();
const router = useRouter();
const coverUploading = ref(false),
  contentUploading = ref(false);
const uploading = computed(() => coverUploading.value || contentUploading.value);
const currentId = ref<string | null>(route.params.id === 'new' ? null : String(route.params.id));
const { busy, error, message, run, invalidate } = useContentEditor();
const record = ref<AdminProduct | null>(null),
  saved = ref('');
const emptyForm = (): ProductForm => ({
  version: 0,
  type: 'BOOK',
  title: '',
  subtitle: '',
  price: '',
  originalPrice: '',
  coverAssetId: '',
  categoryIds: [],
  contentBlocks: [{ type: 'TEXT', text: '' }],
  quantityLimit: 1,
  shippingSummary: '',
  durationMonths: 6,
  sortNo: 0,
});
const form = ref<ProductForm>(emptyForm());
const categories = ref<AdminCategory[]>([]),
  categoryPage = ref(1),
  categoryTotal = ref(0);
const dirty = computed(() => !!saved.value && JSON.stringify(form.value) !== saved.value);
function accept(value: AdminProduct) {
  record.value = value;
  currentId.value = value.id;
  form.value = {
    version: value.version,
    type: value.type,
    title: value.title,
    subtitle: value.subtitle ?? '',
    price: formatFen(value.priceFen),
    originalPrice: value.originalPriceFen === null ? '' : formatFen(value.originalPriceFen),
    coverAssetId: value.coverAssetId ?? '',
    categoryIds: [...value.categoryIds],
    contentBlocks: value.contentBlocks.map((block) => ({ ...block })),
    quantityLimit: value.book?.quantityLimit ?? 1,
    shippingSummary: value.book?.shippingSummary ?? '',
    durationMonths: value.membership?.durationMonths ?? 6,
    sortNo: value.sortNo,
  };
  saved.value = JSON.stringify(form.value);
}
async function queryCategories(page = 1, current: () => boolean = () => true) {
  const value = await contentApi.categories({ type: form.value.type, page, pageSize: 20 });
  if (!current()) return;
  categories.value = value.items;
  categoryPage.value = page;
  categoryTotal.value = value.total;
}
async function loadCategories(page = 1) {
  await run((current) => queryCategories(page, current));
}
async function load() {
  if (uploading.value) return;
  await run(async (current) => {
    if (currentId.value) {
      const value = await contentApi.product(currentId.value);
      if (!current()) return;
      accept(value);
    } else saved.value = JSON.stringify(form.value);
    await queryCategories(1, current);
  });
}
async function save() {
  if (uploading.value) return;
  await run(async (current) => {
    const value = await contentApi.saveProduct(currentId.value, productWrite(form.value));
    if (!current()) return;
    accept(value);
    if (route.params.id === 'new')
      await router.replace(`/content/products/${encodeURIComponent(currentId.value!)}`);
  }, '商品已保存');
}
async function publish() {
  if (uploading.value) return;
  if (!record.value) return;
  if (dirty.value) {
    error.value = '请先保存修改再上下架';
    return;
  }
  await run(async (current) => {
    const value = await contentApi.setProductPublished(
      record.value!.id,
      record.value!.status !== 'ON_SALE',
      record.value!.version,
    );
    if (current()) accept(value);
  }, '商品状态已更新');
}
function changeType() {
  form.value.categoryIds = [];
  void loadCategories();
}
onBeforeRouteLeave(() => !dirty.value || window.confirm('有未保存修改，确定离开？'));
onBeforeRouteUpdate((to, from) => {
  if (to.params.id === from.params.id) return;
  if (uploading.value) return false;
  return !dirty.value || window.confirm('有未保存修改，确定切换商品？');
});
watch(
  () => route.params.id,
  (id) => {
    // Saving a new product already accepted this authoritative record.
    if (id === currentId.value && record.value?.id === id) return;
    invalidate();
    currentId.value = id === 'new' ? null : String(id);
    record.value = null;
    form.value = emptyForm();
    saved.value = '';
    categories.value = [];
    categoryPage.value = 1;
    categoryTotal.value = 0;
    void load();
  },
);
onMounted(load);
</script>
<template>
  <main class="ls-admin-page product-editor">
    <header class="page-header">
      <div>
        <h1>{{ currentId ? '编辑商品' : '新建商品' }}</h1>
        <span class="page-desc">编辑商品信息、售价与图文介绍</span>
      </div>
      <router-link class="ls-button" to="/content/products">返回商品列表</router-link>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="busy" role="status">处理中</p>
    <form class="content-editor-layout" @submit.prevent="save">
      <fieldset class="product-fields" :disabled="busy || uploading">
        <legend>商品信息</legend>
        <label
          >商品类型
          <select
            v-model="form.type"
            :disabled="!!record && !record.typeEditable"
            @change="changeType"
          >
            <option value="BOOK">图书</option>
            <option value="COURSE">课程</option>
            <option value="MEMBERSHIP">会员</option>
          </select></label
        >
        <label>标题 <input v-model="form.title" maxlength="100" required /></label
        ><label>副标题 <input v-model="form.subtitle" /></label
        ><label>售价（元）<input v-model="form.price" inputmode="decimal" required /></label
        ><label>原价（元，可空）<input v-model="form.originalPrice" inputmode="decimal" /></label
        ><label>排序 <input v-model.number="form.sortNo" type="number" step="1" /></label>
        <AssetPicker v-model="form.coverAssetId" @busy="coverUploading = $event" />
        <fieldset>
          <legend>分类</legend>
          <p>已选：{{ form.categoryIds.join(', ') || '无' }}</p>
          <label v-for="item in categories" :key="item.id"
            ><input
              v-model="form.categoryIds"
              type="checkbox"
              :value="item.id"
              :disabled="!item.enabled"
            />{{ item.name }} {{ item.enabled ? '' : '（已停用）' }}</label
          ><el-button
            :disabled="busy || categoryPage <= 1"
            @click="loadCategories(categoryPage - 1)"
            >上一页分类</el-button
          ><el-button
            :disabled="busy || categoryPage * 20 >= categoryTotal"
            @click="loadCategories(categoryPage + 1)"
            >下一页分类</el-button
          >
        </fieldset>
        <template v-if="form.type === 'BOOK'"
          ><label
            >单次限购
            <input
              v-model.number="form.quantityLimit"
              type="number"
              min="1"
              max="99"
              step="1" /></label
          ><label>配送说明 <textarea v-model="form.shippingSummary" required /></label>
          <p>库存由库存管理独立调整。</p></template
        >
        <template v-else-if="form.type === 'MEMBERSHIP'"
          ><label
            >会员期限
            <select v-model="form.durationMonths" :disabled="!!record">
              <option :value="6">6个月</option>
              <option :value="12">12个月</option>
            </select></label
          >
          <p v-if="record">已有会员期限通过会员计划配置维护。</p></template
        >
        <p v-else>
          保存商品草稿后，请到
          <router-link to="/learning/courses">课程管理</router-link>
          关联该商品并保存观看有效期，再配置章节和视频。
        </p>
        <ContentBlockEditor v-model="form.contentBlocks" @busy="contentUploading = $event" />
      </fieldset>
      <aside class="content-editor-sidebar">
        <section class="admin-card">
          <h3>发布设置</h3>
          <span class="ls-badge">{{ record ? uiStatus(record.status) : '新草稿' }}</span>
          <p class="publish-help">
            {{
              dirty ? '有未保存修改' : record ? '已保存' : '尚未保存'
            }}。保存修改后，再按需上架或下架；在售商品的修改保存后会更新用户端内容。
          </p>
          <div class="publish-actions">
            <el-button type="primary" native-type="submit" :disabled="busy || uploading"
              >保存商品</el-button
            >
            <el-button v-if="record" :disabled="busy || uploading || dirty" @click="publish">{{
              record.status === 'ON_SALE' ? '下架商品' : '上架商品'
            }}</el-button>
            <el-button v-if="currentId" :disabled="busy || uploading" @click="load"
              >重新读取商品（丢弃本地修改）</el-button
            >
          </div>
        </section>
        <ContentDraftPreview
          :title="form.title"
          :subtitle="form.subtitle"
          :asset-id="form.coverAssetId"
          :blocks="form.contentBlocks"
          :price="form.price"
        />
      </aside>
    </form>
  </main>
</template>
<style scoped>
.product-fields > label {
  width: calc(50% - 16px);
  min-width: 0;
}
.product-fields > label input,
.product-fields > label textarea,
.product-fields > label select {
  width: 100%;
}
@media (max-width: 736px) {
  .product-fields > label {
    width: 100%;
  }
}
</style>
