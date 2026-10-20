<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import { ApiError } from '@jxsc/shared';
import { LearningMutationCommittedError } from '@jxsc/shared/learning/admin-api';
import type { AdminProduct } from '@jxsc/shared/content/admin-types';
import type {
  AdminChapter,
  AdminCourse,
  AdminLearningAsset,
  ChapterWrite,
  CourseWrite,
} from '@jxsc/shared/learning/admin-types';
import {
  isReadyLearningAsset,
  validateChapter,
  validateCourse,
  validateCoursePublish,
} from '@jxsc/shared/learning/admin-validation';
import { learningApi } from '../../api/learning';
import { contentApi, uploadServices } from '../../api/content';
import { useLearningPage } from '../../composables/useLearningPage';
import AssetUpload from '../../components/AssetUpload.vue';
import ContentBlockEditor from '../../components/ContentBlockEditor.vue';
import ContentDraftPreview from '../../components/ContentDraftPreview.vue';
const route = useRoute(),
  router = useRouter();
const empty = (): CourseWrite => ({
  productId: '',
  version: 0,
  title: '',
  validityType: 'DAYS',
  validityDays: 365,
  contentBlocks: [],
});
const emptyChapter = (): ChapterWrite => ({
  version: 0,
  title: '',
  videoAssetId: '',
  isTrial: false,
  durationSeconds: undefined,
  enabled: true,
  sortNo: 0,
});
const form = ref(empty()),
  record = ref<AdminCourse | null>(null),
  saved = ref(''),
  loaded = ref(false),
  products = ref<AdminProduct[]>([]),
  productPage = ref(1),
  productTotal = ref(0),
  productKeyword = ref('');
const chapterForm = ref(emptyChapter()),
  chapterId = ref<string | null>(null),
  chapterAsset = ref<AdminLearningAsset>(),
  chapterSaved = ref(JSON.stringify(emptyChapter())),
  order = ref<AdminChapter[]>([]),
  assets = ref<AdminLearningAsset[]>([]),
  assetPage = ref(1),
  assetTotal = ref(0),
  assetKeyword = ref('');
const videoUploading = ref(false),
  contentUploading = ref(false);
const refreshRequired = ref(false);
const committedId = ref<string | null>(null);
const uploading = computed(() => videoUploading.value || contentUploading.value);
const { allowed, busy, error, message, conflict, epoch, run, validate, guardedUploads } =
  useLearningPage(() => {
    form.value = empty();
    record.value = null;
    saved.value = '';
    loaded.value = false;
    products.value = [];
    productPage.value = 1;
    productTotal.value = 0;
    productKeyword.value = '';
    refreshRequired.value = false;
    committedId.value = null;
    chapterForm.value = emptyChapter();
    chapterSaved.value = JSON.stringify(chapterForm.value);
    chapterId.value = null;
    chapterAsset.value = undefined;
    order.value = [];
    assets.value = [];
    assetPage.value = 1;
    assetTotal.value = 0;
    assetKeyword.value = '';
    videoUploading.value = contentUploading.value = false;
  });
