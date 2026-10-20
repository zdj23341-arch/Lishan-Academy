<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import { ApiError } from '@jxsc/shared';
import { LearningMutationCommittedError } from '@jxsc/shared/learning/admin-api';
import type { AdminLearningAsset, AdminPaper, PaperWrite } from '@jxsc/shared/learning/admin-types';
import { isReadyLearningAsset, validatePaper } from '@jxsc/shared/learning/admin-validation';
import { learningApi } from '../../api/learning';
import { contentApi, uploadServices } from '../../api/content';
import { useLearningPage } from '../../composables/useLearningPage';
import AssetUpload from '../../components/AssetUpload.vue';
import AssetPicker from '../../components/AssetPicker.vue';
import ContentBlockEditor from '../../components/ContentBlockEditor.vue';
import ContentDraftPreview from '../../components/ContentDraftPreview.vue';
const route = useRoute(),
  router = useRouter();
const schoolStages = ['PRIMARY', 'JUNIOR', 'SENIOR'];
const subjects = [
  'CHINESE',
  'MATH',
  'ENGLISH',
  'PHYSICS',
  'CHEMISTRY',
  'BIOLOGY',
  'POLITICS',
  'HISTORY',
  'GEOGRAPHY',
];
const grades = [
  ['1', '一年级'],
  ['2', '二年级'],
  ['3', '三年级'],
  ['4', '四年级'],
  ['5', '五年级'],
  ['6', '六年级'],
  ['7', '七年级'],
  ['8', '八年级'],
  ['9', '九年级'],
  ['10', '高一'],
  ['11', '高二'],
  ['12', '高三'],
];
const empty = (): PaperWrite => ({
  version: 0,
  title: '',
  paperType: 'PRACTICE',
  schoolStage: '',
  grade: '',
  subject: '',
  year: null,
  coverAssetId: null,
  summary: '',
  contentBlocks: [],
  attachmentIds: [],
  visibility: 'HIDDEN',
  status: 'DRAFT',
});
const form = ref(empty()),
  record = ref<AdminPaper | null>(null),
  loaded = ref(false),
  attachments = ref<AdminLearningAsset[]>([]),
  assets = ref<AdminLearningAsset[]>([]),
  assetPage = ref(1),
  assetTotal = ref(0),
  assetKeyword = ref(''),
  saved = ref(''),
  coverUploading = ref(false),
  attachmentUploading = ref(false),
  contentUploading = ref(false);
const uploading = computed(
  () => coverUploading.value || attachmentUploading.value || contentUploading.value,
);
const fieldErrors = ref<Record<string, string>>({});
const refreshRequired = ref(false),
  committedId = ref<string | null>(null);
const { allowed, busy, error, message, conflict, epoch, run, validate, guardedUploads } =
  useLearningPage(() => {
    form.value = empty();
    record.value = null;
    refreshRequired.value = false;
    committedId.value = null;
    loaded.value = false;
    attachments.value = assets.value = [];
    assetPage.value = 1;
    assetTotal.value = 0;
    assetKeyword.value = '';
    saved.value = '';
    fieldErrors.value = {};
    coverUploading.value = attachmentUploading.value = contentUploading.value = false;
  });
