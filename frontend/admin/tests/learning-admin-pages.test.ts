// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { Component } from 'vue';
import AssetPicker from '../src/components/AssetPicker.vue';
import AssetUpload from '../src/components/AssetUpload.vue';
import ContentBlockEditor from '../src/components/ContentBlockEditor.vue';
import { session } from '../src/api/session';
import { ApiError } from '../../shared/src/http';
import { LearningMutationCommittedError } from '../../shared/src/learning/admin-api';
import Courses from '../src/views/learning/Courses.vue';
import CourseEditor from '../src/views/learning/CourseEditor.vue';
import Papers from '../src/views/learning/Papers.vue';
import PaperEditor from '../src/views/learning/PaperEditor.vue';
import MembershipPlans from '../src/views/learning/MembershipPlans.vue';
import MembershipRecords from '../src/views/learning/MembershipRecords.vue';

const api = vi.hoisted(() =>
  Object.fromEntries(
    [
      'courses',
      'course',
      'saveCourse',
      'chapters',
      'saveChapter',
      'reorderChapters',
      'papers',
      'paper',
      'savePaper',
      'membershipRecords',
      'membershipPlans',
      'saveMembershipPlan',
    ].map((name) => [name, vi.fn()]),
  ),
);
const content = vi.hoisted(() =>
  Object.fromEntries(
    ['products', 'product', 'assets', 'setProductPublished', 'presign', 'transfer', 'complete'].map(
      (name) => [name, vi.fn()],
    ),
  ),
);
vi.mock('../src/api/learning', () => ({ learningApi: api }));
vi.mock('../src/api/content', () => ({ contentApi: content, uploadServices: content }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api' },
}));
const pages: Record<string, Component> = {
  Courses,
  CourseEditor,
  Papers,
  PaperEditor,
  MembershipPlans,
  MembershipRecords,
};
const wrappers: VueWrapper[] = [];
const video = {
  id: 'video1',
  version: 2,
  name: 'lesson.mp4',
  extension: 'MP4',
  mime: 'video/mp4',
  bytes: 4,
  purpose: 'COURSE_VIDEO',
  status: 'READY',
  scanStatus: 'CLEAN',
  publicUrl: null,
  durationSeconds: 73,
};
const attachment = {
  ...video,
  id: 'file1',
  name: 'paper.pdf',
  extension: 'PDF',
  mime: 'application/pdf',
  purpose: 'PAPER_ATTACHMENT',
};
const chapter = {
  id: 'chapter1',
  courseId: 'course1',
  version: 3,
  title: '服务器章节',
  videoAssetId: 'video1',
  isTrial: false,
  durationSeconds: 73,
  video,
  sortNo: 3,
  enabled: false,
};
const course = {
  id: 'course1',
  productId: 'product1',
  version: 4,
  title: '服务器课程',
  validityType: 'DAYS',
  validityDays: 365,
  contentBlocks: [{ type: 'TEXT', text: '课程说明' }],
  status: 'DRAFT',
  chapters: [chapter],
};
const paper = {
  id: 'paper1',
  version: 5,
  title: '服务器试卷',
  schoolStage: '小学',
  grade: '六年级',
  subject: '数学',
  year: '2026',
  summary: '公开摘要',
  contentBlocks: [{ type: 'TEXT', text: '公开样张说明' }],
  attachmentIds: ['file1'],
  attachments: [attachment],
  visibility: 'PUBLIC',
  status: 'DRAFT',
};
const plan = {
  id: 'plan1',
  productId: 'membership1',
  version: 6,
  durationMonths: 6,
  priceFen: 19900,
  enabled: true,
  description: '',
  level: 'STANDARD',
  status: 'ENABLED',
};
const record = {
  id: 'record1',
  userId: 'user-server',
  level: 'STANDARD',
  status: 'ACTIVE',
  version: 3,
  startsAt: '2026-01-01T00:00:00Z',
  expiresAt: '2027-01-01T00:00:00Z',
};
it('sends current membership filters to the server and resets them with an authoritative reread', async () => {
  api.membershipRecords!.mockResolvedValue(paged([{ ...record, status: 'PENDING' }]));
  const { w } = await page('MembershipRecords');
  expect(w.text()).toContain('未生效');
  await w.get('[name="userId"]').setValue('42');
  await w.get('[name="status"]').setValue('PENDING');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(api.membershipRecords).toHaveBeenLastCalledWith({
    userId: '42',
    status: 'PENDING',
    page: 1,
    pageSize: 20,
  });
  await button(w, '重置').trigger('click');
  await flushPromises();
  expect(api.membershipRecords).toHaveBeenLastCalledWith({ page: 1, pageSize: 20 });
});
function paged(items: unknown[], page = 1, total = items.length) {
  return { items: structuredClone(items), page, pageSize: 20, total };
}
function login(permissions = ['CONTENT_EDIT', 'ORDER_OPERATE']) {
  session.set({
    accessToken: 'admin-token',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    user: { id: 'admin', nickname: 'Admin', distributor: false },
  });
}
beforeEach(() => {
  vi.resetAllMocks();
  session.clear();
  login();
  api.courses!.mockResolvedValue(paged([course]));
  api.course!.mockResolvedValue(structuredClone(course));
  api.chapters!.mockResolvedValue(structuredClone(course.chapters));
  api.papers!.mockResolvedValue(paged([paper]));
  api.paper!.mockResolvedValue(structuredClone(paper));
  api.membershipPlans!.mockResolvedValue(paged([plan]));
  api.membershipRecords!.mockResolvedValue(paged([record]));
  content.products!.mockResolvedValue(
    paged([{ id: 'product1', type: 'COURSE', title: '绑定商品', version: 9, status: 'DRAFT' }]),
  );
  content.product!.mockResolvedValue({
    id: 'product1',
    type: 'COURSE',
    version: 9,
    status: 'DRAFT',
  });
  content.assets!.mockImplementation(async (query) =>
    paged(
      query.purpose === 'COURSE_VIDEO'
        ? [video]
        : query.purpose === 'PAPER_ATTACHMENT'
          ? [attachment]
          : [],
    ),
  );
  content.presign!.mockResolvedValue({
    id: 'upload1',
    uploadUrl: 'https://example.invalid/upload',
    uploadToken: 'ticket',
    expiresAt: '2099-01-01T00:00:00Z',
  });
  content.transfer!.mockResolvedValue(undefined);
  content.complete!.mockResolvedValue({ ...video, id: 'upload1', name: 'upload.mp4' });
  api.saveCourse!.mockImplementation(async (_id, body) => ({
    ...course,
    ...body,
    id: 'course1',
    version: 5,
  }));
  api.savePaper!.mockImplementation(async (_id, body) => ({
    ...paper,
    ...body,
    id: 'paper1',
    version: 6,
  }));
  api.saveChapter!.mockResolvedValue(chapter);
  api.reorderChapters!.mockResolvedValue(course);
  api.saveMembershipPlan!.mockImplementation(async (_id, body) => ({
    ...body,
    id: 'plan1',
    version: 7,
  }));
});
afterEach(() => wrappers.splice(0).forEach((w) => w.unmount()));
async function page(name: string, path?: string) {
  const component = pages[name]!;
  const editor = name.endsWith('Editor');
  const prefix = name === 'CourseEditor' ? '/learning/courses' : '/learning/papers';
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: editor ? `${prefix}/:id` : '/page', component },
      { path: '/:pathMatch(.*)*', component: { template: '<p>其他页面</p>' } },
    ],
  });
  await router.push(
    path ?? (editor ? `${prefix}/${name === 'CourseEditor' ? 'course1' : 'paper1'}` : '/page'),
  );
  await router.isReady();
  const w = mount({ template: '<router-view />' }, { global: { plugins: [router] } });
  wrappers.push(w);
  await flushPromises();
  return { w, router };
}
function button(w: VueWrapper, label: string) {
  const b = w.findAll('button').find((b) => b.text() === label);
  expect(b, label).toBeDefined();
  return b!;
}
async function click(w: VueWrapper, label: string) {
  await button(w, label).trigger('click');
  await flushPromises();
}
async function upload(
  w: VueWrapper,
  file = new File(['data'], 'upload.mp4', { type: 'video/mp4' }),
) {
  const input = w.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();
}

