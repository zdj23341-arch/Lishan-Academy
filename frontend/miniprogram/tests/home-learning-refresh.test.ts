// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import Home from '../src/pages/home/index.vue';
const hooks = vi.hoisted(() => ({
  show: null as null | (() => void),
  refresh: null as null | (() => Promise<void>),
  loadHome: vi.fn(),
  loadRecent: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onShow: (fn: () => void) => {
    hooks.show = fn;
  },
  onPullDownRefresh: (fn: () => Promise<void>) => {
    hooks.refresh = fn;
  },
}));
vi.mock('../src/api/client', () => ({ environment: { provider: 'http' } }));
vi.mock('../src/composables/useCatalog', () => ({
  catalog: { home: vi.fn() },
}));
vi.mock('../src/composables/useAccountCatalogDetail', () => ({
  useAccountCatalogDetail: () => ({
    data: ref(null),
    loading: ref(false),
    error: ref(''),
    load: hooks.loadHome,
  }),
}));
vi.mock('../src/composables/useRecentLearning', () => ({
  useRecentLearning: () => ({
    recent: ref(null),
    loading: ref(false),
    error: ref(''),
    loadRecent: hooks.loadRecent,
    continueLearning: vi.fn(),
  }),
}));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});
it('refreshes public home and recent learning before completing platform pull-down refresh', async () => {
  let finish!: () => void;
  hooks.loadHome.mockResolvedValue(undefined);
  hooks.loadRecent.mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  const stop = vi.fn();
  vi.stubGlobal('uni', { stopPullDownRefresh: stop });
  const w = mount(Home, { global: { stubs: { BaseNav: true } } });
  const refresh = hooks.refresh!();
  await flushPromises();
  expect(hooks.loadRecent).toHaveBeenCalledOnce();
  expect(stop).not.toHaveBeenCalled();
  finish();
  await refresh;
  expect(stop).toHaveBeenCalledOnce();
  w.unmount();
});

it('always opens an empty search from home after returning from a previous search', async () => {
  const navigateTo = vi.fn();
  vi.stubGlobal('uni', {
    getStorageSync: () => '%2525E8%2525AF%2525AD',
    navigateTo,
  });
  const w = mount(Home, { global: { stubs: { BaseNav: true } } });
  try {
    for (let visit = 0; visit < 2; visit++) {
      hooks.show!();
      await flushPromises();
      await w.get('.app-search-entry').trigger('click');
      expect(navigateTo).toHaveBeenLastCalledWith({ url: '/pages/search/index' });
    }
  } finally {
    w.unmount();
  }
});

it('uses the host status-bar height on each show instead of retaining a smaller desktop CSS default', async () => {
  let statusBarHeight = 54;
  vi.stubGlobal('uni', { getSystemInfoSync: () => ({ statusBarHeight }) });
  const w = mount(Home, { global: { stubs: { BaseNav: true } } });
  try {
    hooks.show!();
    await flushPromises();
    expect(w.attributes('style')).toContain('--status-bar-height: 54px');
    statusBarHeight = 20;
    hooks.show!();
    await flushPromises();
    expect(w.attributes('style')).toContain('--status-bar-height: 20px');
  } finally {
    w.unmount();
  }
});