const services = computed(() => {
  void epoch.value;
  return guardedUploads(uploadServices);
});
const dirty = computed(() => JSON.stringify(form.value) !== saved.value);
const currentId = computed(() =>
  route.params.id === 'new' ? committedId.value : String(route.params.id),
);
function accept(value: AdminPaper) {
  refreshRequired.value = false;
  record.value = value;
  form.value = {
    version: value.version,
    title: value.title,
    paperType: value.paperType ?? 'PRACTICE',
    stage: value.stage,
    schoolStage: value.schoolStage,
    grade: value.grade,
    subject: value.subject,
    year: value.year,
    coverAssetId: value.coverAssetId ?? null,
    attachmentMetadata: value.attachmentMetadata?.map((item) => ({ ...item })),
    summary: value.summary,
    contentBlocks: value.contentBlocks.map((block) => ({ ...block })),
    attachmentIds: [...value.attachmentIds],
    visibility: value.visibility,
    status: value.status,
  };
  attachments.value = [...value.attachments];
  saved.value = JSON.stringify(form.value);
  loaded.value = true;
}
async function load() {
  if (uploading.value) return;
  if (route.query.recovery === '1') refreshRequired.value = true;
  await run(async (current) => {
    fieldErrors.value = {};
    if (currentId.value) {
      const value = await learningApi.paper(currentId.value);
      if (current()) accept(value);
    } else if (current()) {
      saved.value = JSON.stringify(form.value);
      loaded.value = true;
    }
  });
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
  if (!isReadyLearningAsset(asset, 'PAPER_ATTACHMENT')) {
    error.value = '试卷附件必须是已就绪的Word、PDF或图片';
    return;
  }
  if (!form.value.attachmentIds.includes(asset.id)) {
    form.value.attachmentIds.push(asset.id);
    attachments.value.push(asset);
    form.value.attachmentMetadata ??= [];
    form.value.attachmentMetadata.push({
      assetId: asset.id,
      title: asset.name,
      sortNo: Math.max(-1, ...form.value.attachmentMetadata.map((item) => item.sortNo ?? 0)) + 1,
    });
  }
}
function remove(id: string) {
  form.value.attachmentIds = form.value.attachmentIds.filter((value) => value !== id);
  attachments.value = attachments.value.filter((value) => value.id !== id);
}
async function save() {
  if (uploading.value || !loaded.value || conflict.value || refreshRequired.value) return;
  await run(async (current) => {
    const body = { ...form.value, year: String(form.value.year ?? '').trim() || null };
    fieldErrors.value = validatePaper(body, attachments.value);
    validate(fieldErrors.value);
    let value: AdminPaper;
    try {
      value = await learningApi.savePaper(currentId.value, body);
    } catch (cause) {
      if (current() && cause instanceof ApiError) fieldErrors.value = { ...cause.fieldErrors };
      if (current() && cause instanceof LearningMutationCommittedError) {
        refreshRequired.value = true;
        form.value.version = cause.resource.version;
        if (record.value) record.value.version = cause.resource.version;
        if (!currentId.value) {
          committedId.value = cause.resource.id;
          await router.replace({
            path: `/learning/papers/${encodeURIComponent(cause.resource.id)}`,
            query: { recovery: '1' },
          });
        }
      }
      throw cause;
    }
    if (!current()) return;
    accept(value);
    if (!currentId.value) await router.replace(`/learning/papers/${encodeURIComponent(value.id)}`);
  }, '试卷已保存');
}
watch(() => route.fullPath, load);
onBeforeRouteLeave(() => {
  if (uploading.value) return true;
  return !loaded.value || !dirty.value || window.confirm('试卷还有未保存修改，确定离开吗？');
});
onMounted(load);
</script>
<template>
  <main class="ls-admin-page learning-editor">
    <header class="page-header">
      <div>
        <h1>{{ currentId ? '编辑试卷' : '新建试卷' }}</h1>
        <span class="page-desc">管理试卷信息、公开样张与授权附件</span>
      </div>
      <router-link class="ls-button" to="/learning/papers">返回试卷列表</router-link>
    </header>
    <p v-if="!allowed" role="alert">没有内容管理权限</p>
    <template v-else>
      <p v-if="busy" role="status">处理中</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="refreshRequired" role="status">操作已提交，需重新读取试卷以确认最新版本后再继续。</p>
      <el-button v-if="!loaded" :disabled="busy || uploading" @click="load">{{
        currentId ? '重新读取试卷（丢弃本地修改）' : '重新读取'
      }}</el-button>
      <template v-if="loaded">
        <p v-if="conflict">版本已变化，本地输入已保留。请先复制需保留的修改，再重新读取。</p>
        <form class="content-editor-layout" @submit.prevent="save">
          <fieldset class="learning-fields" :disabled="busy || uploading">
            <legend>试卷信息</legend>
            <label
              >标题
              <input
                v-model="form.title"
                name="title"
                maxlength="100"
                :aria-invalid="!!fieldErrors.title"
                aria-describedby="paper-title-error"
              />
              <span v-if="fieldErrors.title" id="paper-title-error" class="paper-field-error">{{
                fieldErrors.title
              }}</span></label
            ><label
              >试卷类型
              <select v-model="form.paperType" name="paperType">
                <option value="PRACTICE">练习</option>
                <option value="FINAL_EXAM">期中期末</option>
                <option value="PAST_EXAM">真题</option></select
              ><span v-if="fieldErrors.paperType" class="paper-field-error">{{
                fieldErrors.paperType
              }}</span></label
            ><label
              >学段
              <select
                v-model="form.schoolStage"
                name="schoolStage"
                :aria-invalid="!!fieldErrors.schoolStage"
                aria-describedby="paper-stage-error"
              >
                <option value="" disabled>请选择学段</option>
                <option
                  v-if="form.schoolStage && !schoolStages.includes(form.schoolStage)"
                  :value="form.schoolStage"
                >
                  {{ form.schoolStage }}（原有值）
                </option>
                <option v-for="stage in schoolStages" :key="stage" :value="stage">
                  {{ uiStatus(stage) }}
                </option></select
              ><span
                v-if="fieldErrors.schoolStage"
                id="paper-stage-error"
                class="paper-field-error"
                >{{ fieldErrors.schoolStage }}</span
              ></label
            ><label
              >年级
              <select
                v-model="form.grade"
                name="grade"
                :aria-invalid="!!fieldErrors.grade"
                aria-describedby="paper-grade-error"
              >
                <option value="" disabled>请选择年级</option>
                <option
                  v-if="form.grade && !grades.some(([value]) => value === form.grade)"
                  :value="form.grade"
                >
                  {{ form.grade }}（原有值）
                </option>
                <option v-for="[value, label] in grades" :key="value" :value="value">
                  {{ label }}
                </option></select
              ><span v-if="fieldErrors.grade" id="paper-grade-error" class="paper-field-error">{{
                fieldErrors.grade
              }}</span></label
            ><label
              >学科
              <select
                v-model="form.subject"
                name="subject"
                :aria-invalid="!!fieldErrors.subject"
                aria-describedby="paper-subject-error"
              >
                <option value="" disabled>请选择学科</option>
                <option
                  v-if="form.subject && !subjects.includes(form.subject)"
                  :value="form.subject"
                >
                  {{ form.subject }}（原有值）
                </option>
                <option v-for="subject in subjects" :key="subject" :value="subject">
                  {{ uiStatus(subject) }}
                </option></select
              ><span
                v-if="fieldErrors.subject"
                id="paper-subject-error"
                class="paper-field-error"
                >{{ fieldErrors.subject }}</span
              ></label
            >
            <details class="learning-field-wide">
              <summary>附加信息（年份，可选）</summary>
              <label
                >年份（可空）<input
                  v-model="form.year"
                  name="year"
                  inputmode="numeric"
                  :aria-invalid="!!fieldErrors.year"
                  aria-describedby="paper-year-error"
                />
                <span v-if="fieldErrors.year" id="paper-year-error" class="paper-field-error">{{
                  fieldErrors.year
                }}</span></label
              >
            </details>
            <label class="learning-field-wide"
              >内容介绍
              <textarea v-model="form.summary" name="summary" rows="4" maxlength="1000" />
              <span v-if="fieldErrors.summary" class="paper-field-error">{{
                fieldErrors.summary
              }}</span>
            </label>
            <fieldset class="paper-cover">
              <legend>试卷封面（可选）</legend>
              <p>封面用于试卷列表和详情展示；与下方公开样张、会员下载附件分别设置。</p>
              <AssetPicker
                :key="epoch"
                :model-value="form.coverAssetId ?? null"
                :services="services"
                @update:model-value="form.coverAssetId = $event"
                @busy="coverUploading = $event"
              />
              <el-button
                v-if="form.coverAssetId"
                :disabled="busy || uploading"
                @click="form.coverAssetId = null"
                >移除封面</el-button
              >
              <span v-if="fieldErrors.coverAssetId" class="paper-field-error">{{
                fieldErrors.coverAssetId
              }}</span>
            </fieldset>
            <fieldset>
              <legend>私有原始附件</legend>
              <p v-if="fieldErrors.attachmentIds" class="paper-field-error">
                {{ fieldErrors.attachmentIds }}
              </p>
              <p>
                支持
                DOC、DOCX、PDF、JPG、JPEG、PNG、WEBP。替换时先上传新文件，再移除旧绑定；不会删除原文件或历史订单。
              </p>
              <ul>
                <li v-for="asset in attachments" :key="asset.id">
                  {{ asset.name }}（{{ asset.id }}） {{ uiStatus(asset.status) }} /
                  {{ uiStatus(asset.scanStatus) }}
                  <el-button :disabled="busy || uploading" @click="remove(asset.id)"
                    >移除绑定</el-button
                  >
                </li>
              </ul>
              <AssetUpload
                :key="epoch"
                :services="services"
                purpose="PAPER_ATTACHMENT"
                :disabled="busy"
                @ready="bind"
                @busy="attachmentUploading = $event"
              />
              <el-button :disabled="busy || uploading" @click="loadAssets()">浏览素材</el-button>
              <ul>
                <li v-for="asset in assets" :key="asset.id">
                  {{ asset.name }} · {{ uiStatus(asset.status) }} / {{ uiStatus(asset.scanStatus) }}
                  <el-button
                    :disabled="
                      busy || uploading || !isReadyLearningAsset(asset, 'PAPER_ATTACHMENT')
                    "
                    @click="bind(asset)"
                    >绑定附件</el-button
                  >
                </li>
              </ul>
              <el-button
                :disabled="busy || uploading || assetPage <= 1"
                @click="loadAssets(assetPage - 1)"
                >上一页附件</el-button
              ><el-button
                :disabled="busy || uploading || assetPage * 10 >= assetTotal"
                @click="loadAssets(assetPage + 1)"
                >下一页附件</el-button
              >
            </fieldset>
            <fieldset>
              <legend>公开样张内容</legend>
              <p v-if="fieldErrors.contentBlocks" class="paper-field-error">
                {{ fieldErrors.contentBlocks }}
              </p>
              <p>这里只放公开文字和图片，原始附件保留在私有附件区。</p>
              <ContentBlockEditor
                :key="epoch"
                v-model="form.contentBlocks"
                :services="services"
                @busy="contentUploading = $event"
              />
            </fieldset>
          </fieldset>
          <aside class="content-editor-sidebar">
            <section class="admin-card">
              <h3>发布设置</h3>
              <label
                >发布状态<select v-model="form.status" name="status" :disabled="busy || uploading">
                  <option value="DRAFT">草稿</option>
                  <option value="PUBLISHED">已发布</option>
                  <option value="UNPUBLISHED">已撤下</option>
                </select></label
              >
              <p class="publish-help">
                {{
                  dirty ? '有未保存修改' : record ? '已保存' : '新草稿（尚未保存）'
                }}。保存时应用所选状态，原始附件继续按会员权限授权。
              </p>
              <div class="publish-actions">
                <el-button
                  type="primary"
                  native-type="button"
                  @click="save"
                  :disabled="busy || uploading || conflict || refreshRequired"
                  >保存试卷</el-button
                ><el-button :disabled="busy || uploading" @click="load">{{
                  currentId ? '重新读取试卷（丢弃本地修改）' : '重新读取'
                }}</el-button>
              </div>
            </section>
            <ContentDraftPreview
              :title="form.title"
              :subtitle="form.summary"
              :asset-id="form.coverAssetId"
              :blocks="form.contentBlocks"
            />
            <section class="admin-card">
              <h3>附件概览</h3>
              <p>已绑定 {{ attachments.length }} 份原始附件</p>
              <p class="publish-help">此处不公开预览私有文件。附件的就绪状态请在编辑区核对。</p>
            </section>
          </aside>
        </form>
      </template>
    </template>
  </main>
</template>
<style scoped>
.learning-editor .learning-fields,
.learning-editor .content-editor-sidebar > .admin-card {
  border-radius: 9px;
}
.learning-editor fieldset > legend {
  padding: 0 6px;
  color: #24334b;
  font-weight: 700;
}
.learning-editor fieldset > p {
  color: #596981;
  line-height: 1.6;
}
.learning-editor li {
  padding: 8px 0;
  overflow-wrap: anywhere;
  border-bottom: 1px solid #edf1f6;
}
.learning-editor button:focus-visible,
.learning-editor input:focus-visible,
.learning-editor select:focus-visible,
.learning-editor textarea:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
.paper-field-error {
  display: block;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
