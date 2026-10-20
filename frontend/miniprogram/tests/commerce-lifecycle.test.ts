// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import Home from '../src/pages/home/index.vue';
import Product from '../src/pages/product/detail.vue';
import { session } from '../src/api/session';
import type { HomeView, ProductDetail } from '@jxsc/shared';

const hooks = vi.hoisted(() => ({
  load: [] as Array<(options: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  hide: [] as Array<() => void>,
  unload: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (options: Record<string, string>) => void) => hooks.load.push(fn),
  onShow: (fn: () => void) => hooks.show.push(fn),
  onHide: (fn: () => void) => hooks.hide.push(fn),
  onUnload: (fn: () => void) => hooks.unload.push(fn),
  onPullDownRefresh: vi.fn(),
  onShareAppMessage: vi.fn(),
}));
vi.mock('../src/api/client', () => ({
  client: { request: hooks.request },
  environment: { provider: 'http' },
}));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'http', apiBaseUrl: 'https://api.example.test/api/v1' },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('@jxsc/shared');
  return { session: createSession({ get: () => null, set: () => {}, remove: () => {} }) };
});
vi.mock('../src/composables/useRecentLearning', async () => {
  const { ref } = await import('vue');
  return {
    useRecentLearning: () => ({
      recent: ref(null),
      loading: ref(false),
      error: ref(''),
      loadRecent: vi.fn(),
      continueLearning: vi.fn(),
    }),
  };
});
const wrappers: VueWrapper[] = [];
const home: HomeView = {
  revision: 1,
  banners: [],
  shortcuts: [],
  sections: [
    {
      id: 'configured-books',
      type: 'PRODUCT_GRID',
      title: '服务端已发布图书',
      moreTarget: null,
      products: [],
      article: null,
    },
  ],
};
function course(entitlement: 'NONE' | 'ACTIVE' | 'EXPIRED' = 'NONE'): ProductDetail {
  return {
    id: 'product/id',
    type: 'COURSE',
    title: '接口真实课程',
    coverUrl: null,
    priceFen: 6800,
    originalPriceFen: null,
    status: 'ON_SALE',
    subtitle: null,
    version: 1,
    contentBlocks: [],
    allowedActions: entitlement === 'ACTIVE' ? ['VIEW_CONTENT'] : ['BUY'],
    course: {
      courseId: 'course/id',
      entitlement,
      validityType: 'DAYS',
      validityDays: 90,
      chaptersSummary: 4,
    },
  };
}
function login(id: string) {
  session.set({
    accessToken: id,
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id, nickname: id, distributor: false },
  });
}
beforeEach(() => {
  hooks.load = [];
  hooks.show = [];
  hooks.hide = [];
  hooks.unload = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', {
    getStorageSync: () => '',
    setStorageSync: vi.fn(),
    removeStorageSync: vi.fn(),
    navigateTo: vi.fn(),
    showToast: vi.fn(),
  });
  Object.assign(uni, { showModal: vi.fn() });
  session.clear();
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function open(component = Product, id = 'product/id') {
  const wrapper = mount(component, { global: { stubs: { BaseNav: true } } });
  wrappers.push(wrapper);
  hooks.load.forEach((fn) => fn({ id }));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return wrapper;
}
it('refreshes product rights once per visit after purchase and expiry', async () => {
  hooks.request.mockResolvedValue(course());
  const w = await open();
  expect(hooks.request).toHaveBeenCalledTimes(1);
  expect(w.text()).not.toContain('进入学习');
  for (const state of ['ACTIVE', 'EXPIRED'] as const) {
    hooks.hide.forEach((fn) => fn());
    hooks.request.mockResolvedValue(course(state));
    hooks.show.forEach((fn) => fn());
    await flushPromises();
    expect(w.text().includes('进入学习')).toBe(state === 'ACTIVE');
  }
  expect(hooks.request).toHaveBeenCalledTimes(3);
});
it('clears previously displayed account rights immediately on logout', async () => {
  login('buyer');
  hooks.request.mockResolvedValue(course('ACTIVE'));
  const w = await open();
  expect(w.text()).toContain('进入学习');
  session.clear();
  await flushPromises();
  expect(w.text()).not.toContain('进入学习');
  expect(w.text()).not.toContain('接口真实课程');
});
it.each(['hide', 'unload', 'account'] as const)(
  'rejects a late account-sensitive response on %s',
  async (event) => {
    login('buyer');
    let resolve!: (value: ProductDetail) => void;
    hooks.request.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const w = await open();
    if (event === 'account') login('other');
    else hooks[event].forEach((fn) => fn());
    resolve(course('ACTIVE'));
    await flushPromises();
    expect(w.text()).not.toContain('进入学习');
    expect(w.text()).not.toContain('接口真实课程');
  },
);
it('preserves published home modules on refresh failure and allows a real retry', async () => {
  hooks.request.mockResolvedValue(home);
  const w = await open(Home);
  hooks.hide.forEach((fn) => fn());
  hooks.request.mockRejectedValueOnce(new Error('private endpoint'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('服务端已发布图书');
  expect(w.text()).toContain('加载失败');
  expect(w.text()).not.toContain('private endpoint');
  hooks.request.mockResolvedValue({ ...home, sections: [] });
  const retry = w.findAll('button').find((b) => b.text().includes('重新加载'))!;
  await retry.trigger('click');
  await flushPromises();
  expect(w.text()).toContain('暂无更多模块推荐');
  expect(w.text()).not.toContain('服务端已发布图书');
  expect(w.text()).not.toContain('热门视频课程');
});
it('shows an honest recent-learning empty state without inventing progress', async () => {
  hooks.request.mockResolvedValue(home);
  const w = await open(Home);
  expect(w.text()).toContain('暂无学习记录');
  expect(w.text()).not.toContain('已学');
});
it('does not fetch a product or expose purchasing when its route has no ID', async () => {
  hooks.request.mockResolvedValue(course());
  const w = await open(Product, '');
  expect(hooks.request).not.toHaveBeenCalled();
  expect(w.text()).toContain('缺少商品编号');
  expect(w.text()).not.toContain('立即购买');
});
it('ignores a referral response after the product page hides', async () => {
  hooks.request.mockResolvedValue(course());
  const w = await open();
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await w.get('.detail-share-button').trigger('click');
  hooks.hide.forEach((fn) => fn());
  resolve({ miniProgramPath: '/pages/product/detail?id=old&ref=old' });
  await flushPromises();
  expect(uni.showModal).not.toHaveBeenCalled();
});
