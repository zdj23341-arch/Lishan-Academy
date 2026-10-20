// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ApiError } from '@jxsc/shared';
import Editor from '../src/views/learning/CourseWorkflowEditor.vue';
import { session } from '../src/api/session';
const api = vi.hoisted(() => ({ get: vi.fn(), execute: vi.fn(), operation: vi.fn() }));
const content = vi.hoisted(() => ({ asset: vi.fn(), setProductPublished: vi.fn() }));
vi.mock('../src/api/course-workflow', () => ({ courseWorkflowApi: api }));
vi.mock('../src/api/content', () => ({ contentApi: content, uploadServices: {} }));
vi.mock('../src/api/learning', () => ({ learningApi: {} }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'http', apiBaseUrl: 'https://api.example/api/v1' },
}));
const product = {
  id: '101',
  version: 3,
  type: 'COURSE',
  title: '阅读课程',
  subtitle: null,
  status: 'DRAFT',
  coverAssetId: null,
  priceFen: 9900,
  originalPriceFen: 12900,
  sortNo: 2,
  categoryIds: ['cat'],
  contentBlocks: [{ type: 'TEXT', text: '介绍' }],
};
const workflow = {
  stage: 'DRAFT',
  product,
  course: {
    id: '901',
    productId: '101',
    version: 4,
    validityType: 'DAYS',
    validityDays: 365,
    teacherName: '老师',
  },
  chapters: [],
};
const wrappers: VueWrapper[] = [];
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  session.set({
    accessToken: 'token',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: ['CONTENT_EDIT'],
    admin: { id: 'a1', loginName: 'teacher' },
  });
  api.get.mockImplementation(async () => structuredClone(workflow));
  api.execute.mockImplementation(async () => structuredClone(workflow));
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.restoreAllMocks();
});
async function open(path = '/learning/courses/new') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/learning/courses/:id', component: Editor },
      { path: '/learning/courses', component: { template: '<p>列表</p>' } },
    ],
  });
  await router.push(path);
  const w = mount(
    { template: '<router-view/>' },
    {
      global: {
        plugins: [router],
        stubs: {
          AssetPicker: true,
          AssetUpload: true,
          ContentBlockEditor: true,
          ContentDraftPreview: true,
          HomeRecommendation: true,
        },
      },
    },
  );
  wrappers.push(w);
  await flushPromises();
  return { w, router };
}
async function click(w: VueWrapper, label: string) {
  const button = w.findAll('button').find((b) => b.text() === label)!;
  if (button.attributes('type') === 'submit')
    await button.element
      .closest('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  else await button.trigger('click');
  await flushPromises();
}
it('creates the product and course in one recoverable action then opens the committed course', async () => {
  const { w, router } = await open();
  await w.get('[name=title]').setValue('新课程');
  await w.get('[name=price]').setValue('19.90');
  await click(w, '保存课程草稿');
  expect(api.execute).toHaveBeenCalledWith(
    expect.objectContaining({
      action: 'SAVE',
      courseId: null,
      body: expect.objectContaining({
        product: expect.objectContaining({ title: '新课程', priceFen: 1990, type: 'COURSE' }),
        course: expect.objectContaining({ version: 0 }),
      }),
    }),
  );
  expect(router.currentRoute.value.path).toBe('/learning/courses/901');
  expect(w.find('[name=productId]').exists()).toBe(false);
  expect(w.text()).not.toContain('先在商品管理创建');
});
it('keeps unknown requests locked across remount and recovers the original committed result', async () => {
  const { w } = await open();
  await w.get('[name=title]').setValue('断网课程');
  api.execute.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '断网'));
  await click(w, '保存课程草稿');
  const command = api.execute.mock.calls[0]![0];
  expect(w.text()).toContain('恢复原操作');
  w.unmount();
  wrappers.splice(wrappers.indexOf(w), 1);
  api.operation.mockResolvedValue({
    operationKey: command.body.operationKey,
    action: 'CREATE',
    status: 'COMMITTED',
    courseId: '901',
    result: structuredClone(workflow),
  });
  const next = await open();
  await click(next.w, '恢复原操作');
  expect(api.execute).toHaveBeenCalledTimes(1);
  expect(next.router.currentRoute.value.path).toBe('/learning/courses/901');
});
it('preserves both product values and local edits after a version conflict', async () => {
  const { w } = await open('/learning/courses/901');
  expect((w.get('[name=price]').element as HTMLInputElement).value).toBe('99.00');
  await w.get('[name=title]').setValue('保留修改');
  api.execute.mockRejectedValueOnce(new ApiError('VERSION_CONFLICT', '版本已变化', null, {}, 409));
  await click(w, '保存课程草稿');
  expect((w.get('[name=title]').element as HTMLInputElement).value).toBe('保留修改');
  expect(w.text()).toContain('版本冲突');
  expect(api.execute.mock.calls[0]![0].body).toMatchObject({
    product: { version: 3, categoryIds: ['cat'], sortNo: 2 },
    course: { version: 4 },
  });
});
it('does not discard an unsaved chapter when saving course basics', async () => {
  const { w } = await open('/learning/courses/901');
  const inputs = w.findAll('input');
  const chapterTitle = inputs.find((input) =>
    input.element.closest('section')?.textContent?.includes('章节与视频'),
  )!;
  await chapterTitle.setValue('尚未保存的章节');
  await click(w, '保存课程草稿');
  expect(api.execute).toHaveBeenCalledTimes(1);
  expect((chapterTitle.element as HTMLInputElement).value).toBe('尚未保存的章节');
});
it('saves and exits an existing course without an unsaved prompt', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const { w, router } = await open('/learning/courses/901');
  await w.get('[name=title]').setValue('已更新课程');
  await click(w, '保存并退出');
  expect(router.currentRoute.value.path).toBe('/learning/courses');
  expect(confirm).not.toHaveBeenCalled();
});
it('does not request course data without content permissions', async () => {
  session.set({
    accessToken: 'readonly',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: [],
    admin: { id: 'reader', loginName: 'reader' },
  });
  const { w } = await open('/learning/courses/901');
  expect(api.get).not.toHaveBeenCalled();
  expect(w.text()).toContain('没有内容管理权限');
});
it('recovers to the committed course even if another course is currently open', async () => {
  const first = await open();
  await first.w.get('[name=title]').setValue('断网课程');
  api.execute.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '断网'));
  await click(first.w, '保存课程草稿');
  const command = api.execute.mock.calls[0]![0];
  first.w.unmount();
  wrappers.splice(wrappers.indexOf(first.w), 1);
  api.operation.mockResolvedValue({
    operationKey: command.body.operationKey,
    action: 'CREATE',
    status: 'COMMITTED',
    courseId: '901',
    result: structuredClone(workflow),
  });
  const next = await open('/learning/courses/902');
  await click(next.w, '恢复原操作');
  expect(next.router.currentRoute.value.path).toBe('/learning/courses/901');
});
