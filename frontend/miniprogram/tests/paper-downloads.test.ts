// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { ApiError } from '@jxsc/shared';
import Downloads from '../src/pages/paper/downloads.vue';
import { session } from '../src/api/session';

const hooks = vi.hoisted(() => ({
  show: [] as Array<() => void>,
  pull: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onShow: (fn: () => void) => hooks.show.push(fn),
  onPullDownRefresh: (fn: () => void) => hooks.pull.push(fn),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api/v1' },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const store = new Map<string, string>();
  return {
    session: createSession({
      get: (k) => store.get(k) ?? null,
      set: (k, v) => {
        store.set(k, v);
      },
      remove: (k) => {
        store.delete(k);
      },
    }),
  };
});
vi.mock('../src/api/protected-files', async (original) => {
  const actual = await original<typeof import('../src/api/protected-files')>();
  return {
    ...actual,
    createProtectedFilePlatform: (
      options: Parameters<typeof actual.createProtectedFilePlatform>[0],
    ) => actual.createH5ProtectedFilePlatform(undefined, options),
  };
});

const record = (overrides: Record<string, unknown> = {}) => ({
  paperId: 'paper/real',
  paperTitle: '2026 中考语文真题',
  paperStatus: 'PUBLISHED',
  fileId: 'file/real',
  fileName: '真题.pdf',
  extension: 'pdf',
  mime: 'application/pdf',
  bytes: 1024,
  action: 'DOWNLOAD',
  lastAt: '2026-10-19T02:00:00Z',
  times: 3,
  canDownload: true,
  unavailableReason: null,
  ...overrides,
});

const wrappers: VueWrapper[] = [];
function login() {
  session.set({
    accessToken: 'a',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'a', nickname: 'a', distributor: false },
  });
}
beforeEach(() => {
  hooks.show = [];
  hooks.pull = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', { navigateTo: vi.fn(), stopPullDownRefresh: vi.fn() });
  session.clear();
  login();
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function open() {
  const w = mount(Downloads);
  wrappers.push(w);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return w;
}
it('lists the caller history once per file and explains unavailable rows', async () => {
  hooks.request.mockResolvedValue([
    record(),
    record({
      paperId: 'paper/gone',
      paperTitle: '已下架资料',
      fileId: 'file/gone',
      fileName: '旧卷.pdf',
      canDownload: false,
      unavailableReason: 'PAPER_UNAVAILABLE',
      times: 1,
    }),
  ]);
  const w = await open();
  expect(hooks.request).toHaveBeenCalledWith('/downloads', { query: { limit: 20 } });
  expect(w.text()).toContain('2026 中考语文真题');
  expect(w.text()).toContain('真题.pdf');
  expect(w.text()).toContain('共 3 次');
  expect(w.text()).toContain('该试卷已下架');
  // Only the downloadable row offers a button.
  expect(w.findAll('button').filter((b) => b.text().includes('重新下载'))).toHaveLength(1);
});
it('re-downloads through the same authorization path as the paper page', async () => {
  hooks.request
    .mockResolvedValueOnce([record()])
    .mockRejectedValueOnce(new ApiError('MEMBERSHIP_EXPIRED', '会员已过期'));
  const w = await open();
  const button = w.findAll('button').find((b) => b.text().includes('重新下载'))!;
  expect(button).toBeDefined();
  await button.trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenLastCalledWith(
    '/papers/paper%2Freal/files/file%2Freal/authorize',
    {
      method: 'POST',
      body: { action: 'DOWNLOAD' },
    },
  );
  expect(w.text()).toContain('会员已过期，请续费后重试');
});
