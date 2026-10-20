<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { formatFen, parseYuan } from '@jxsc/shared';
import type { ProductWrite } from '@jxsc/shared/content/admin-types';
import type { AdminLearningAsset, ChapterWrite } from '@jxsc/shared/learning/admin-types';
import {
  createWorkflowRecovery,
  type CourseWorkflow,
  type WorkflowCommand,
  type WorkflowResult,
} from '@jxsc/shared/learning/course-workflow';
import { validateChapter } from '@jxsc/shared/learning/admin-validation';
import { contentApi, uploadServices } from '../../api/content';
import { learningApi } from '../../api/learning';
import { courseWorkflowApi } from '../../api/course-workflow';
import { session, operationNamespace } from '../../api/session';
import { useLearningPage } from '../../composables/useLearningPage';
import AssetPicker from '../../components/AssetPicker.vue';
import AssetUpload from '../../components/AssetUpload.vue';
import ContentBlockEditor from '../../components/ContentBlockEditor.vue';
import ContentDraftPreview from '../../components/ContentDraftPreview.vue';
import CourseFlowSteps from '../../components/ui/CourseFlowSteps.vue';
import HomeRecommendation from '../../components/HomeRecommendation.vue';

const route = useRoute(),
  router = useRouter();
const blankProduct = (): ProductWrite => ({
  version: 0,
  type: 'COURSE',
  title: '',
  subtitle: null,
  coverAssetId: null,
  priceFen: 100,
  originalPriceFen: null,
  sortNo: 0,
  categoryIds: [],
  contentBlocks: [],
});
const product = ref(blankProduct()),
  price = ref('1.00'),
  originalPrice = ref('');
const terms = ref<CourseWorkflow['course']>({
  id: '',
  productId: '',
  version: 0,
  validityType: 'DAYS',
  validityDays: 365,
  teacherName: null,
});
const record = ref<CourseWorkflow>(),
  saved = ref(''),
  loaded = ref(false);
const currentId = computed(() => (route.params.id === 'new' ? null : String(route.params.id)));
const step = ref<'basics' | 'chapters' | 'preview' | 'publish'>('basics');
const uploads = ref(new Set<string>()),
  pending = ref(false);
const chapter = ref<ChapterWrite>({
  version: 0,
  title: '',
  videoAssetId: '',
  durationSeconds: 1,
  sortNo: 0,
  enabled: true,
  isTrial: false,
});
const chapterId = ref<string | null>(null),
  chapterAsset = ref<AdminLearningAsset>();
const videos = ref<Record<string, AdminLearningAsset>>({});
const { allowed, busy, error, message, conflict, epoch, run, guardedUploads } = useLearningPage(
  () => {
    record.value = undefined;
    product.value = blankProduct();
    terms.value = {
      id: '',
      productId: '',
      version: 0,
      validityType: 'DAYS',
      validityDays: 365,
      teacherName: null,
    };
    price.value = '1.00';
    originalPrice.value = '';
    loaded.value = false;
    saved.value = '';
    uploads.value.clear();
    videos.value = {};
    resetChapter();
  },
);
const services = computed(() => {
  void epoch.value;
  return guardedUploads(uploadServices);
});
const uploading = computed(() => uploads.value.size > 0);
const snapshot = () =>
  JSON.stringify([product.value, terms.value, price.value, originalPrice.value]);
