// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ApiError, type HomeView } from '@jxsc/shared';
import type { AdminArticle, HomeDraft } from '@jxsc/shared/content/admin-types';
import ArticleEditor from '../src/views/content/ArticleEditor.vue';
import HomeEditor from '../src/views/content/HomeEditor.vue';
import AssetPicker from '../src/components/AssetPicker.vue';
import ContentBlockEditor from '../src/components/ContentBlockEditor.vue';

const api = vi.hoisted(() => ({
  articles: vi.fn(),
  article: vi.fn(),
  saveArticle: vi.fn(),
  homeDraft: vi.fn(),
  saveHome: vi.fn(),
  publishHome: vi.fn(),
  previewHome: vi.fn(),
}));
vi.mock('../src/api/content', () => ({ contentApi: api }));
vi.mock('../src/api/content-selection', () => ({
  contentSelectionSource: {
    resolve: async (_kind: string, id: string) => ({
      id,
      title: id === 'off' ? '已下架课程' : '有效课程',
      kind: 'COURSE',
      status: id === 'off' ? '已下架' : '已上架',
      available: id !== 'off',
      reason: id === 'off' ? '商品未上架' : undefined,
      coverUrl: null,
    }),
  },
}));

const wrappers: VueWrapper[] = [];
let storedHome: HomeDraft;
let storedArticle: AdminArticle;
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));
const stale = () =>
  new ApiError('CONFLICT', 'Content changed; reload before editing', null, {}, 409);

function homeFixture(): HomeDraft {
  const target = { type: 'PRODUCT_LIST' as const, id: null, filters: {} };
  return {
    revision: 7,
    banners: Array.from({ length: 4 }, (_, n) => ({
      id: `banner${n}`,
      title: `轮播${n}`,
      assetId: `image${n}`,
      enabled: n < 3,
      sortNo: n,
      target,
    })),
    shortcuts: Array.from({ length: 8 }, (_, n) => ({
      id: `shortcut${n}`,
      label: `入口${n}`,
      assetId: `icon${n}`,
      enabled: true,
      sortNo: n,
      target,
    })),
    sections: [
      {
        id: 'hidden-section',
        title: '暂不展示',
        type: 'PRODUCT_GRID',
        enabled: false,
        sortNo: 0,
        productIds: ['product1'],
        articleId: null,
        moreTarget: null,
      },
    ],
  };
}

beforeEach(() => {
  vi.resetAllMocks();
  storedArticle = {
    id: 'article1',
    version: 2,
    title: '已保存标题',
    authorName: '作者甲',
    categoryName: '阅读',
    status: 'DRAFT',
    coverAssetId: null,
    contentBlocks: [{ type: 'TEXT', text: '原正文' }],
  };
  api.articles.mockImplementation(async () => ({
    items: [clone(storedArticle)],
    page: 1,
    pageSize: 20,
    total: 1,
  }));
  api.article.mockImplementation(async () => clone(storedArticle));
  api.saveArticle.mockImplementation(async (_id, body) => {
    storedArticle = { ...clone(body), id: 'article1', version: storedArticle.version + 1 };
    return clone(storedArticle);
  });
  storedHome = homeFixture();
  api.homeDraft.mockImplementation(async () => clone(storedHome));
  api.saveHome.mockImplementation(async (body: HomeDraft) => {
    if (body.revision !== storedHome.revision) throw stale();
    storedHome = { ...clone(body), revision: storedHome.revision + 1 };
    return clone(storedHome);
  });
  api.publishHome.mockImplementation(async (revision: number): Promise<HomeView> => {
    if (revision !== storedHome.revision) throw stale();
    storedHome.revision++;
    return {
      revision: storedHome.revision,
      banners: storedHome.banners
        .filter((b) => b.enabled)
        .map((b) => ({ id: b.id, title: b.title, imageUrl: '/image.png', target: b.target })),
      shortcuts: storedHome.shortcuts.map((s) => ({
        id: s.id,
        label: s.label,
        iconUrl: '/icon.png',
        target: s.target,
      })),
      sections: [],
    };
  });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

async function open(component: object) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }],
  });
  await router.push('/');
  const w = mount(
    { template: '<router-view />' },
    {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: { AssetPicker: true, ContentBlockEditor: true, InternalTargetEditor: true },
      },
    },
  );
  wrappers.push(w);
  await flushPromises();
  return w;
}
function button(w: VueWrapper, label: string) {
  return w.findAll('button').find((b) => b.text() === label)!;
}
async function click(w: VueWrapper, label: string) {
  await button(w, label).trigger('click');
  await flushPromises();
}
async function openArticle() {
  const w = await open(ArticleEditor);
  await click(w, '编辑');
  return w;
}
const titleInput = (w: VueWrapper) => w.get('input[placeholder="请输入图文标题（100字内）"]');
const bannerTitle = (w: VueWrapper) =>
  w.findAll('.draggable-item-card')[0]!.get('input[placeholder="如：名师春季精读课限时特惠"]');