const services = computed(() => {
  void epoch.value;
  return guardedUploads(uploadServices);
});
const currentId = computed(() =>
  route.params.id === 'new' ? committedId.value : String(route.params.id),
);
const dirty = computed(() => JSON.stringify(form.value) !== saved.value);
const chapterDirty = computed(() => JSON.stringify(chapterForm.value) !== chapterSaved.value);
const orderDirty = computed(
  () =>
    order.value.map((value) => value.id).join('|') !==
    record.value?.chapters.map((value) => value.id).join('|'),
);
function resetChapter() {
  chapterId.value = null;
  chapterForm.value = emptyChapter();
  chapterAsset.value = undefined;
  chapterSaved.value = JSON.stringify(chapterForm.value);
}
function accept(value: AdminCourse) {
  refreshRequired.value = false;
  record.value = value;
  form.value = {
    productId: value.productId,
    version: value.version,
    title: value.title,
    validityType: value.validityType,
    validityDays: value.validityDays,
    teacherName: value.teacherName,
    productVersion: value.productVersion,
    contentBlocks: value.contentBlocks.map((block) => ({ ...block })),
  };
  saved.value = JSON.stringify(form.value);
  order.value = [...value.chapters];
  loaded.value = true;
}
async function load() {
  if (uploading.value) return;
  if (route.query.recovery === '1') refreshRequired.value = true;
  await run(async (current) => {
    if (currentId.value) {
      const value = await learningApi.course(currentId.value);
      if (current()) {
        accept(value);
        resetChapter();
      }
    } else {
      const result = await contentApi.products({
        type: 'COURSE',
        keyword: productKeyword.value,
        page: 1,
        pageSize: 20,
      });
      if (current()) {
        products.value = result.items;
        productPage.value = result.page;
        productTotal.value = result.total;
        saved.value = JSON.stringify(form.value);
        loaded.value = true;
      }
    }
  });
}
async function mutation<T>(action: () => Promise<T>, current: () => boolean): Promise<T> {
  try {
    return await action();
  } catch (cause) {
    if (current() && cause instanceof LearningMutationCommittedError) {
      refreshRequired.value = true;
      if (cause.resource.kind === 'CHAPTER') {
        chapterId.value = cause.resource.id;
        chapterForm.value.version = cause.resource.version;
      } else {
        form.value.version = cause.resource.version;
        if (record.value) record.value.version = cause.resource.version;
        if (!currentId.value) {
          committedId.value = cause.resource.id;
          await router.replace({
            path: `/learning/courses/${encodeURIComponent(cause.resource.id)}`,
            query: { recovery: '1' },
          });
        }
      }
    }
    throw cause;
  }
}
async function loadProducts(next = 1) {
  await run(async (current) => {
    const result = await contentApi.products({
      type: 'COURSE',
      keyword: productKeyword.value,
      page: next,
      pageSize: 20,
    });
    if (current()) {
      products.value = result.items;
      productPage.value = result.page;
      productTotal.value = result.total;
    }
  });
}
async function save() {
  if (uploading.value || !loaded.value || conflict.value || refreshRequired.value) return;
  await run(async (current) => {
    const body = {
      ...form.value,
      validityDays: form.value.validityType === 'PERMANENT' ? null : form.value.validityDays,
    };
    validate(validateCourse(body));
    const product = await contentApi.product(body.productId);
    if (!current()) return;
    if (product.type !== 'COURSE') throw new ApiError('VALIDATION_ERROR', '请选择真实的课程商品');
    const pendingOrder = orderDirty.value ? order.value.map((chapter) => chapter.id) : null;
    const value = await mutation(() => learningApi.saveCourse(currentId.value, body), current);
    if (!current()) return;
    accept(value);
    if (pendingOrder)
      order.value.sort((a, b) => pendingOrder.indexOf(a.id) - pendingOrder.indexOf(b.id));
    if (!currentId.value) await router.replace(`/learning/courses/${encodeURIComponent(value.id)}`);
  }, '课程已保存');
}
function validityChanged() {
  if (form.value.validityType === 'PERMANENT') form.value.validityDays = null;
}
async function loadAssets(next = 1) {
  await run(async (current) => {
    const result = await contentApi.assets({
      page: next,
      pageSize: 10,
    });
    if (current()) {
      assets.value = result.items;
      assetPage.value = result.page;
      assetTotal.value = result.total;
    }
  });
}
function bind(asset: AdminLearningAsset) {
  if (!allowed.value || !loaded.value) return;
  const errors = validateChapter(
    { ...chapterForm.value, title: chapterForm.value.title || '章节', videoAssetId: asset.id },
    asset,
  );
  if (errors.videoAssetId) {
    error.value = errors.videoAssetId;
    return;
  }
  chapterAsset.value = asset;
  chapterForm.value.videoAssetId = asset.id;
  chapterForm.value.durationSeconds = asset.durationSeconds;
  if (!chapterId.value) chapterForm.value.sortNo = order.value.length;
}
function editChapter(chapter: AdminChapter) {
  if (busy.value || uploading.value) return;
  chapterId.value = chapter.id;
  chapterForm.value = {
    version: chapter.version,
    title: chapter.title,
    videoAssetId: chapter.videoAssetId,
    isTrial: chapter.isTrial,
    durationSeconds: chapter.durationSeconds,
    enabled: chapter.enabled,
    sortNo: chapter.sortNo,
  };
  chapterAsset.value = chapter.video;
  chapterSaved.value = JSON.stringify(chapterForm.value);
}
async function saveChapter() {
  if (!record.value || uploading.value || conflict.value || refreshRequired.value) return;
  await run(async (current) => {
    if (dirty.value || orderDirty.value)
      throw new ApiError('VALIDATION_ERROR', '请先保存课程信息与章节排序');
    validate(validateChapter(chapterForm.value, chapterAsset.value));
    if (!chapterAsset.value) throw new ApiError('VALIDATION_ERROR', '请绑定已就绪视频');
    const id = record.value!.id;
    await mutation(
      () => learningApi.saveChapter(id, chapterId.value, { ...chapterForm.value }),
      current,
    );
    if (!current()) return;
    refreshRequired.value = true;
    const value = await learningApi.course(id);
    if (current()) {
      accept(value);
      resetChapter();
    }
  }, '章节已保存');
}
function move(index: number, delta: number) {
  if (busy.value || uploading.value) return;
  const next = index + delta;
  if (next < 0 || next >= order.value.length) return;
  const value = order.value.splice(index, 1)[0]!;
  order.value.splice(next, 0, value);
}
async function saveOrder() {
  if (!record.value || uploading.value || conflict.value || refreshRequired.value) return;
  await run(async (current) => {
    if (dirty.value) throw new ApiError('VALIDATION_ERROR', '请先保存课程信息');
    const id = record.value!.id;
    await mutation(
      () =>
        learningApi.reorderChapters(id, {
          version: record.value!.version,
          chapters: order.value.map((chapter) => chapter.id),
        }),
      current,
    );
    if (!current()) return;
    refreshRequired.value = true;
    const value = await learningApi.course(id);
    if (current()) {
      accept(value);
      const edited = value.chapters.find((chapter) => chapter.id === chapterId.value);
      if (edited) {
        chapterForm.value.version = edited.version;
        chapterForm.value.sortNo = edited.sortNo;
      }
    }
  }, '章节排序已保存');
}
async function publish() {
  if (!record.value || uploading.value || conflict.value || refreshRequired.value) return;
  await run(async (current) => {
    if (dirty.value || chapterDirty.value || orderDirty.value)
      throw new ApiError('VALIDATION_ERROR', '请先保存课程、章节和排序修改');
    const id = record.value!.id,
      publish = record.value!.status !== 'ON_SALE';
    const course = await learningApi.course(id);
    if (!current()) return;
    if (publish) validate(validateCoursePublish(course));
    const product = await contentApi.product(course.productId);
    if (!current()) return;
    if (product.type !== 'COURSE') throw new ApiError('VALIDATION_ERROR', '绑定商品不是课程');
    await contentApi.setProductPublished(product.id, publish, product.version);
    if (!current()) return;
    refreshRequired.value = true;
    const value = await learningApi.course(id);
    if (current()) accept(value);
  }, '课程状态已更新');
}
watch(() => route.fullPath, load);
onMounted(load);
</script>
<template>
  <main class="ls-admin-page learning-editor">
    <header class="page-header">
      <div>
        <h1>{{ currentId ? '编辑课程' : '新建课程' }}</h1>
        <span class="page-desc">维护课程内容、观看有效期与章节</span>
      </div>
      <router-link class="ls-button" to="/learning/courses">返回课程列表</router-link>
    </header>
    <p v-if="!allowed" role="alert">没有内容管理权限</p>
    <template v-else>
      <p v-if="busy" role="status">处理中</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="refreshRequired" role="status">
        课程操作已提交，关联商品内容可能尚未保存。需重新读取课程和关联商品，核对最新版本及保存范围后再继续。
      </p>
      <el-button v-if="!loaded" :disabled="busy || uploading" @click="load">{{
        currentId ? '重新读取课程（丢弃本地修改）' : '重新读取'
      }}</el-button>
      <template v-if="loaded">
        <div class="content-editor-layout">
          <div class="learning-editor-main">
            <p v-if="conflict">版本已变化，本地输入已保留。请先复制需保留的修改，再重新读取。</p>
            <p>
              观看有效期修改仅影响新订单，已有订单快照保持不变。下架阻止新购买，不撤销已购有效权益。
            </p>
            <form id="course-editor-form" @submit.prevent="save">
              <fieldset class="learning-fields" :disabled="busy || uploading">
                <legend>课程信息</legend>
                <p v-if="record">
                  <router-link :to="'/content/products/' + encodeURIComponent(record.productId)"
                    >维护关联商品售价与介绍</router-link
                  >
                </p>
                <details v-if="record">
                  <summary>关联商品详情</summary>
                  商品编号：{{ record.productId }}
                </details>
                <fieldset v-else>
                  <legend>第一步：选择课程商品</legend>
                  <p>先在商品管理创建课程商品并填写售价，再回到此处绑定，维护课程内容与有效期。</p>
                  <router-link to="/content/products/new">创建课程商品</router-link>
                  <label>商品关键词 <input v-model="productKeyword" name="productKeyword" /></label
                  ><el-button :disabled="busy" @click="loadProducts()">查找课程商品</el-button>
                  <label
                    >课程商品
                    <select v-model="form.productId" name="productId">
                      <option value="">请选择</option>
                      <option v-for="product in products" :key="product.id" :value="product.id">
                        {{ product.title }}（{{ product.id }}）
                      </option>
                    </select></label
                  >
                  <el-button
                    :disabled="busy || productPage <= 1"
                    @click="loadProducts(productPage - 1)"
                    >上一页商品</el-button
                  ><el-button
                    :disabled="busy || productPage * 20 >= productTotal"
                    @click="loadProducts(productPage + 1)"
                    >下一页商品</el-button
                  >
                </fieldset>
                <label>标题 <input v-model="form.title" name="title" maxlength="100" /></label
                ><label
                  >观看有效期
                  <select v-model="form.validityType" name="validityType" @change="validityChanged">
                    <option value="DAYS">固定天数</option>
                    <option value="PERMANENT">永久有效</option>
                  </select></label
                >
                <label v-if="form.validityType === 'DAYS'"
                  >有效天数
                  <input
                    v-model.number="form.validityDays"
                    name="validityDays"
                    type="number"
                    min="1"
                    step="1"
                /></label>
                <ContentBlockEditor
                  :key="epoch"
                  v-model="form.contentBlocks"
                  :services="services"
                  @busy="contentUploading = $event"
                />
              </fieldset>
            </form>
            <template v-if="record">
              <section class="admin-card chapter-section">
                <h2>课程章节</h2>
                <p>章节时长与观看有效期分别维护。素材未提供媒体时长时，请填写实际视频时长。</p>
                <ol class="chapter-list">
                  <li v-for="(chapter, index) in order" :key="chapter.id">
                    {{ chapter.title }} · {{ chapter.durationSeconds }} 秒 ·
                    {{ chapter.isTrial ? '试看' : '付费' }} ·
                    {{ chapter.video ? uiStatus(chapter.video.status) : '未绑定视频' }} /
                    {{ chapter.video ? uiStatus(chapter.video.scanStatus) : '—' }}
                    <el-button :disabled="busy || uploading" @click="editChapter(chapter)"
                      >编辑章节</el-button
                    ><el-button
                      :disabled="busy || uploading || index === 0"
                      @click="move(index, -1)"
                      >上移章节</el-button
                    ><el-button
                      :disabled="busy || uploading || index === order.length - 1"
                      @click="move(index, 1)"
                      >下移章节</el-button
                    >
                  </li>
                </ol>
                <el-button
                  :disabled="busy || uploading || !orderDirty || conflict || refreshRequired"
                  @click="saveOrder"
                  >保存章节排序</el-button
                >
                <form @submit.prevent="saveChapter">
                  <fieldset class="chapter-editor" :disabled="busy || uploading">
                    <legend>{{ chapterId ? '编辑章节' : '添加章节' }}</legend>
                    <div class="chapter-toggles">
                      <label
                        ><input
                          v-model="chapterForm.enabled"
                          name="chapterEnabled"
                          type="checkbox"
                        />启用章节</label
                      >
                      <label
                        ><input
                          v-model="chapterForm.isTrial"
                          name="isTrial"
                          type="checkbox"
                        />允许试看</label
                      >
                    </div>
                    <div class="chapter-editor-grid">
                      <label
                        >实际时长（秒）<input
                          v-model.number="chapterForm.durationSeconds"
                          name="durationSeconds"
                          type="number"
                          min="1"
                          max="86400"
                          step="1"
                      /></label>
                      <label
                        >章节标题
                        <input v-model="chapterForm.title" name="chapterTitle" maxlength="100"
                      /></label>
                    </div>
                    <p v-if="chapterAsset">
                      已绑定：{{ chapterAsset.name }}（{{ chapterAsset.id }}） ·
                      {{ uiStatus(chapterAsset.status) }} /
                      {{ uiStatus(chapterAsset.scanStatus) }} ·
                      {{ chapterAsset.durationSeconds ?? '未提供' }} 秒
                    </p>
                    <AssetUpload
                      :key="epoch"
                      :services="services"
                      purpose="COURSE_VIDEO"
                      :disabled="busy"
                      @ready="bind"
                      @busy="videoUploading = $event"
                    />
                    <el-button :disabled="busy || uploading" @click="loadAssets()"
                      >浏览素材</el-button
                    >
                    <ul>
                      <li v-for="asset in assets" :key="asset.id">
                        {{ asset.name }} · {{ uiStatus(asset.status) }} /
                        {{ uiStatus(asset.scanStatus) }} ·
                        {{ asset.durationSeconds ?? '未提供' }} 秒
                        <el-button
                          :disabled="
                            busy || uploading || !isReadyLearningAsset(asset, 'COURSE_VIDEO')
                          "
                          @click="bind(asset)"
                          >绑定视频</el-button
                        >
                      </li>
                    </ul>
                    <el-button
                      :disabled="busy || uploading || assetPage <= 1"
                      @click="loadAssets(assetPage - 1)"
                      >上一页视频</el-button
                    ><el-button
                      :disabled="busy || uploading || assetPage * 10 >= assetTotal"
                      @click="loadAssets(assetPage + 1)"
                      >下一页视频</el-button
                    >
                    <el-button
                      :disabled="busy || uploading || conflict || refreshRequired"
                      @click="saveChapter"
                      >保存章节</el-button
                    ><el-button :disabled="busy || uploading" @click="resetChapter"
                      >清空并新增章节</el-button
                    >
                  </fieldset>
                </form>
              </section>
            </template>
            <p v-else>先保存课程后配置章节。</p>
          </div>
          <aside class="content-editor-sidebar">
            <section class="admin-card">
              <h3>发布设置</h3>
              <span class="ls-badge">{{ record ? uiStatus(record.status) : '新草稿' }}</span>
              <p class="publish-help">
                {{
                  dirty || chapterDirty || orderDirty
                    ? '有未保存修改'
                    : record
                      ? '已保存'
                      : '尚未保存'
                }}。请分别保存课程、章节和排序后，再调整上架状态。
              </p>
              <div class="publish-actions">
                <el-button
                  type="primary"
                  native-type="button"
                  @click="save"
                  :disabled="busy || uploading || conflict || refreshRequired"
                  >保存课程</el-button
                >
                <el-button
                  v-if="record"
                  :disabled="busy || uploading || conflict || refreshRequired"
                  @click="publish"
                  >{{ record.status === 'ON_SALE' ? '下架课程' : '上架课程' }}</el-button
                >
                <el-button :disabled="busy || uploading" @click="load">{{
                  currentId ? '重新读取课程（丢弃本地修改）' : '重新读取'
                }}</el-button>
              </div>
            </section>
            <ContentDraftPreview
              :title="form.title"
              :blocks="form.contentBlocks"
              :show-cover="false"
            />
            <section class="admin-card">
              <h3>课程概览</h3>
              <p>
                {{
                  form.validityType === 'PERMANENT'
                    ? '永久有效'
                    : `购买后 ${form.validityDays ?? '—'} 天有效`
                }}
              </p>
              <p>共 {{ order.length }} 个章节</p>
              <p class="publish-help">售价和封面在关联商品中维护。</p>
            </section>
          </aside>
        </div>
      </template>
    </template>
  </main>
</template>
<style scoped>
.chapter-toggles {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.chapter-editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.chapter-toggles label,
.chapter-editor-grid label {
  min-width: 0;
  margin: 0;
}
@media (max-width: 560px) {
  .chapter-editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