const dirty = computed(() => loaded.value && snapshot() !== saved.value);
const chapterSaved = ref('');
const chapterDirty = computed(() => JSON.stringify(chapter.value) !== chapterSaved.value);
const blocked = computed(() => busy.value || uploading.value || pending.value || conflict.value);
function upload(key: string, value: boolean) {
  if (value) uploads.value.add(key);
  else uploads.value.delete(key);
}
function storageKey() {
  const actor = session.get()?.admin?.id;
  if (!actor) throw new Error('请重新登录管理员账号');
  return `jxsc.course-workflow:${JSON.stringify([operationNamespace, actor])}`;
}
function journal() {
  const key = storageKey();
  return {
    get: () => {
      const raw = sessionStorage.getItem(key);
      return raw ? (JSON.parse(raw) as WorkflowCommand) : null;
    },
    set: (command: WorkflowCommand) => sessionStorage.setItem(key, JSON.stringify(command)),
    clear: () => sessionStorage.removeItem(key),
  };
}
function resetChapter() {
  chapterId.value = null;
  chapterAsset.value = undefined;
  chapter.value = {
    version: 0,
    title: '',
    videoAssetId: '',
    durationSeconds: 1,
    sortNo: record.value?.chapters.length ?? 0,
    enabled: true,
    isTrial: false,
  };
  chapterSaved.value = JSON.stringify(chapter.value);
}
function accept(value: CourseWorkflow) {
  record.value = value;
  const { id: _id, status: _status, typeEditable: _editable, ...write } = value.product;
  void _id;
  void _status;
  void _editable;
  product.value = { ...write, contentBlocks: write.contentBlocks.map((block) => ({ ...block })) };
  terms.value = { ...value.course };
  price.value = formatFen(value.product.priceFen);
  originalPrice.value =
    value.product.originalPriceFen == null ? '' : formatFen(value.product.originalPriceFen);
  saved.value = snapshot();
  loaded.value = true;
}
async function readVideos(value: CourseWorkflow, current: () => boolean) {
  const assets = await Promise.all(
    value.chapters.map(async (ch) => {
      if (!ch.videoAssetId) return null;
      try {
        return await contentApi.asset(ch.videoAssetId);
      } catch {
        return null;
      }
    }),
  );
  if (current())
    videos.value = Object.fromEntries(
      assets.filter((a): a is AdminLearningAsset => a !== null).map((a) => [a.id, a]),
    );
}
async function load() {
  await run(async (current) => {
    pending.value = !!journal().get();
    if (currentId.value) {
      const value = await courseWorkflowApi.get(currentId.value);
      if (!current()) return;
      accept(value);
      resetChapter();
      await readVideos(value, current);
    } else if (current()) {
      loaded.value = true;
      saved.value = snapshot();
      resetChapter();
    }
  });
}
async function result(value: WorkflowResult, current: () => boolean, preserveChapter = false) {
  const workflow = 'workflow' in value ? value.workflow : value;
  if (!current()) return;
  accept(workflow);
  if (!preserveChapter) resetChapter();
  // Store the committed identity in the URL before optional media hydration.
  if (currentId.value !== workflow.course.id) {
    await router.replace(`/learning/courses/${encodeURIComponent(workflow.course.id)}`);
    return;
  }
  await readVideos(workflow, current);
}
async function mutate(command: WorkflowCommand, current: () => boolean) {
  const store = journal();
  try {
    await result(
      await createWorkflowRecovery(courseWorkflowApi, store).submit(command),
      current,
      command.action === 'SAVE',
    );
  } finally {
    if (current()) pending.value = !!store.get();
  }
}
async function recover() {
  await run(async (current) => {
    const store = journal();
    try {
      await result(await createWorkflowRecovery(courseWorkflowApi, store).recover(), current);
    } finally {
      if (current()) pending.value = !!store.get();
    }
  }, '原操作已恢复，请核对内容后继续');
}
async function save(exit = false) {
  if (blocked.value || !loaded.value) return;
  await run(async (current) => {
    if (!product.value.title.trim()) throw new Error('请填写课程名称');
    const priceFen = parseYuan(price.value);
    const originalPriceFen = originalPrice.value.trim() ? parseYuan(originalPrice.value) : null;
    if (
      priceFen < 1 ||
      priceFen > 100000000 ||
      (originalPriceFen !== null && originalPriceFen < priceFen)
    )
      throw new Error('请检查售价与原价');
    if (
      terms.value.validityType === 'DAYS' &&
      (!Number.isInteger(terms.value.validityDays) ||
        terms.value.validityDays! < 1 ||
        terms.value.validityDays! > 36500)
    )
      throw new Error('观看有效期须为1–36500天');
    const { version, validityType, teacherName, durationMinutes } = terms.value;
    await mutate(
      {
        action: 'SAVE',
        courseId: currentId.value,
        body: {
          operationKey: crypto.randomUUID(),
          product: {
            ...product.value,
            title: product.value.title.trim(),
            priceFen,
            originalPriceFen,
          },
          course: {
            version,
            validityType,
            validityDays: validityType === 'PERMANENT' ? null : terms.value.validityDays,
            teacherName,
            durationMinutes,
          },
        },
      },
      current,
    );
    if (exit && !pending.value) await router.push('/learning/courses');
  }, '课程草稿已保存；请继续添加章节，尚未发布');
}
function bind(asset: AdminLearningAsset) {
  chapterAsset.value = asset;
  chapter.value.videoAssetId = asset.id;
  if (asset.durationSeconds) chapter.value.durationSeconds = asset.durationSeconds;
}
function editChapter(id: string) {
  if (blocked.value) return;
  const value = record.value?.chapters.find((ch) => ch.id === id);
  if (!value) return;
  const { version, title, videoAssetId, durationSeconds, sortNo, enabled, isTrial } = value;
  chapter.value = { version, title, videoAssetId, durationSeconds, sortNo, enabled, isTrial };
  chapterId.value = id;
  chapterAsset.value = videos.value[videoAssetId];
  chapterSaved.value = JSON.stringify(chapter.value);
}
async function saveChapter() {
  if (blocked.value || !currentId.value) return;
  await run(async (current) => {
    if (dirty.value) throw new Error('请先保存课程信息');
    const errors = validateChapter(chapter.value, chapterAsset.value);
    if (!chapterAsset.value || Object.keys(errors).length)
      throw new Error(Object.values(errors).join('；') || '请上传已就绪视频');
    if (!chapterId.value)
      await mutate(
        {
          action: 'CHAPTER_CREATE',
          courseId: currentId.value!,
          body: { operationKey: crypto.randomUUID(), chapter: { ...chapter.value } },
        },
        current,
      );
    else {
      await learningApi.saveChapter(currentId.value!, chapterId.value, { ...chapter.value });
      if (!current()) return;
      const value = await courseWorkflowApi.get(currentId.value!);
      if (current()) {
        accept(value);
        resetChapter();
        await readVideos(value, current);
      }
    }
  }, '章节已保存');
}
async function publish() {
  if (blocked.value || !record.value || !currentId.value) return;
  await run(async (current) => {
    if (dirty.value || chapterDirty.value) throw new Error('请先保存课程信息和章节');
    await mutate(
      {
        action: 'PUBLISH',
        courseId: currentId.value!,
        body: {
          operationKey: crypto.randomUUID(),
          productVersion: record.value!.product.version,
          courseVersion: record.value!.course.version,
          reason: '管理员核对课程后发布',
        },
      },
      current,
    );
  }, '课程已上架；首页是否展示取决于对应栏目已发布的规则，请核对首页');
}
async function unpublish() {
  if (blocked.value || !record.value || dirty.value || chapterDirty.value) return;
  await run(async (current) => {
    await contentApi.setProductPublished(
      record.value!.product.id,
      false,
      record.value!.product.version,
    );
    const value = await courseWorkflowApi.get(currentId.value!);
    if (current()) accept(value);
  }, '课程已下架，既有购买权益保持原规则');
}
function chooseStep(value: string) {
  if (['basics', 'chapters', 'preview', 'publish'].includes(value))
    step.value = value as typeof step.value;
}
onBeforeRouteLeave(
  () =>
    !uploading.value &&
    ((!dirty.value && !chapterDirty.value) || window.confirm('还有未保存内容，确定离开吗？')),
);
watch(() => route.fullPath, load);
onMounted(load);
</script>
<template>
  <main class="ls-admin-page course-workflow-editor">
    <header class="page-header">
      <h1>{{ currentId ? '编辑课程' : '新建课程' }}</h1>
      <router-link to="/learning/courses">返回课程列表</router-link>
    </header>
    <p v-if="!allowed" role="alert">没有内容管理权限</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="conflict" role="alert">版本冲突，本地填写内容已保留。请核对后重新读取。</p>
      <p v-if="pending" role="status">
        发现待确认操作，请恢复原操作后继续。<button :disabled="busy" @click="recover">
          恢复原操作
        </button>
      </p>
      <button :disabled="busy || uploading" @click="load">重新读取课程（丢弃本地修改）</button>
      <template v-if="loaded">
        <CourseFlowSteps
          :current="step"
          :completed="record ? ['basics'] : []"
          :locked="record ? [] : ['chapters', 'publish']"
          @select="chooseStep"
        />
        <p>课程与商品信息一同保存。观看有效期修改适用于新订单，既有权益按原规则执行。</p>
        <form v-show="step === 'basics'" @submit.prevent="save()">
          <fieldset :disabled="blocked">
            <legend>课程信息</legend>
            <label
              >课程名称 <input v-model="product.title" name="title" maxlength="100" required
            /></label>
            <label
              >售价（元）<input v-model="price" name="price" inputmode="decimal" required
            /></label>
            <label>原价（可选）<input v-model="originalPrice" inputmode="decimal" /></label>
            <label>讲师 <input v-model="terms.teacherName" maxlength="200" /></label>
            <label
              >观看有效期
              <select v-model="terms.validityType">
                <option value="DAYS">固定天数</option>
                <option value="PERMANENT">永久有效</option>
              </select></label
            >
            <label v-if="terms.validityType === 'DAYS'"
              >有效天数
              <input v-model.number="terms.validityDays" type="number" min="1" max="36500"
            /></label>
            <AssetPicker
              :model-value="product.coverAssetId"
              :services="services"
              @update:model-value="product.coverAssetId = $event"
              @busy="upload('cover', $event)"
            />
            <ContentBlockEditor
              v-model="product.contentBlocks"
              :services="services"
              @busy="upload('content', $event)"
            />
          </fieldset>
          <button type="submit" :disabled="blocked">保存课程草稿</button
          ><button type="button" :disabled="blocked" @click="save(true)">保存并退出</button>
        </form>
        <section v-show="step === 'chapters'">
          <h2>章节与视频</h2>
          <p>视频时长与购买后的观看有效期分别维护；只有可使用的视频才能保存启用章节。</p>
          <ol>
            <li v-for="item in record?.chapters" :key="item.id">
              {{ item.title }} · {{ item.durationSeconds }}秒 · 顺序 {{ item.sortNo }} ·
              {{ item.enabled ? '启用' : '停用' }} ·
              {{ videos[item.videoAssetId]?.status === 'READY' ? '可使用' : '视频状态待核对' }}
              <button :disabled="blocked" @click="editChapter(item.id)">编辑章节</button>
            </li>
          </ol>
          <form @submit.prevent="saveChapter">
            <fieldset :disabled="blocked || !record">
              <legend>{{ chapterId ? '编辑章节' : '添加章节' }}</legend>
              <label>章节名称 <input v-model="chapter.title" maxlength="100" required /></label>
              <label
                >实际时长（秒）<input
                  v-model.number="chapter.durationSeconds"
                  type="number"
                  min="1"
                  max="86400"
              /></label>
              <label>排序 <input v-model.number="chapter.sortNo" type="number" min="0" /></label>
              <label><input v-model="chapter.enabled" type="checkbox" />启用章节</label
              ><label><input v-model="chapter.isTrial" type="checkbox" />允许试看</label>
              <p v-if="chapterAsset">
                {{ chapterAsset.name }} ·
                {{ chapterAsset.status === 'READY' ? '可使用' : '不可使用' }}
              </p>
              <AssetUpload
                :key="epoch"
                :services="services"
                purpose="COURSE_VIDEO"
                :disabled="blocked"
                @ready="bind"
                @busy="upload('video', $event)"
              />
            </fieldset>
            <button :disabled="blocked || !record" type="submit">保存章节</button
            ><button type="button" :disabled="blocked" @click="resetChapter">新增另一章节</button>
          </form>
        </section>
        <section v-show="step === 'preview'">
          <ContentDraftPreview
            :title="product.title"
            :asset-id="product.coverAssetId"
            :blocks="product.contentBlocks"
            :price="price"
          />
          <p>预览为当前填写内容，发布前请先保存。</p>
        </section>
        <section v-show="step === 'publish'">
          <h2>核对并发布</h2>
          <p>
            {{ record?.product.status === 'ON_SALE' ? '当前已上架' : '当前尚未上架' }} · 共
            {{ record?.chapters.length ?? 0 }} 个章节
          </p>
          <p>课程发布与首页装修发布是两项操作，手动精选栏目需要另行推荐。</p>
          <button
            v-if="record?.product.status !== 'ON_SALE'"
            :disabled="blocked || dirty || chapterDirty || !record"
            @click="publish"
          >
            发布上架</button
          ><button v-else :disabled="blocked || dirty || chapterDirty" @click="unpublish">
            下架课程
          </button>
          <HomeRecommendation
            v-if="record"
            :content-id="record.product.id"
            kind="COURSE"
            :disabled="blocked || record.product.status !== 'ON_SALE'"
          />
        </section>
      </template>
    </template>
  </main>