it('keeps unsaved article fields when the user cancels collapsing the editor', async () => {
  const w = await openArticle();
  await titleInput(w).setValue('未保存标题');
  await w.get('[name="authorName"]').setValue('作者乙');
  vi.spyOn(window, 'confirm').mockReturnValue(false);
  await click(w, '收起编辑');
  expect(w.find('.editor-form').exists()).toBe(true);
  expect((titleInput(w).element as HTMLInputElement).value).toBe('未保存标题');
  expect((w.get('[name="authorName"]').element as HTMLInputElement).value).toBe('作者乙');
  await w.get('.editor-form').trigger('submit');
  await flushPromises();
  expect(storedArticle.title).toBe('未保存标题');
  expect(storedArticle.authorName).toBe('作者乙');
});

it('discards article input only after confirmation and reopens the server value', async () => {
  const w = await openArticle();
  await titleInput(w).setValue('明确放弃的标题');
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
  await click(w, '收起编辑');
  expect(confirm).toHaveBeenCalledOnce();
  expect(w.find('.editor-form').exists()).toBe(false);
  await click(w, '编辑');
  expect((titleInput(w).element as HTMLInputElement).value).toBe('已保存标题');
});

it('collapses a saved article without an unnecessary discard confirmation', async () => {
  const w = await openArticle();
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  await click(w, '收起编辑');
  expect(w.find('.editor-form').exists()).toBe(false);
  expect(confirm).not.toHaveBeenCalled();
});

it('keeps the article editor attached while a save is pending', async () => {
  const w = await openArticle();
  let finish!: (value: AdminArticle) => void;
  api.saveArticle.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await titleInput(w).setValue('正在保存的标题');
  await w.get('.editor-form').trigger('submit');
  await flushPromises();
  const collapse = button(w, '收起编辑');
  expect(collapse.attributes('disabled')).toBeDefined();
  await collapse.trigger('click');
  expect(w.find('.editor-form').exists()).toBe(true);
  finish({ ...storedArticle, title: '正在保存的标题', version: 3 });
  await flushPromises();
  expect((titleInput(w).element as HTMLInputElement).value).toBe('正在保存的标题');
  await click(w, '收起编辑');
  expect(w.find('.editor-form').exists()).toBe(false);
});

it.each([
  ['cover', AssetPicker],
  ['content', ContentBlockEditor],
] as const)('keeps the article editor attached during a %s upload', async (_label, component) => {
  const w = await openArticle();
  const child = w.findComponent(component);
  child.vm.$emit('busy', true);
  await flushPromises();
  const collapse = button(w, '收起编辑');
  expect(collapse.attributes('disabled')).toBeDefined();
  await collapse.trigger('click');
  expect(w.find('.editor-form').exists()).toBe(true);
  child.vm.$emit('busy', false);
  await flushPromises();
  await click(w, '收起编辑');
  expect(w.find('.editor-form').exists()).toBe(false);
});

