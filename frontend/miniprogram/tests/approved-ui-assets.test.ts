// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ShortcutGrid from '../src/components/ShortcutGrid.vue';
import AppSearchBar from '../src/components/AppSearchBar.vue';
import SearchPage from '../src/pages/search/index.vue';
import ProductList from '../src/pages/product/list.vue';
import { createDevCatalogStore } from '../../shared/src/dev/catalog';

const hooks = vi.hoisted(() => ({
  load: [] as Array<(o: Record<string, string>) => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (o: Record<string, string>) => void) => hooks.load.push(fn),
  onReachBottom: vi.fn(),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
const wrappers: VueWrapper[] = [];
const course = createDevCatalogStore().products.find((p) => p.type === 'COURSE')!;
beforeEach(() => {
  hooks.load = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', { getStorageSync: () => '', setStorageSync: vi.fn(), navigateTo: vi.fn() });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});

it('uses the local membership icon even when server icon metadata changes', async () => {
  const entry = {
    id: '1',
    label: '试卷会员',
    iconUrl: '/broken.png',
    target: { type: 'MEMBERSHIP' as const, id: null, filters: {} },
  };
  const w = mount(ShortcutGrid, { props: { shortcuts: [entry] } });
  wrappers.push(w);
  expect(w.get('.ls-icon').attributes('src')).toBe('/static/design/icons/crown-blue.png');
  await w.setProps({ shortcuts: [{ ...entry, iconUrl: '/new.png' }] });
  expect(w.get('.ls-icon').attributes('src')).toBe('/static/design/icons/crown-blue.png');
  await w.get('.shortcut-item').trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/membership/index' });
});

it('uses a single accessible search entry without an input on the home variant', async () => {
  const w = mount(AppSearchBar, { props: { modelValue: '', variant: 'entry' } });
  wrappers.push(w);
  expect(w.find('input').exists()).toBe(false);
  await w.get('button').trigger('click');
  expect(w.emitted('submit')).toEqual([[]]);
});

it('shows server supplied recommended courses before any keyword search', async () => {
  hooks.request.mockResolvedValue({
    items: [{ ...course, title: '真实课程推荐' }],
    page: 1,
    pageSize: 2,
    total: 1,
  });
  const w = mount(SearchPage);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({}));
  await flushPromises();
  expect(w.text()).toContain('真实课程推荐');
  expect(w.findAll('.product-card')).toHaveLength(1);
});

it('keeps keyword search usable when recommendation loading fails', async () => {
  hooks.request.mockRejectedValueOnce(new Error('推荐暂不可用')).mockResolvedValue({
    items: [{ ...course, title: '查询结果' }],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  const w = mount(SearchPage);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({}));
  await flushPromises();
  expect(w.text()).toContain('推荐暂不可用');
  await w.get('input').trigger('input', { detail: { value: '查询' } });
  await w.get('.app-search-bar__btn').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('查询结果');
  expect(w.findAll('.product-card')).toHaveLength(1);
});

it('uses the same actionable product cards for the catalog grid', async () => {
  hooks.request.mockImplementation(async (path) =>
    path === '/categories'
      ? { items: [], page: 1, pageSize: 100, total: 0 }
      : { items: [course], page: 1, pageSize: 6, total: 1 },
  );
  const w = mount(ProductList);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({}));
  await flushPromises();
  await w.get('.product-card').trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: `/pages/product/detail?id=${course.id}` });
});