it('preserves a created paper route and blocks another POST after committed hydration failure', async () => {
  const { w, router } = await page('PaperEditor', '/learning/papers/new');
  for (const [name, value] of Object.entries({
    title: '已创建试卷',
    schoolStage: 'PRIMARY',
    grade: '1',
    subject: 'MATH',
  }))
    await w.get(`[name="${name}"]`).setValue(value);
  content.complete!.mockResolvedValue({ ...attachment, id: 'upload1', name: 'new.pdf' });
  await upload(w, new File(['data'], 'new.pdf', { type: 'application/pdf' }));
  api.savePaper!.mockRejectedValueOnce(
    new LearningMutationCommittedError({ kind: 'PAPER', id: 'paper-created', version: 1 }, [
      'PAPER',
    ]),
  );
  api.paper!.mockRejectedValue(new ApiError('NETWORK_ERROR', '附件读取失败'));
  await click(w, '保存试卷');
  expect(router.currentRoute.value.path).toBe('/learning/papers/paper-created');
  expect(w.text()).toContain('已提交');
  const retry = w.findAll('button').find((button) => button.text() === '保存试卷');
  if (retry) {
    await retry.trigger('click');
    await flushPromises();
  }
  expect(api.savePaper).toHaveBeenCalledTimes(1);
  api.paper!.mockResolvedValue({ ...paper, id: 'paper-created', version: 1 });
  await click(w, '重新读取试卷（丢弃本地修改）');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper-created',
    expect.objectContaining({ version: 1 }),
  );
});
it('preserves a created course route after a later product-stage failure', async () => {
  const { w, router } = await page('CourseEditor', '/learning/courses/new');
  await w.get('[name="productId"]').setValue('product1');
  await w.get('[name="title"]').setValue('已创建课程');
  api.saveCourse!.mockRejectedValueOnce(
    new LearningMutationCommittedError(
      { kind: 'COURSE', id: 'course-created', version: 1 },
      ['COURSE'],
      '课程信息已提交，商品内容保存结果尚未确认',
    ),
  );
  api.course!.mockRejectedValue(new ApiError('NETWORK_ERROR', '课程读取失败'));
  await click(w, '保存课程');
  expect(router.currentRoute.value.path).toBe('/learning/courses/course-created');
  expect(w.text()).toContain('已提交');
  expect(api.saveCourse).toHaveBeenCalledTimes(1);
});
it('blocks duplicate chapter creation when hydration fails inside the mutation adapter', async () => {
  const { w } = await page('CourseEditor');
  await w.get('[name="chapterTitle"]').setValue('已创建章节');
  await upload(w);
  api.saveChapter!.mockRejectedValueOnce(
    new LearningMutationCommittedError({ kind: 'CHAPTER', id: 'chapter-created', version: 1 }, [
      'CHAPTER',
    ]),
  );
  await click(w, '保存章节');
  expect(w.text()).toContain('已提交');
  expect(button(w, '保存章节').attributes('disabled')).toBeDefined();
  await click(w, '保存章节');
  expect(api.saveChapter).toHaveBeenCalledTimes(1);
  await click(w, '重新读取课程（丢弃本地修改）');
  expect(button(w, '保存章节').attributes('disabled')).toBeUndefined();
});
it('requires a fresh version after a committed reorder hydration failure', async () => {
  api.course!.mockResolvedValue({ ...course, chapters: [chapter, { ...chapter, id: 'chapter2' }] });
  const { w } = await page('CourseEditor');
  await click(w, '下移章节');
  api.reorderChapters!.mockRejectedValueOnce(
    new LearningMutationCommittedError({ kind: 'COURSE', id: 'course1', version: 5 }, ['COURSE']),
  );
  await click(w, '保存章节排序');
  expect(button(w, '保存章节排序').attributes('disabled')).toBeDefined();
  await click(w, '保存章节排序');
  expect(api.reorderChapters).toHaveBeenCalledTimes(1);
});