it('continues editing, saving and publishing with the returned revision and all draft-only fields intact', async () => {
  const w = await open(HomeEditor);
  await w.get('input[placeholder="如：更新首页七年级暑假名师课专题"]').setValue('发布测试');
  await click(w, '正式发布上线');
  expect(w.text()).toContain('草稿版本：8');
  expect(w.text()).toContain('草稿已同步');
  await click(w, '编辑配置');
  await bannerTitle(w).setValue('发布后继续修改');
  await click(w, '保存草稿');
  expect(w.find('.el-alert--error').exists()).toBe(false);
  expect(storedHome.revision).toBe(9);
  expect(storedHome.banners[0]!.title).toBe('发布后继续修改');
  expect(storedHome.banners[3]).toEqual({
    id: 'banner3',
    title: '轮播3',
    assetId: 'image3',
    enabled: false,
    sortNo: 3,
    target: { type: 'PRODUCT_LIST', id: null, filters: {} },
  });
  expect(storedHome.sections[0]!.productIds).toEqual(['product1']);
  expect(button(w, '正式发布上线').attributes('disabled')).toBeUndefined();
  await click(w, '正式发布上线');
  expect(w.text()).toContain('草稿版本：10');
  await click(w, '保存草稿');
  expect(storedHome.revision).toBe(11);
  expect(w.find('.el-alert--error').exists()).toBe(false);
});

it('retains unsaved homepage fields after a genuine concurrent version conflict', async () => {
  const w = await open(HomeEditor);
  await w.get('input[placeholder="如：更新首页七年级暑假名师课专题"]').setValue('发布测试');
  await click(w, '正式发布上线');
  await click(w, '编辑配置');
  await bannerTitle(w).setValue('我的未保存修改');
  storedHome.revision++;
  storedHome.banners[0]!.title = '另一个管理员已保存';
  await click(w, '保存草稿');
  expect(w.text()).toContain('Content changed; reload before editing');
  expect((bannerTitle(w).element as HTMLInputElement).value).toBe('我的未保存修改');
  expect(w.text()).toContain('有未保存修改');
  expect(storedHome.banners[0]!.title).toBe('另一个管理员已保存');
});

it('retains homepage edits and the old revision when publication fails', async () => {
  const w = await open(HomeEditor);
  await w.get('input[placeholder="如：更新首页七年级暑假名师课专题"]').setValue('发布测试');
  api.publishHome.mockRejectedValueOnce(stale());
  await click(w, '正式发布上线');
  expect(w.text()).toContain('Content changed; reload before editing');
  expect(w.text()).toContain('草稿版本：7');
  await click(w, '编辑配置');
  expect((bannerTitle(w).element as HTMLInputElement).value).toBe('轮播0');
  expect(storedHome.revision).toBe(7);
});

it('saves automatic rules and pins without clearing the existing manual selection', async () => {
  storedHome.sections[0] = {
    ...storedHome.sections[0]!,
    type: 'COURSE_GRID',
    productIds: ['product1'],
    selectionMode: 'MANUAL',
    pinnedIds: [],
  };
  const w = await open(HomeEditor);
  await click(w, '编辑配置');
  const modes = w.get('.home-section-row').findAll('input[type="radio"]');
  await modes.find((input) => input.attributes('value') === 'automatic')!.setValue(true);
  await w.get('input[type="number"][max="50"]').setValue(6);
  await click(w, '保存草稿');
  expect(storedHome.sections[0]).toMatchObject({
    selectionMode: 'AUTO',
    displayLimit: 6,
    productIds: ['product1'],
    pinnedIds: [],
  });
  await modes.find((input) => input.attributes('value') === 'manual')!.setValue(true);
  await click(w, '保存草稿');
  expect(storedHome.sections[0]).toMatchObject({
    selectionMode: 'MANUAL',
    productIds: ['product1'],
    pinnedIds: [],
  });
});
it('explains each saved pin using actual preview membership and resolved availability', async () => {
  storedHome.sections = [
    {
      ...storedHome.sections[0]!,
      id: 'courses',
      title: '课程栏目',
      enabled: true,
      type: 'COURSE_GRID',
      selectionMode: 'AUTO',
      displayLimit: 1,
      pinnedIds: ['shown', 'off', 'overflow'],
    },
  ];
  api.previewHome.mockResolvedValue({
    revision: 7,
    banners: [],
    shortcuts: [],
    sections: [
      {
        id: 'courses',
        title: '课程栏目',
        type: 'COURSE_GRID',
        products: [{ id: 'shown', title: '已展示课程' }],
        papers: [],
        article: null,
        moreTarget: null,
      },
    ],
  });
  const w = await open(HomeEditor);
  await click(w, '预览已保存版本');
  expect(w.text()).toContain('商品未上架');
  expect(w.text()).toContain('未进入本次预览的前 1 条');
  expect(w.text()).toContain('已展示课程');
  expect(w.text()).toContain('已展示');
});
