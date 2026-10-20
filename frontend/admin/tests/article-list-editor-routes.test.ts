// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { AdminArticle } from '@jxsc/shared/content/admin-types';
import ArticleEditor from '../src/views/content/ArticleEditor.vue';
import ArticleList from '../src/views/content/ArticleList.vue';

const api = vi.hoisted(() => ({ articles: vi.fn(), article: vi.fn(), saveArticle: vi.fn() }));
vi.mock('../src/api/content', () => ({ contentApi: api }));

const wrappers: VueWrapper[] = [];
const article: AdminArticle = {
  id: 'article-1',
  version: 1,
  title: '真实列表文章',
  coverAssetId: null,
  contentBlocks: [{ type: 'TEXT', text: '正文' }],
  status: 'DRAFT',
};

beforeEach(() => {
  vi.resetAllMocks();
  api.articles.mockResolvedValue({ items: [article], page: 1, pageSize: 20, total: 1 });
  api.article.mockResolvedValue(article);
  api.saveArticle.mockImplementation(async (_id, body) => ({
    ...body,
    id: 'article-new',
    version: 1,
  }));
});

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

async function openAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/content/articles', component: ArticleList },
      { path: '/content/articles/:id', component: ArticleEditor },
    ],
  });
  await router.push(path);
  const wrapper = mount(
    { template: '<router-view />' },
    { attachTo: document.body, global: { plugins: [router] } },
  );
  wrappers.push(wrapper);
  await flushPromises();
  return { router, wrapper };
}

it('loads the collection with backend paging and links each row to its editor route', async () => {
  const { wrapper } = await openAt('/content/articles');
  expect(api.articles).toHaveBeenCalledWith({
    keyword: '',
    status: undefined,
    page: 1,
    pageSize: 20,
  });
  expect(wrapper.text()).toContain('真实列表文章');
  expect(wrapper.get('a[href="/content/articles/article-1"]').exists()).toBe(true);
  expect(wrapper.get('a[href="/content/articles/new"]').exists()).toBe(true);
});

it('updates the new-article URL after a successful create', async () => {
  const { router, wrapper } = await openAt('/content/articles/new');
  expect(wrapper.get('.dirty-badge').text()).toContain('新草稿（尚未保存）');
  await wrapper.get('input[placeholder="请输入图文标题（100字内）"]').setValue('新建文章');
  expect(wrapper.get('.dirty-badge').text()).toContain('未保存');
  await wrapper.get('.editor-form').trigger('submit');
  await flushPromises();
  expect(router.currentRoute.value.path).toBe('/content/articles/article-new');
  expect(wrapper.get('.dirty-badge').text()).toContain('已保存');
  expect(api.saveArticle).toHaveBeenCalledWith(
    null,
    expect.objectContaining({ title: '新建文章', status: 'DRAFT' }),
  );
});

it('keeps an existing editor open when leaving with unsaved edits is cancelled', async () => {
  const { router, wrapper } = await openAt('/content/articles/article-1');
  await wrapper.get('input[placeholder="请输入图文标题（100字内）"]').setValue('未保存标题');
  vi.spyOn(window, 'confirm').mockReturnValue(false);
  await router.push('/content/articles');
  await flushPromises();
  expect(router.currentRoute.value.path).toBe('/content/articles/article-1');
  expect(
    (wrapper.get('input[placeholder="请输入图文标题（100字内）"]').element as HTMLInputElement)
      .value,
  ).toBe('未保存标题');
});