it.each(['Courses', 'CourseEditor', 'Papers', 'PaperEditor', 'MembershipPlans'])(
  '%s denies every read and mutation without CONTENT_EDITOR',
  async (name) => {
    login(['ORDER_OPERATOR']);
    const { w } = await page(name);
    expect(w.text()).toContain('没有内容管理权限');
    expect(w.find('form').exists()).toBe(false);
    for (const fn of [...Object.values(api), ...Object.values(content)])
      expect(fn).not.toHaveBeenCalled();
  },
);
it.each([
  ['Courses', '服务器课程'],
  ['Papers', '服务器试卷'],
  ['MembershipRecords', '2027-01-01 08:00'],
  ['MembershipPlans', '199.00'],
])('%s renders authoritative rows', async (name, title) => {
  const { w } = await page(name);
  expect(w.text()).toContain(title);
});
it('keeps course rows on failed refresh, filters and pages through the API', async () => {
  api.courses!.mockResolvedValue(paged([course], 1, 25));
  const { w } = await page('Courses');
  expect(w.find('[name="keyword"]').exists()).toBe(false);
  api.courses!.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '读取失败'));
  await click(w, '刷新列表');
  expect(w.text()).toContain('服务器课程');
  expect(w.text()).toContain('读取失败');
  await click(w, '重试');
  await click(w, '下一页');
  expect(api.courses).toHaveBeenLastCalledWith({
    page: 2,
    pageSize: 20,
  });
});
it('hides unsupported paper filters, exposes contracted membership filters and uses ORDER_OPERATE', async () => {
  const { w } = await page('Papers');
  expect(w.find('[name="schoolStage"]').exists()).toBe(false);
  expect(api.papers).toHaveBeenLastCalledWith({ page: 1, pageSize: 20 });
  login(['ORDER_OPERATE']);
  const { w: records } = await page('MembershipRecords');
  expect(records.text()).toContain('user-server');
  expect(records.text()).toContain('2027-01-01 08:00');
  expect(records.find('[name="userId"]').exists()).toBe(true);
  expect(records.find('[name="orderId"]').exists()).toBe(false);
  expect(api.membershipRecords).toHaveBeenLastCalledWith({ page: 1, pageSize: 20 });
});
it('denies membership records to content-only operators', async () => {
  login(['CONTENT_EDIT']);
  const { w } = await page('MembershipRecords');
  expect(w.text()).toContain('没有订单操作权限');
  expect(api.membershipRecords).not.toHaveBeenCalled();
});
it('rejects zero validity, sends null for permanent validity, and retains conflict input', async () => {
  const { w } = await page('CourseEditor');
  await w.get('[name="validityDays"]').setValue('0');
  await click(w, '保存课程');
  expect(api.saveCourse).not.toHaveBeenCalled();
  expect(w.text()).toContain('正整数');
  await w.get('[name="validityType"]').setValue('PERMANENT');
  await w.get('[name="title"]').setValue('我的课程修改');
  api.saveCourse!.mockRejectedValueOnce(
    new ApiError('VERSION_CONFLICT', '版本已变化', null, {}, 409),
  );
  await click(w, '保存课程');
  expect(api.saveCourse).toHaveBeenLastCalledWith(
    'course1',
    expect.objectContaining({
      productId: 'product1',
      version: 4,
      validityType: 'PERMANENT',
      validityDays: null,
    }),
  );
  expect((w.get('[name="title"]').element as HTMLInputElement).value).toBe('我的课程修改');
  expect(w.text()).toContain('版本已变化');
  expect(w.text()).toContain('新订单');
  await click(w, '重新读取课程（丢弃本地修改）');
  expect((w.get('[name="title"]').element as HTMLInputElement).value).toBe('服务器课程');
});
it('binds a real uploader READY event with server metadata and rereads the course version after chapter save', async () => {
  const { w } = await page('CourseEditor');
  await w.get('[name="chapterTitle"]').setValue('上传章节');
  await upload(w);
  expect(w.text()).toContain('73 秒');
  api.course!.mockResolvedValue({ ...course, version: 7 });
  await click(w, '保存章节');
  expect(api.saveChapter).toHaveBeenLastCalledWith('course1', null, {
    version: 0,
    title: '上传章节',
    videoAssetId: 'upload1',
    isTrial: false,
    durationSeconds: 73,
    enabled: true,
    sortNo: 1,
  });
  await w.get('[name="title"]').setValue('章节后的修改');
  await click(w, '保存课程');
  expect(api.saveCourse).toHaveBeenLastCalledWith(
    'course1',
    expect.objectContaining({ version: 7 }),
  );
});
it('blocks publication for unready chapter assets and uses the freshly read product version for publish', async () => {
  api.course!.mockResolvedValue({
    ...course,
    chapters: [{ ...chapter, video: { ...video, status: 'PENDING' } }],
  });
  const { w } = await page('CourseEditor');
  await click(w, '上架课程');
  expect(content.setProductPublished).not.toHaveBeenCalled();
  expect(w.text()).toContain('已就绪');
  api.course!.mockResolvedValue(course);
  await click(w, '重新读取课程（丢弃本地修改）');
  content.product!.mockResolvedValue({
    id: 'product1',
    type: 'COURSE',
    version: 15,
    status: 'DRAFT',
  });
  content.setProductPublished!.mockResolvedValue({
    id: 'product1',
    version: 16,
    status: 'ON_SALE',
  });
  api.course!.mockResolvedValue({ ...course, status: 'ON_SALE' });
  await click(w, '上架课程');
  expect(content.setProductPublished).toHaveBeenLastCalledWith('product1', true, 15);
  expect(w.text()).toContain('在售');
});
it('binds a paper PDF upload separately from public samples and preserves a version-conflicting draft', async () => {
  const { w } = await page('PaperEditor');
  content.complete!.mockResolvedValue({ ...attachment, id: 'upload1', name: 'new.pdf' });
  await upload(w, new File(['data'], 'new.pdf', { type: 'application/pdf' }));
  await w.get('[name="title"]').setValue('我的试卷');
  api.savePaper!.mockRejectedValueOnce(
    new ApiError('VERSION_CONFLICT', '版本已变化', null, {}, 409),
  );
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({
      version: 5,
      title: '我的试卷',
      attachmentIds: ['file1', 'upload1'],
      contentBlocks: [{ type: 'TEXT', text: '公开样张说明' }],
    }),
  );
  expect((w.get('[name="title"]').element as HTMLInputElement).value).toBe('我的试卷');
  expect(w.text()).toContain('new.pdf');
  expect(w.text()).toContain('版本已变化');
});
it('offers Chinese paper classifications but saves contracted codes and edited summary', async () => {
  api.paper!.mockResolvedValue({
    ...paper,
    schoolStage: 'JUNIOR',
    grade: '9',
    subject: 'CHINESE',
  });
  const { w } = await page('PaperEditor');
  expect(w.get('select[name="schoolStage"] option:checked').text()).toBe('初中');
  expect(w.get('select[name="grade"] option:checked').text()).toBe('九年级');
  expect(w.get('select[name="subject"] option:checked').text()).toBe('语文');
  await w.get('[name="schoolStage"]').setValue('SENIOR');
  await w.get('[name="grade"]').setValue('12');
  await w.get('[name="subject"]').setValue('MATH');
  await w.get('[name="summary"]').setValue('修改后的公开介绍');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({
      schoolStage: 'SENIOR',
      grade: '12',
      subject: 'MATH',
      summary: '修改后的公开介绍',
      version: 5,
      attachmentIds: ['file1'],
    }),
  );
});
it('selects and saves a paper cover independently from its samples and private attachments', async () => {
  const { w } = await page('PaperEditor');
  const cover = w.get('.paper-cover').getComponent(AssetPicker);
  cover.vm.$emit('update:modelValue', 'public-cover');
  await flushPromises();
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({
      coverAssetId: 'public-cover',
      attachmentIds: ['file1'],
      contentBlocks: paper.contentBlocks,
    }),
  );
  await click(w, '移除封面');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({ coverAssetId: null }),
  );
});
it('blocks saving and discards a pending cover upload after changing accounts', async () => {
  let finish!: () => void;
  content.transfer!.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  content.complete!.mockResolvedValue({
    ...attachment,
    id: 'cover-upload',
    name: 'cover.png',
    purpose: 'PUBLIC_IMAGE',
    extension: 'PNG',
    mime: 'image/png',
    publicUrl: '/cover.png',
  });
  const { w } = await page('PaperEditor');
  const cover = w.get('.paper-cover').getComponent(AssetPicker);
  await cover.get('button').trigger('click');
  await flushPromises();
  const input = cover.getComponent(AssetUpload).get('input[type="file"]');
  Object.defineProperty(input.element, 'files', {
    value: [new File(['data'], 'cover.png', { type: 'image/png' })],
    configurable: true,
  });
  await input.trigger('change');
  await flushPromises();
  expect(button(w, '保存试卷').attributes('disabled')).toBeDefined();
  await click(w, '保存试卷');
  expect(api.savePaper).not.toHaveBeenCalled();
  login();
  finish();
  await flushPromises();
  expect(content.complete).not.toHaveBeenCalled();
  expect(w.find('.paper-cover').exists()).toBe(false);
});
it('identifies an invalid paper year at its field and preserves input without sending a write', async () => {
  const { w } = await page('PaperEditor');
  await w.get('[name="year"]').setValue('2201');
  await click(w, '保存试卷');
  expect(api.savePaper).not.toHaveBeenCalled();
  expect(w.get('#paper-year-error').text()).toContain('1900–2200');
  expect(w.get('[name="year"]').attributes('aria-invalid')).toBe('true');
  expect((w.get('[name="year"]').element as HTMLInputElement).value).toBe('2201');
  await w.get('[name="year"]').setValue('2025');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenCalledOnce();
  expect(w.find('#paper-year-error').exists()).toBe(false);
});
it('keeps legacy paper classifications unchanged when saving an unrelated title edit', async () => {
  const { w } = await page('PaperEditor');
  expect(w.get('select[name="schoolStage"] option:checked').text()).toContain('小学');
  expect(w.get('select[name="grade"] option:checked').text()).toContain('六年级');
  expect(w.get('select[name="subject"] option:checked').text()).toContain('数学');
  await w.get('[name="title"]').setValue('只修改标题');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({ schoolStage: '小学', grade: '六年级', subject: '数学' }),
  );
});
it('shows failed scan, does not bind it, and accepts the retry through the actual uploader', async () => {
  const { w } = await page('PaperEditor');
  content.complete!.mockResolvedValueOnce({
    ...attachment,
    id: 'bad',
    status: 'REJECTED',
    scanStatus: 'REJECTED',
  });
  await upload(w, new File(['data'], 'new.pdf', { type: 'application/pdf' }));
  expect(w.text()).toContain('上传失败');
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({ attachmentIds: ['file1'] }),
  );
  content.complete!.mockResolvedValue({ ...attachment, id: 'upload1', name: 'new.pdf' });
  await click(w, '重试上传');
  expect(w.text()).toContain('new.pdf');
});
it('shows product pricing read-only and retains a rejected plan cycle edit', async () => {
  const { w } = await page('MembershipPlans');
  await click(w, '编辑计划');
  expect(w.find('[name="price"]').exists()).toBe(false);
  expect(w.text()).toContain('199.00');
  expect(w.text()).toContain('维护商品售价与介绍');
  await w.get('[name="durationMonths"]').setValue('12');
  api.saveMembershipPlan!.mockRejectedValueOnce(
    new ApiError('VALIDATION_ERROR', '已有启用的12个月计划'),
  );
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(api.saveMembershipPlan).toHaveBeenLastCalledWith(
    'plan1',
    expect.objectContaining({ durationMonths: 12, enabled: true, priceFen: 19900 }),
  );
  expect(w.text()).toContain('已有启用');
});
it('clears rows on session replacement and suppresses a pending prior-account response', async () => {
  let finish!: (value: unknown) => void;
  api.courses!.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w } = await page('Courses');
  login(['ORDER_OPERATOR']);
  finish(paged([course]));
  await flushPromises();
  expect(w.text()).not.toContain('服务器课程');
  expect(w.text()).toContain('没有内容管理权限');
});
it('loads a changed course route and prevents the older response from restoring its draft', async () => {
  let finish!: (value: unknown) => void;
  api.course!.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w, router } = await page('CourseEditor');
  api.course!.mockResolvedValue({ ...course, id: 'second', title: '第二课程' });
  await router.push('/learning/courses/second');
  await flushPromises();
  finish(course);
  await flushPromises();
  expect((w.get('[name="title"]').element as HTMLInputElement).value).toBe('第二课程');
});