</template>
<style scoped>
.course-workflow-editor :deep(.ls-course-steps) {
  margin: 20px 0 12px;
}
.course-workflow-editor > p {
  line-height: 1.6;
}
.course-workflow-editor form,
.course-workflow-editor section {
  min-width: 0;
  padding: 20px;
  border: 1px solid #e1e8f1;
  border-radius: 10px;
  background: #fff;
}
.course-workflow-editor section {
  margin-top: 16px;
}
.course-workflow-editor section form {
  padding: 0;
  border: 0;
}
.course-workflow-editor h2 {
  margin: 0 0 12px;
  font-size: 18px;
}
.course-workflow-editor input:not([type='checkbox']),
.course-workflow-editor select {
  min-height: 38px;
  padding: 7px 10px;
  border: 1px solid #cbd5e3;
  border-radius: 6px;
  font: inherit;
  color: #24334b;
  background: #fff;
}
.course-workflow-editor button {
  padding: 7px 14px;
  color: #2855b7;
  background: #fff;
  border: 1px solid #cbd8eb;
  border-radius: 6px;
}
.course-workflow-editor button[type='submit'] {
  color: #fff;
  background: #2855b7;
  border-color: #2855b7;
}
.course-workflow-editor button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.course-workflow-editor button:focus-visible,
.course-workflow-editor input:focus-visible,
.course-workflow-editor select:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
.course-workflow-editor [role='alert'] {
  padding: 12px;
  border-left: 3px solid #b5473a;
  background: #fff5f1;
  color: #9f342b;
}
.course-workflow-editor [role='status'] {
  padding: 12px;
  border-left: 3px solid #2855b7;
  background: #f4f7fd;
  color: #344968;
}
@media (max-width: 736px) {
  .course-workflow-editor form,
  .course-workflow-editor section {
    padding: 14px;
  }
  .course-workflow-editor .page-header {
    flex-wrap: wrap;
  }
}
fieldset {
  min-width: 0;
  margin: 16px 0;
  padding: 16px;
  border: 1px solid var(--ls-line, #ddd);
}
label {
  display: block;
  margin: 12px 0;
  overflow-wrap: anywhere;
}
input,
select {
  max-width: 100%;
}
button {
  min-height: 36px;
  margin: 4px;
  cursor: pointer;
}
button:disabled {
  cursor: default;
}
li {
  overflow-wrap: anywhere;
  padding: 8px 0;
}
</style>
