// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';
import Users from '../src/views/operations/UserList.vue';
import Articles from '../src/views/content/ArticleEditor.vue';
import { session } from '../src/api/session';

const api = vi.hoisted(() => ({
  listUsers: vi.fn(),
  updateUserStatus: vi.fn(),
  articles: vi.fn(),
  article: vi.fn(),
  saveArticle: vi.fn(),
}));
vi.mock('../src/api/operations', () => ({ adminOperationsApi: api }));
vi.mock('../src/api/content', () => ({ contentApi: api }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api' },
}));
const wrappers: VueWrapper[] = [];
const article = {
  id: 'article1',
  version: 2,
  title: '真实图文',
  authorName: '作者甲',
  categoryName: '阅读',
  updatedAt: '2026-10-01T12:00:00Z',
  status: 'DRAFT',
  coverAssetId: null,
  contentBlocks: [],
};
function login(permissions = ['USER_MANAGE', 'CONTENT_EDIT']) {
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    user: { id: 'admin', nickname: 'Admin', distributor: false },
  });
}
beforeEach(() => {
  vi.resetAllMocks();
  session.clear();
  login();
  api.listUsers.mockResolvedValue({
    items: [
      {
        id: '42',
        username: null,
        phone: null,
        nickname: '真实用户',
        status: 'CLOSED',
        version: 1,
        registeredAt: '2026-01-01T00:00:00Z',
        lastLoginAt: null,
        membership: { status: 'ACTIVE', startsAt: null, expiresAt: '2027-01-01T00:00:00Z' },
      },
    ],
    total: 1,
  });
  api.articles.mockResolvedValue({ items: [article], total: 1, page: 1 });
  api.article.mockResolvedValue(structuredClone(article));
  api.saveArticle.mockImplementation(async (_id, body) => ({ ...article, ...body, version: 3 }));
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
});
async function open(component: object) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/page', component },
      { path: '/:pathMatch(.*)*', component: { template: '<p />' } },
    ],
  });
  await router.push('/page');
  const w = mount(
    { template: '<router-view />' },
    {
      global: {
        plugins: [router, ElementPlus],
        stubs: { AssetPicker: true, ContentBlockEditor: true },
      },
    },
  );
  wrappers.push(w);
  await flushPromises();
  return w;
}
it('shows closed users and independent membership without exposing a restore action, then clears on logout', async () => {
  const w = await open(Users);
  expect(w.text()).toContain('已注销');
  expect(w.text()).toContain('会员有效');
  expect(w.findAll('button').some((b) => b.text().includes('解除封禁'))).toBe(false);
  session.clear();
  await flushPromises();
  expect(w.text()).not.toContain('真实用户');
  expect(w.text()).toContain('没有用户管理权限');
});
it('blocks user reads without USER_MANAGE at the page boundary', async () => {
  login(['CONTENT_EDIT']);
  const w = await open(Users);
  expect(api.listUsers).not.toHaveBeenCalled();
  expect(w.text()).toContain('没有用户管理权限');
});
it('edits real author and category values and retains input after a failed save', async () => {
  const w = await open(Articles);
  await w
    .findAll('button')
    .find((b) => b.text() === '编辑')!
    .trigger('click');
  await flushPromises();
  expect((w.get('[name="authorName"]').element as HTMLInputElement).value).toBe('作者甲');
  await w.get('[name="authorName"]').setValue('作者乙');
  await w.get('[name="categoryName"]').setValue('');
  api.saveArticle.mockRejectedValueOnce(new Error('版本冲突，请刷新'));
  await w.get('.editor-form').trigger('submit');
  await flushPromises();
  expect(w.text()).toContain('版本冲突');
  expect((w.get('[name="authorName"]').element as HTMLInputElement).value).toBe('作者乙');
  await w.get('.editor-form').trigger('submit');
  await flushPromises();
  expect(api.saveArticle).toHaveBeenLastCalledWith(
    'article1',
    expect.objectContaining({ authorName: '作者乙', categoryName: '' }),
  );
});