it('creates a course from an actual COURSE product and navigates to the server identifier', async () => {
  const { w, router } = await page('CourseEditor', '/learning/courses/new');
  await w.get('[name="productId"]').setValue('product1');
  await w.get('[name="title"]').setValue('新课程');
  content.product!.mockResolvedValueOnce({ id: 'product1', type: 'BOOK', version: 9 });
  await click(w, '保存课程');
  expect(api.saveCourse).not.toHaveBeenCalled();
  expect(w.text()).toContain('真实的课程商品');
  await click(w, '保存课程');
  expect(api.saveCourse).toHaveBeenLastCalledWith(
    null,
    expect.objectContaining({ productId: 'product1', title: '新课程', version: 0 }),
  );
  expect(router.currentRoute.value.path).toBe('/learning/courses/course1');
});
it('persists an edited trial chapter and explicit server chapter order with their distinct versions', async () => {
  const second = { ...chapter, id: 'chapter2', title: '第二章', version: 8 };
  api.course!.mockResolvedValue({ ...course, chapters: [chapter, second] });
  const { w } = await page('CourseEditor');
  await click(w, '编辑章节');
  await w.get('[name="isTrial"]').setValue(true);
  await click(w, '保存章节');
  expect(api.saveChapter).toHaveBeenLastCalledWith('course1', 'chapter1', {
    version: 3,
    title: '服务器章节',
    videoAssetId: 'video1',
    isTrial: true,
    durationSeconds: 73,
    enabled: false,
    sortNo: 3,
  });
  await click(w, '下移章节');
  expect(api.reorderChapters).not.toHaveBeenCalled();
  await click(w, '保存章节排序');
  expect(api.reorderChapters).toHaveBeenLastCalledWith('course1', {
    version: 4,
    chapters: ['chapter2', 'chapter1'],
  });
});
it('creates a classified paper from a READY file and uses the returned paper identifier', async () => {
  const { w, router } = await page('PaperEditor', '/learning/papers/new');
  for (const [name, value] of Object.entries({
    title: '新试卷',
    schoolStage: 'PRIMARY',
    grade: '6',
    subject: 'MATH',
    status: 'PUBLISHED',
  }))
    await w.get(`[name="${name}"]`).setValue(value);
  await click(w, '保存试卷');
  expect(api.savePaper).not.toHaveBeenCalled();
  content.complete!.mockResolvedValue({ ...attachment, id: 'upload1', name: 'new.pdf' });
  await upload(w, new File(['data'], 'new.pdf', { type: 'application/pdf' }));
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    null,
    expect.objectContaining({
      title: '新试卷',
      year: null,
      attachmentIds: ['upload1'],
      status: 'PUBLISHED',
    }),
  );
  expect(router.currentRoute.value.path).toBe('/learning/papers/paper1');
});
it('prevents writes during video upload and stops its completion when the session changes', async () => {
  let finish!: () => void;
  content.transfer!.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  const { w } = await page('CourseEditor');
  await upload(w);
  expect(button(w, '保存课程').attributes('disabled')).toBeDefined();
  await w.get('form').trigger('submit');
  expect(api.saveCourse).not.toHaveBeenCalled();
  login();
  finish();
  await flushPromises();
  expect(content.complete).not.toHaveBeenCalled();
  expect(w.find('[name="title"]').exists()).toBe(false);
});
it('discards a late paper save after leaving the editor and does not redirect back', async () => {
  let finish!: (value: unknown) => void;
  api.savePaper!.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w, router } = await page('PaperEditor');
  await click(w, '保存试卷');
  await router.push('/elsewhere');
  finish(paper);
  await flushPromises();
  expect(router.currentRoute.value.path).toBe('/elsewhere');
  expect(w.text()).not.toContain('试卷已保存');
});
it('displays READY and rejected picker metadata while disabling unready binding', async () => {
  content.assets!.mockResolvedValue(
    paged([
      attachment,
      {
        ...attachment,
        id: 'rejected',
        name: 'rejected.pdf',
        status: 'REJECTED',
        scanStatus: 'MALWARE',
      },
    ]),
  );
  const { w } = await page('PaperEditor');
  await click(w, '浏览素材');
  expect(w.text()).toContain('已拒绝 / 安全检查未通过');
  const buttons = w.findAll('button').filter((button) => button.text() === '绑定附件');
  expect(buttons[1]!.attributes('disabled')).toBeDefined();
  await click(w, '保存试卷');
  expect(api.savePaper).toHaveBeenLastCalledWith(
    'paper1',
    expect.objectContaining({ attachmentIds: ['file1'] }),
  );
});
it('keeps unsaved chapter order while saving course metadata', async () => {
  const second = { ...chapter, id: 'chapter2', title: '第二章' };
  api.course!.mockResolvedValue({ ...course, chapters: [chapter, second] });
  api.saveCourse!.mockResolvedValue({
    ...course,
    version: 5,
    title: '更新标题',
    chapters: [chapter, second],
  });
  const { w } = await page('CourseEditor');
  await click(w, '下移章节');
  await w.get('[name="title"]').setValue('更新标题');
  await click(w, '保存课程');
  expect(w.get('ol').findAll('li')[0]!.text()).toContain('第二章');
  await click(w, '保存章节排序');
  expect(api.reorderChapters).toHaveBeenLastCalledWith('course1', {
    version: 5,
    chapters: ['chapter2', 'chapter1'],
  });
});

it('does not duplicate a saved chapter when the required course refresh fails', async () => {
  const { w } = await page('CourseEditor');
  await w.get('[name="chapterTitle"]').setValue('新章节');
  await upload(w);
  api.course!.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '重新读取失败'));
  await click(w, '保存章节');
  expect(w.text()).toContain('重新读取失败');
  await click(w, '保存章节');
  expect(api.saveChapter).toHaveBeenCalledTimes(1);
  expect(button(w, '保存章节').attributes('disabled')).toBeDefined();
  await click(w, '重新读取课程（丢弃本地修改）');
  expect(button(w, '保存章节').attributes('disabled')).toBeUndefined();
});

it.each([
  ['CourseEditor', 'session', 'transfer'],
  ['CourseEditor', 'route', 'presign'],
  ['PaperEditor', 'route', 'transfer'],
  ['PaperEditor', 'session', 'presign'],
])(
  '%s stops nested public-image upload after %s changes during %s',
  async (name, change, phase) => {
    let finish!: (value?: unknown) => void;
    content[phase]!.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    content.complete!.mockResolvedValue({
      ...attachment,
      id: 'public-new',
      name: 'public.png',
      purpose: 'PUBLIC_IMAGE',
      mime: 'image/png',
      extension: 'PNG',
      publicUrl: '/public-new.png',
    });
    const { w, router } = await page(name);
    await click(w, '添加图片');
    await w
      .getComponent(ContentBlockEditor)
      .getComponent(AssetPicker)
      .findAll('button')
      .find((b) => b.text() === '选择图片')!
      .trigger('click');
    await flushPromises();
    const input = w
      .getComponent(ContentBlockEditor)
      .getComponent(AssetPicker)
      .getComponent(AssetUpload)
      .get('input[type="file"]');
    Object.defineProperty(input.element, 'files', {
      value: [new File(['data'], 'public.png', { type: 'image/png' })],
      configurable: true,
    });
    await input.trigger('change');
    await flushPromises();
    expect(content.presign).toHaveBeenCalledWith(
      expect.objectContaining({ purpose: 'PUBLIC_IMAGE', name: 'public.png' }),
    );
    if (change === 'session') login();
    else await router.push('/elsewhere');
    finish(
      phase === 'presign'
        ? {
            id: 'public-new',
            uploadUrl: 'https://example.invalid/upload',
            uploadToken: 'ticket',
            expiresAt: '2099-01-01T00:00:00Z',
          }
        : undefined,
    );
    await flushPromises();
    if (phase === 'presign') expect(content.transfer).not.toHaveBeenCalled();
    expect(content.complete).not.toHaveBeenCalled();
    expect(w.text()).not.toContain('public-new');
  },
);

it('saves chapter order before the retained edited chapter without a circular prerequisite', async () => {
  const second = { ...chapter, id: 'chapter2', title: '第二章', version: 8 };
  api.course!.mockResolvedValue({ ...course, chapters: [chapter, second] });
  const { w } = await page('CourseEditor');
  await click(w, '编辑章节');
  await w.get('[name="chapterTitle"]').setValue('保留的章节修改');
  await w.get('[name="isTrial"]').setValue(true);
  await click(w, '下移章节');
  api.course!.mockResolvedValue({
    ...course,
    version: 5,
    chapters: [second, { ...chapter, version: 4, sortNo: 1 }],
  });
  await click(w, '保存章节排序');
  expect(api.reorderChapters).toHaveBeenLastCalledWith('course1', {
    version: 4,
    chapters: ['chapter2', 'chapter1'],
  });
  expect((w.get('[name="chapterTitle"]').element as HTMLInputElement).value).toBe('保留的章节修改');
  expect((w.get('[name="isTrial"]').element as HTMLInputElement).checked).toBe(true);
  api.course!.mockResolvedValue({
    ...course,
    version: 6,
    chapters: [second, { ...chapter, version: 4, title: '保留的章节修改', isTrial: true }],
  });
  await click(w, '保存章节');
  expect(api.saveChapter).toHaveBeenLastCalledWith('course1', 'chapter1', {
    version: 4,
    title: '保留的章节修改',
    videoAssetId: 'video1',
    isTrial: true,
    durationSeconds: 73,
    enabled: false,
    sortNo: 1,
  });
  expect(w.get('ol').findAll('li')[0]!.text()).toContain('第二章');
  expect(w.get('ol').findAll('li')[1]!.text()).toContain('保留的章节修改');
  await w.get('[name="title"]').setValue('新的课程标题');
  await click(w, '保存课程');
  expect(api.saveCourse).toHaveBeenLastCalledWith(
    'course1',
    expect.objectContaining({ version: 6 }),
  );
});
