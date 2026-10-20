// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import Membership from '../src/pages/membership/index.vue';
import Paper from '../src/pages/paper/detail.vue';
import Papers from '../src/pages/paper/list.vue';
import Profile from '../src/pages/profile/index.vue';
import OrderDetail from '../src/pages/order/detail.vue';
import OrderConfirm from '../src/pages/order/confirm.vue';
import type { Component } from 'vue';
import { session, sessionStorage } from '../src/api/session';
import { ApiError, type MembershipView, type PaperDetail } from '@jxsc/shared';

const hooks = vi.hoisted(() => ({
  load: [] as Array<(options: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  hide: [] as Array<() => void>,
  unload: [] as Array<() => void>,
  bottom: [] as Array<() => void>,
  request: vi.fn(),
  native: false,
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (options: Record<string, string>) => void) => hooks.load.push(fn),
  onShow: (fn: () => void) => hooks.show.push(fn),
  onHide: (fn: () => void) => hooks.hide.push(fn),
  onUnload: (fn: () => void) => hooks.unload.push(fn),
  onReachBottom: (fn: () => void) => hooks.bottom.push(fn),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api/v1' },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const sessionStorage = { get: () => null, set: vi.fn(), remove: vi.fn() };
  return {
    session: createSession(sessionStorage),
    sessionStorage,
    operationNamespace: 'membership-paper-pages',
  };
});
// The build target is the only adapter substitution; both adapter implementations stay real.
vi.mock('../src/api/protected-files', async (original) => {
  const actual = await original<typeof import('../src/api/protected-files')>();
  return {
    ...actual,
    createProtectedFilePlatform: (
      options: Parameters<typeof actual.createProtectedFilePlatform>[0],
    ) =>
      hooks.native
        ? actual.createNativeProtectedFilePlatform(undefined, options)
        : actual.createH5ProtectedFilePlatform(undefined, options),
  };
});

const membership = (state: MembershipView['state'] = 'ACTIVE'): MembershipView => ({
  level: 'STANDARD',
  state,
  startsAt: '2026-08-31T09:10:00+08:00',
  expiresAt: state === 'NONE' ? null : '2027-01-31T09:10:00+08:00',
  canDownload: state === 'ACTIVE',
  plans: [
    {
      productId: 'real/six',
      durationMonths: 6,
      priceFen: 12880,
      projectedExpiresAt: '2027-07-31T09:10:00+08:00',
    },
    {
      productId: 'real/twelve',
      durationMonths: 12,
      priceFen: 20350,
      projectedExpiresAt: '2028-01-31T09:10:00+08:00',
    },
  ],
});
const paper = (allowed = true): PaperDetail => ({
  id: 'paper/real',
  title: '接口试卷',
  grade: '9',
  stage: 'JUNIOR',
  schoolStage: 'JUNIOR',
  year: '2026',
  subject: 'CHINESE',
  paperType: 'PRACTICE',
  summary: '原始练习题',
  accessCondition: 'ACTIVE_MEMBER',
  resourceStatus: 'READY',
  contentBlocks: [],
  attachments: [
    {
      id: 'word',
      name: '练习.docx',
      extension: 'DOCX',
      mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      bytes: 2345,
    },
    { id: 'pdf', name: '练习.pdf', extension: 'PDF', mime: 'application/pdf', bytes: 4500 },
    { id: 'png', name: '练习.png', extension: 'PNG', mime: 'image/png', bytes: 1300 },
    { id: 'jpg', name: '练习.jpg', extension: 'JPG', mime: 'image/jpeg', bytes: 1200 },
    { id: 'webp', name: '练习.webp', extension: 'WEBP', mime: 'image/webp', bytes: 900 },
  ].map((file) => ({ ...file, canPreview: allowed, canDownload: allowed })),
});
let currentMember: MembershipView;
let currentPaper: PaperDetail;
let fetcher: ReturnType<typeof vi.fn>;
let popup: { location: { href: string }; close: ReturnType<typeof vi.fn> };
let revoke: ReturnType<typeof vi.fn>;
let anchors: Array<{ href: string; download: string }>;
const wrappers: VueWrapper[] = [];
function login(id = 'learner', expiresAt = '2099-01-01T00:00:00Z') {
  session.set({ accessToken: id, expiresAt, user: { id, nickname: '学习者', distributor: false } });
}
function authorization(fileId: string, url?: string) {
  const file = currentPaper.attachments.find((item) => item.id === fileId)!;
  return {
    file,
    url: url ?? `https://files.example.test/${fileId}?signature=PRIVATE`,
    expiresAt: new Date(Date.now() + 60000).toISOString(),
  };
}
beforeEach(() => {
  for (const key of ['load', 'show', 'hide', 'unload', 'bottom'] as const) hooks[key] = [];
  hooks.request.mockReset();
  hooks.native = false;
  currentMember = membership();
  currentPaper = paper();
  anchors = [];
  session.clear();
  session.takeReturnTo();
  login();
  vi.mocked(sessionStorage.set).mockClear();
  vi.stubEnv('VITE_PROVIDER', 'http');
  vi.stubGlobal('uni', {
    navigateTo: vi.fn(),
    switchTab: vi.fn(),
    showToast: vi.fn(),
    showModal: vi.fn(),
  });
  popup = { location: { href: '' }, close: vi.fn() };
  revoke = vi.fn();
  vi.stubGlobal(
    'open',
    vi.fn(() => popup),
  );
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:ephemeral');
      static revokeObjectURL = revoke;
    },
  );
  fetcher = vi.fn(async (url: string) => {
    const file =
      currentPaper.attachments.find((item) => url.includes(item.id)) ??
      currentPaper.attachments[1]!;
    return { ok: true, blob: async () => new Blob(['fixture'], { type: file.mime }) };
  });
  vi.stubGlobal('fetch', fetcher);
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
    anchors.push({ href: this.href, download: this.download });
  });
  hooks.request.mockImplementation(async (path: string) => {
    if (path === '/membership/me') return structuredClone(currentMember);
    if (path === '/membership/plans')
      return {
        items: currentMember.plans,
        page: 1,
        pageSize: 100,
        total: currentMember.plans.length,
      };
    if (path.endsWith('/authorize'))
      return authorization(decodeURIComponent(path.split('/').at(-2)!));
    if (path === '/papers')
      return { items: [structuredClone(currentPaper)], page: 1, pageSize: 6, total: 1 };
    return structuredClone(currentPaper);
  });
});
afterEach(async () => {
  wrappers.splice(0).forEach((w) => w.unmount());
  await flushPromises();
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});
async function open(component: Component = Membership, options: Record<string, string> = {}) {
  const w = mount(component, { global: { plugins: [createPinia()] } });
  wrappers.push(w);
  hooks.load.forEach((fn) => fn(options));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return w;
}
async function click(w: VueWrapper, selector: string) {
  await w.get(selector).trigger('click');
  await flushPromises();
}
async function action(w: VueWrapper, index: number, kind: 'preview' | 'download') {
  await w.findAll(`.act-btn--${kind}`)[index]!.trigger('click');
  await flushPromises();
}

it.each(['NONE', 'ACTIVE', 'EXPIRED'] as const)(
  'renders server membership %s and real plan prices without claiming saved access forever',
  async (state) => {
    currentMember = membership(state);
    const w = await open();
    expect(w.text()).toContain(
      state === 'NONE' ? '未开通会员' : state === 'ACTIVE' ? '已生效' : '会员已到期',
    );
    expect(w.text()).toContain('半年会员');
    expect(w.text()).toContain('一年会员');
    expect(w.text()).toContain('128.80');
    expect(w.text()).toContain('203.50');
    expect(w.text()).not.toContain('历史下载权限永久有效');
    expect(w.text()).toContain('已保存');
    expect(w.text()).toContain('单独付费');
  },
);
it('keeps the selected server plan amount visible in the sticky checkout bar', async () => {
  currentMember = membership('ACTIVE');
  const w = await open();
  expect(w.get('.bottom-plan-price').text()).toContain('203.50');
  expect(w.get('.renewal-note').text()).toContain('2028-01-31 09:10');
  await click(w, '.plan-card');
  expect(w.get('.bottom-plan-price').text()).toContain('128.80');
  expect(w.get('.renewal-note').text()).toContain('2027-07-31 09:10');
});
it('keeps the member summary and concise rights explanation in separate sections', async () => {
  const w = await open();
  expect(w.text()).toContain('好资料，备课更从容。');
  expect(w.get('.member-summary').text()).toContain('我的会员');
  expect(w.get('.vip-info-box').text()).toContain('标准试卷会员');
  expect(w.get('.benefits-section').findAll('.benefit-line')).toHaveLength(2);
  expect(w.get('.benefits-section').text()).toContain('会员试卷原文件预览与下载');
  expect(w.get('.benefits-section').text()).toContain('视频课程需单独付费');
});
it('sends each real plan to checkout with quantity one instead of inventing purchase success', async () => {
  const w = await open();
  await click(w, '.plan-card');
  await click(w, '.full-buy-btn');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/order/confirm?productId=real%2Fsix&quantity=1',
  });
  await w.findAll('.plan-card')[1]!.trigger('click');
  await click(w, '.full-buy-btn');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/order/confirm?productId=real%2Ftwelve&quantity=1',
  });
  expect(uni.showModal).not.toHaveBeenCalled();
  expect(uni.showToast).not.toHaveBeenCalled();
});
it('preserves the selected plan across hiding for login and returning to the membership page', async () => {
  const w = await open();
  await click(w, '.plan-card');
  hooks.hide.forEach((fn) => fn());
  await flushPromises();
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  await click(w, '.full-buy-btn');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/order/confirm?productId=real%2Fsix&quantity=1',
  });
});
it('allows guests to browse public plans and explicitly log in for their selected checkout after cancelling a prior login', async () => {
  session.clear();
  session.rememberReturnTo('/pages/paper/detail?id=prior');
  session.takeReturnTo(); // the existing cancel-login flow consumes its prior target
  hooks.request.mockImplementation(async (path: string) => {
    if (path === '/membership/plans')
      return {
        items: membership().plans.map((plan) => ({
          id: plan.productId,
          productId: plan.productId,
          durationMonths: plan.durationMonths,
          priceFen: plan.priceFen,
          status: 'ON_SALE',
        })),
        page: 1,
        pageSize: 100,
        total: 2,
      };
    throw new ApiError('UNAUTHORIZED', '请先登录', null, {}, 401);
  });
  const w = await open();
  expect(hooks.request.mock.calls.some(([path]) => path === '/membership/me')).toBe(false);
  expect(w.text()).toContain('128.80');
  expect(w.text()).toContain('203.50');
  expect(w.text()).toContain('登录后查看会员状态');
  expect(w.text()).not.toContain('未开通会员');
  expect(w.text()).not.toContain('预计有效期至 undefined');
  await click(w, '.plan-card');
  await click(w, '.full-buy-btn');
  expect(uni.navigateTo).toHaveBeenLastCalledWith(
    expect.objectContaining({ url: '/pages/login/index' }),
  );
  expect(session.takeReturnTo()).toBe('/pages/order/confirm?productId=real%2Fsix&quantity=1');
  // Cancelling once must not permanently suppress a subsequent explicit attempt.
  await click(w, '.full-buy-btn');
  expect(uni.navigateTo).toHaveBeenCalledTimes(2);
});
it('shows unknown membership rather than NONE after a failed authenticated read', async () => {
  hooks.request.mockRejectedValue(new ApiError('NETWORK_ERROR', '网络失败'));
  const w = await open();
  expect(w.text()).toContain('会员状态待确认');
  expect(w.text()).not.toContain('未开通会员');
  expect(w.get('.full-buy-btn').attributes('disabled')).toBeDefined();
});
it('recovers public plans after a guest read failure without calling the private membership endpoint', async () => {
  session.clear();
  hooks.request.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '网络失败'));
  hooks.request.mockResolvedValue({ items: membership().plans, page: 1, pageSize: 100, total: 2 });
  const w = await open();
  expect(w.get('.full-buy-btn').attributes('disabled')).toBeDefined();
  const retry = w.findAll('button').find((b) => b.text() === '重新加载')!;
  await retry.trigger('click');
  await flushPromises();
  expect(w.findAll('.plan-card')).toHaveLength(2);
  expect(hooks.request.mock.calls.every(([path]) => path === '/membership/plans')).toBe(true);
});
it('refreshes membership on return and clears prior account projections before late results', async () => {
  const w = await open();
  let resolve!: (value: MembershipView) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  login('other');
  resolve(membership());
  await flushPromises();
  expect(w.text()).not.toContain('2027-07-31');
  currentMember = membership('EXPIRED');
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('会员已到期');
});
it('shows safe membership retry and disables purchase without plans', async () => {
  hooks.request.mockRejectedValueOnce(new Error('https://private.example.test/token'));
  const w = await open();
  expect(w.text()).toContain('会员信息加载失败');
  expect(w.text()).not.toContain('private.example');
  currentMember.plans = [];
  await click(w, '.retry-btn');
  expect(w.get('.full-buy-btn').attributes('disabled')).toBeDefined();
});
it('profile badge refreshes and cannot retain previous account membership', async () => {
  const w = await open(Profile);
  expect(w.get('.vip-desc').text()).toContain('2027-01-31 09:10');
  session.clear();
  await flushPromises();
  expect(w.get('.vip-desc').text()).not.toContain('2027-01-31');
  currentMember = membership('NONE');
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.get('.vip-btn').text()).toContain('开通');
});
it.each(['NONE', 'EXPIRED'] as const)(
  'locked file rows offer membership %s without transfer',
  async (state) => {
    currentMember = membership(state);
    currentPaper = paper(false);
    const w = await open(Paper, { id: 'paper/real' });
    expect(w.get('.file-row').text()).toContain(
      state === 'EXPIRED' ? '下载需续费会员' : '下载需开通会员',
    );
    for (const index of [0, 1, 2]) {
      await action(w, index, 'preview');
      await action(w, index, 'download');
    }
    expect(fetcher).not.toHaveBeenCalled();
    expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/authorize'))).toBe(false);
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/membership/index' });
  },
);
it.each([
  ['ACTIVE', 'preview'],
  ['ACTIVE', 'download'],
  ['unknown', 'preview'],
  ['unknown', 'download'],
] as const)(
  'keeps %s unavailable-file %s neutral and refreshes metadata before fresh authorization',
  async (state, kind) => {
    currentPaper.attachments[1]!.canPreview = false;
    currentPaper.attachments[1]!.canDownload = false;
    if (state === 'unknown') {
      const request = hooks.request.getMockImplementation()!;
      hooks.request.mockImplementation((path, options) =>
        path === '/membership/me'
          ? Promise.reject(new Error('membership unavailable'))
          : request(path, options),
      );
    }
    const w = await open(Paper, { id: 'paper/real' });
    const unavailableRow = w.findAll('.file-row')[1]!;
    await action(w, 1, kind);
    expect(uni.navigateTo).not.toHaveBeenCalled();
    expect(unavailableRow.text()).toContain('预览暂不可用');
    expect(unavailableRow.text()).toContain('下载暂不可用');
    expect(unavailableRow.text()).not.toMatch(/需.*会员/);

    expect(fetcher).not.toHaveBeenCalled();
    expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/authorize'))).toBe(false);
    expect(w.get('[role="alert"]').text()).toContain('暂不可用');
    expect(w.get('[role="alert"]').text()).not.toMatch(/开通会员|续费会员/);

    // A ready sibling remains usable under the same membership projection.
    await action(w, 2, 'download');
    expect(anchors).toHaveLength(1);
    await action(w, 1, kind);
    const reads = hooks.request.mock.calls.filter(
      ([path]) => path === '/papers/paper%2Freal',
    ).length;
    const memberReads = hooks.request.mock.calls.filter(
      ([path]) => path === '/membership/me',
    ).length;
    currentPaper.attachments[1]!.canPreview = true;
    currentPaper.attachments[1]!.canDownload = true;
    await click(w, '[data-action="refresh-files"]');
    expect(
      hooks.request.mock.calls.filter(([path]) => path === '/papers/paper%2Freal'),
    ).toHaveLength(reads + 1);
    expect(hooks.request.mock.calls.filter(([path]) => path === '/membership/me')).toHaveLength(
      memberReads + 1,
    );
    expect(w.find('[role="alert"]').exists()).toBe(false);
    expect(w.findAll('.file-row')[1]!.text()).toContain('可预览 · 可下载');
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(uni.navigateTo).not.toHaveBeenCalled();

    await action(w, 1, kind);
    expect(hooks.request.mock.calls.filter(([path]) => path.endsWith('/pdf/authorize'))).toEqual([
      [
        '/papers/paper%2Freal/files/pdf/authorize',
        { method: 'POST', body: { action: kind === 'preview' ? 'PREVIEW' : 'DOWNLOAD' } },
      ],
    ]);
    expect(fetcher).toHaveBeenCalledTimes(2);
    if (kind === 'preview') expect(popup.location.href).toBe('blob:ephemeral');
    else expect(anchors).toHaveLength(2);
  },
);
it('preserves FILE_NOT_READY from fresh authorization and offers metadata refresh without renewal', async () => {
  const w = await open(Paper, { id: 'paper/real' });
  hooks.request.mockRejectedValueOnce(new ApiError('FILE_NOT_READY', 'private-url', 'request'));
  await action(w, 1, 'download');
  expect(w.get('[role="alert"]').text()).toContain('文件尚未准备完成');
  expect(w.get('[role="alert"]').text()).not.toMatch(/开通会员|续费会员/);
  expect(fetcher).not.toHaveBeenCalled();
  await click(w, '[data-action="refresh-files"]');
  expect(uni.navigateTo).not.toHaveBeenCalled();
  expect(fetcher).not.toHaveBeenCalled();
  await action(w, 1, 'download');
  expect(hooks.request.mock.calls.filter(([path]) => path.endsWith('/pdf/authorize'))).toHaveLength(
    2,
  );
  expect(anchors).toHaveLength(1);
});
it.each([0, 1, 2, 3, 4])(
  'downloads format row %i with a fresh action grant and a blob destination',
  async (index) => {
    const w = await open(Paper, { id: 'paper/real' });
    expect(w.text()).toContain('2.3 KB');
    expect(w.text()).toContain('可预览');
    expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/authorize'))).toBe(false);
    await action(w, index, 'download');
    await action(w, index, 'download');
    expect(hooks.request.mock.calls.filter(([path]) => path.endsWith('/authorize'))).toEqual([
      [
        `/papers/paper%2Freal/files/${currentPaper.attachments[index]!.id}/authorize`,
        { method: 'POST', body: { action: 'DOWNLOAD' } },
      ],
      [
        `/papers/paper%2Freal/files/${currentPaper.attachments[index]!.id}/authorize`,
        { method: 'POST', body: { action: 'DOWNLOAD' } },
      ],
    ]);
    expect(anchors).toHaveLength(2);
    expect(anchors[0]!.href).toBe('blob:ephemeral');
    expect(anchors[0]!.download).toBe(currentPaper.attachments[index]!.name);
    expect(w.html()).not.toContain('PRIVATE');
    expect(sessionStorage.set).not.toHaveBeenCalled();
  },
);
it('H5 Word preview gives a download alternative that performs actual download handoff', async () => {
  const w = await open(Paper, { id: 'paper/real' });
  await action(w, 0, 'preview');
  expect(w.text()).toContain('下载');
  expect(w.text()).toContain('当前环境无法预览');
  expect(fetcher).not.toHaveBeenCalled();
  await action(w, 0, 'download');
  expect(anchors).toHaveLength(1);
});
it.each([1, 2])(
  'previews supported row %i and clears temporary URL/window on hide',
  async (index) => {
    const w = await open(Paper, { id: 'paper/real' });
    await action(w, index, 'preview');
    expect(popup.location.href).toBe('blob:ephemeral');
    expect(fetcher).toHaveBeenCalledTimes(1);
    hooks.hide.forEach((fn) => fn());
    await flushPromises();
    expect(revoke).toHaveBeenCalledWith('blob:ephemeral');
    expect(popup.close).toHaveBeenCalled();
    currentMember = membership('EXPIRED');
    currentPaper = paper(false);
    hooks.show.forEach((fn) => fn());
    await flushPromises();
    expect(w.text()).toContain('续费');
    await action(w, index, 'download');
    expect(fetcher).toHaveBeenCalledTimes(1);
  },
);
it('updates busy/error from async controller callbacks, prevents duplicate opens, and retries a fresh grant', async () => {
  const w = await open(Paper, { id: 'paper/real' });
  let reject!: (reason: Error) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((_, no) => {
        reject = no;
      }),
  );
  await action(w, 1, 'preview');
  expect(w.get('.act-btn--preview').attributes('disabled')).toBeDefined();
  await action(w, 1, 'preview');
  reject(new Error('https://secret.example.test/token'));
  await flushPromises();
  expect(w.text()).toContain('授权失败');
  expect(w.text()).not.toContain('secret.example');
  expect(w.get('.act-btn--preview').attributes('disabled')).toBeUndefined();
  await action(w, 1, 'preview');
  expect(popup.location.href).toBe('blob:ephemeral');
});
it.each(['hide', 'unload', 'session', 'route'] as const)(
  'ignores late grants after %s',
  async (event) => {
    const w = await open(Paper, { id: 'paper/real' });
    let resolve!: (value: unknown) => void;
    hooks.request.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await action(w, 1, 'preview');
    expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/authorize'))).toBe(true);
    if (event === 'session') login('replacement');
    else if (event === 'route') hooks.load.forEach((fn) => fn({ id: 'paper/new' }));
    else hooks[event].forEach((fn) => fn());
    resolve(authorization('pdf'));
    await flushPromises();
    expect(fetcher).not.toHaveBeenCalled();
    expect(w.html()).not.toContain('PRIVATE');
  },
);
it('reauthorizes after expiry and cleans the browser handle from timer callbacks', async () => {
  vi.useFakeTimers();
  const w = await open(Paper, { id: 'paper/real' });
  await action(w, 1, 'preview');
  await vi.advanceTimersByTimeAsync(60001);
  await flushPromises();
  expect(revoke).toHaveBeenCalledWith('blob:ephemeral');
  await action(w, 1, 'preview');
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it('shows current server permission denial despite previously enabled attachment flags', async () => {
  const w = await open(Paper, { id: 'paper/real' });
  hooks.request.mockRejectedValueOnce(new ApiError('MEMBERSHIP_EXPIRED', 'private-url', 'request'));
  await action(w, 1, 'download');
  expect(w.text()).toContain('会员已过期');
  expect(fetcher).not.toHaveBeenCalled();
});
it('keeps album permission settings explicit and preview usable after native permission denial', async () => {
  hooks.native = true;
  Object.assign(uni, {
    downloadFile: vi.fn((options) => {
      options.success({ statusCode: 200, tempFilePath: '/tmp/owned.png' });
      return { abort: vi.fn() };
    }),
    saveImageToPhotosAlbum: vi.fn((options) => options.fail({ errMsg: 'auth denied private-url' })),
    previewImage: vi.fn((options) => options.success()),
    openSetting: vi.fn((options) => options.success()),
  });
  const w = await open(Paper, { id: 'paper/real' });
  await action(w, 2, 'download');
  expect(w.text()).toContain('相册权限');
  expect(uni.openSetting).not.toHaveBeenCalled();
  await click(w, '[data-action="album-settings"]');
  expect(uni.openSetting).toHaveBeenCalledTimes(1);
  await action(w, 2, 'preview');
  expect(uni.previewImage).toHaveBeenCalled();
});
it('uses all four paper filter dimensions, resets page and retries next page without discarding results', async () => {
  hooks.request.mockImplementation(async (_path, options) => ({
    items: [{ ...paper(), id: `page-${options.query.page}`, title: `第${options.query.page}页` }],
    page: options.query.page,
    pageSize: 1,
    total: 3,
  }));
  const w = await open(Papers, {
    grade: '9',
    schoolStage: 'JUNIOR',
    year: '2026',
    subject: 'CHINESE',
  });
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({
      grade: '9',
      schoolStage: 'JUNIOR',
      year: '2026',
      subject: 'CHINESE',
      page: 1,
    }),
  });
  hooks.request.mockRejectedValueOnce(new Error('https://private.invalid/path'));
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('第1页');
  expect(w.text()).not.toContain('private.invalid');
  await click(w, '.retry-btn');
  expect(w.text()).toContain('第1页');
  expect(w.text()).toContain('第2页');
  if (!w.find('[aria-label="学科"]').exists()) await click(w, '[aria-label="更多筛选"]');
  await w.get('[aria-label="学科"]').trigger('change', { detail: { value: 2 } });
  await flushPromises();
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({ subject: 'MATH', page: 1 }),
  });
  expect(w.text()).not.toContain('第2页');
});

it.each([
  ['mock', 'test', true],
  ['http', 'test', false],
  ['mock', 'production', false],
  ['http', 'development', false],
] as const)(
  'allows exact local fixture only for provider %s in mode %s',
  async (provider, mode, allowed) => {
    vi.stubEnv('VITE_PROVIDER', provider);
    vi.stubEnv('MODE', mode);
    const w = await open(Paper, { id: 'paper/real' });
    hooks.request.mockResolvedValueOnce(authorization('pdf', '/dev-files/paper-sample.pdf'));
    await action(w, 1, 'preview');
    expect(fetcher).toHaveBeenCalledTimes(allowed ? 1 : 0);
    if (allowed) expect(popup.location.href).toBe('blob:ephemeral');
    else expect(w.text()).toContain('文件地址');
  },
);
it('rejects unlisted local files even with the mock provider', async () => {
  vi.stubEnv('VITE_PROVIDER', 'mock');
  const w = await open(Paper, { id: 'paper/real' });
  hooks.request.mockResolvedValueOnce(authorization('pdf', '/dev-files/unlisted.pdf'));
  await action(w, 1, 'preview');
  expect(fetcher).not.toHaveBeenCalled();
});
it('requires login for an enabled file and never persists authorization material', async () => {
  session.clear();
  const w = await open(Paper, { id: 'paper/real' });
  await action(w, 1, 'download');
  expect(w.text()).toContain('登录后重试');
  expect(fetcher).not.toHaveBeenCalled();
  const button = w.findAll('button').find((item) => item.text() === '登录后重试')!;
  await button.trigger('click');
  // Encoded IDs are valid query data; encoded route escapes remain rejected.
  expect(session.takeReturnTo()).toBe('/pages/paper/detail?id=paper%2Freal');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/login/index' });
  expect(sessionStorage.set).not.toHaveBeenCalled();
});
it('clears pending paper metadata on account change, hide and missing route without fallback', async () => {
  let resolve!: (value: PaperDetail) => void;
  hooks.request.mockImplementation(async (path) =>
    path === '/membership/me'
      ? currentMember
      : new Promise((done) => {
          resolve = done;
        }),
  );
  const w = await open(Paper, { id: 'paper/real' });
  login('different');
  resolve(paper());
  await flushPromises();
  expect(w.text()).not.toContain('接口试卷');
  hooks.load.forEach((fn) => fn({}));
  await flushPromises();
  expect(w.text()).toContain('缺少试卷编号');
});
it('clears busy state reactively when the login expires during authorization', async () => {
  vi.useFakeTimers();
  login('short', new Date(Date.now() + 1000).toISOString());
  const w = await open(Paper, { id: 'paper/real' });
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await action(w, 1, 'download');
  expect(w.get('.act-btn--download').attributes('disabled')).toBeDefined();
  await vi.advanceTimersByTimeAsync(1001);
  resolve(authorization('pdf'));
  await flushPromises();
  expect(w.find('.file-row').exists()).toBe(false);
  expect(fetcher).not.toHaveBeenCalled();
});
it.each([0, 1, 2])(
  'native row %i hands preview and save to the corresponding platform API',
  async (index) => {
    hooks.native = true;
    Object.assign(uni, {
      downloadFile: vi.fn((options) => {
        options.success({ statusCode: 200, tempFilePath: '/tmp/owned-file' });
        return { abort: vi.fn() };
      }),
      openDocument: vi.fn((options) => options.success()),
      previewImage: vi.fn((options) => options.success()),
      shareFileMessage: vi.fn((options) => options.success()),
      saveImageToPhotosAlbum: vi.fn((options) => options.success()),
    });
    const w = await open(Paper, { id: 'paper/real' });
    await action(w, index, 'preview');
    await action(w, index, 'download');
    expect(index === 2 ? uni.previewImage : uni.openDocument).toHaveBeenCalled();
    expect(index === 2 ? uni.saveImageToPhotosAlbum : uni.shareFileMessage).toHaveBeenCalled();
    expect(uni.showToast).not.toHaveBeenCalled();
  },
);

const memberOrder = {
  id: 'membership-order',
  orderNo: '真实会员订单',
  orderStatus: 'COMPLETED',
  fulfillmentStatus: 'NOT_REQUIRED',
  refundStatus: 'NONE',
  goodsAmountFen: 12880,
  freightAmountFen: 0,
  payableAmountFen: 12880,
  paidAmountFen: 12880,
  refundedAmountFen: 0,
  paidAt: '2026-10-07T10:00:00Z',
  expiresAt: null,
  completedAt: '2026-10-07T10:00:00Z',
  item: {
    productId: 'real/six',
    title: '真实半年会员',
    type: 'MEMBERSHIP',
    quantity: 1,
    unitPriceFen: 12880,
    coverUrl: null,
  },
  allowedActions: ['VIEW_CONTENT'],
};
it('membership order VIEW_CONTENT opens papers without entering a course', async () => {
  hooks.request.mockResolvedValue(memberOrder);
  const w = await open(OrderDetail, { id: 'membership-order' });
  const button = w.findAll('button').find((item) => item.text() === '进入试卷资料库');
  expect(button).toBeDefined();
  await button!.trigger('click');
  await flushPromises();
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/paper/list' });
  expect(w.findAll('button').some((item) => item.text() === '进入课程学习')).toBe(false);
  expect(w.get('.goods-cover-placeholder').text()).toBe('会员');
});
it.each([
  ['MEMBERSHIP', []],
  ['BOOK', ['VIEW_CONTENT']],
])(
  'does not show paper action for %s without the right permission/type',
  async (type, allowedActions) => {
    hooks.request.mockResolvedValue({
      ...memberOrder,
      item: { ...memberOrder.item, type },
      allowedActions,
    });
    const w = await open(OrderDetail, { id: 'membership-order' });
    expect(w.findAll('button').some((item) => item.text() === '进入试卷资料库')).toBe(false);
  },
);
it('selected membership plan reaches the existing checkout draft and server quote', async () => {
  const w = await open();
  await click(w, '.plan-card');
  await click(w, '.full-buy-btn');
  const route = vi.mocked(uni.navigateTo).mock.calls[0]![0]!.url;
  const search = new URL(route, 'https://app.example.test').searchParams;
  w.unmount();
  wrappers.splice(wrappers.indexOf(w), 1);
  hooks.load = [];
  hooks.show = [];
  hooks.request.mockImplementation(async (path) =>
    path.startsWith('/products/')
      ? {
          id: 'real/six',
          type: 'MEMBERSHIP',
          title: '真实半年会员',
          priceFen: 12880,
          originalPriceFen: null,
          coverUrl: null,
          status: 'ON_SALE',
          allowedActions: ['BUY'],
          membership: { durationMonths: 6 },
        }
      : {
          quoteId: 'real-quote',
          expiresAt: '2099-01-01T00:00:00Z',
          item: memberOrder.item,
          availableStock: null,
          goodsAmountFen: 12880,
          freightAmountFen: 0,
          payableAmountFen: 12880,
          currency: 'CNY',
          warnings: [],
        },
  );
  const confirm = await open(OrderConfirm, {
    productId: search.get('productId')!,
    quantity: search.get('quantity')!,
  });
  expect(hooks.request).toHaveBeenCalledWith('/orders/preview', {
    method: 'POST',
    body: { productId: 'real/six', quantity: 1 },
  });
  expect(confirm.text()).toContain('128.80');
  expect(hooks.request.mock.calls.some(([path]) => path === '/orders')).toBe(false);
});

it('shows safe paper loading failure and retries into an empty attachment state', async () => {
  let fail = true;
  hooks.request.mockImplementation(async (path) => {
    if (path === '/membership/me') return currentMember;
    if (fail) throw new Error('https://private.example.test/token');
    return { ...paper(), attachments: [] };
  });
  const w = await open(Paper, { id: 'paper-real' });
  expect(w.text()).toContain('试卷加载失败');
  expect(w.text()).not.toContain('private.example');
  fail = false;
  await click(w, '.retry-btn');
  expect(w.text()).toContain('暂无附件');
});

it('keeps the paper membership entry in a persistent bottom action area', async () => {
  const w = await open(Paper, { id: 'paper/real' });
  const action = w.get('.paper-detail-bottom-bar');
  expect(action.text()).toContain('查看会员权益');
  expect(action.element.closest('.detail-container')).toBeNull();
  await click(w, '.paper-detail-membership-btn');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/membership/index' });
});

it('ignores late previous-filter results and renders an empty replacement list', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = await open(Papers);
  expect(w.text()).toContain('加载中');
  hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 6, total: 0 });
  await w.get('[aria-label="学段"]').trigger('change', { detail: { value: 3 } });
  await flushPromises();
  resolve({ items: [paper()], page: 1, pageSize: 6, total: 1 });
  await flushPromises();
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({ schoolStage: 'SENIOR', page: 1 }),
  });
  expect(w.text()).toContain('暂无符合条件');
  expect(w.text()).not.toContain('接口试卷');
});

it('renders the server membership start and expiry as Shanghai wall times without guessing the entitlement interval', async () => {
  currentMember.startsAt = '2026-02-28T23:47:19-03:00';
  const w = await open();
  expect(w.get('.vip-info-box').text()).toContain('生效时间：2026-03-01 10:47');
  expect(w.get('.vip-info-box').text()).toContain('2027-01-31 09:10');
});

it('renders paper classification and honest access/resource states from the backend view', async () => {
  currentPaper = {
    ...paper(),
    summary: null,
    schoolStage: 'JUNIOR',
    paperType: 'FINAL_EXAM',
    accessCondition: 'ACTIVE_MEMBER',
    resourceStatus: 'NOT_READY',
  };
  const w = await open(Paper, { id: 'paper-real' });
  expect(w.text()).toContain('初中');
  expect(w.text()).toContain('期中期末');
  expect(w.text()).not.toContain('FINAL_EXAM');
  expect(w.text()).toContain('会员可访问');
  expect(w.text()).toContain('资源准备中');
  expect(w.text()).toContain('暂无公开摘要');
});

it('does not invent a membership start time when the server returns null', async () => {
  currentMember.startsAt = null;
  const w = await open();
  expect(w.get('.vip-info-box').text()).not.toContain('生效时间');
});

it('summarizes attachment formats on the list without exposing per-file actions', async () => {
  currentPaper.attachments[0]!.canDownload = false;
  currentPaper.attachments[1]!.canPreview = false;
  const w = await open(Papers);
  const card = w.get('.paper-card');
  expect(card.text()).toContain('Word / PDF / 图片 · 5份');
  expect(card.text()).toContain('练习');
  expect(card.text()).not.toContain('PRACTICE');
  expect(card.text()).not.toContain('2.3 KB');
  expect(card.text()).not.toContain('4.4 KB');
  expect(card.text()).toContain('可预览 · 可下载');
  expect(card.findAll('.file-row')).toHaveLength(0);
  expect(card.text()).not.toMatch(/需.*会员/);
  expect(card.text()).not.toContain('VIP免费下载');
  expect(w.text()).toContain('试卷摘要免费浏览，原文件预览与下载需要有效会员');
  expect(hooks.request.mock.calls.map(([path]) => path)).toEqual(['/papers']);
  expect(fetcher).not.toHaveBeenCalled();
});

it.each([
  [undefined, '附件信息待确认'],
  [[], '暂无附件'],
] as const)('shows honest list fallback for attachments %s', async (attachments, label) => {
  hooks.request.mockResolvedValueOnce({
    items: [{ ...paper(), attachments }],
    page: 1,
    pageSize: 6,
    total: 1,
  });
  const w = await open(Papers);
  expect(w.get('.paper-card').text()).toContain(label);
  expect(w.get('.paper-card').text()).not.toContain('VIP免费下载');
  expect(w.get('.paper-card').text()).not.toContain('可下载');
});

it('initializes route filters on load and makes exactly one initial list request on show', async () => {
  const w = mount(Papers);
  wrappers.push(w);
  hooks.load.forEach((fn) =>
    fn({ grade: '8', schoolStage: 'JUNIOR', year: '2025', subject: 'MATH' }),
  );
  await flushPromises();
  expect(hooks.request).not.toHaveBeenCalled();
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('接口试卷');
  expect(hooks.request).toHaveBeenCalledTimes(1);
  expect(hooks.request).toHaveBeenCalledWith('/papers', {
    query: expect.objectContaining({
      grade: '8',
      schoolStage: 'JUNIOR',
      year: '2025',
      subject: 'MATH',
      page: 1,
    }),
  });
});

it('keeps advanced paper-type filtering collapsed until requested and applies its selection', async () => {
  const w = await open(Papers);
  expect(w.text()).toContain('全部学段');
  expect(w.text()).toContain('全部年级');
  expect(w.text()).toContain('全部年份');
  expect(w.find('[aria-label="试卷类型"]').exists()).toBe(false);

  await w.get('[aria-label="年级"]').trigger('change', { detail: { value: 1 } });
  await flushPromises();
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({ grade: '7', page: 1 }),
  });

  await click(w, '[aria-label="更多筛选"]');
  expect(w.find('[aria-label="试卷类型"]').exists()).toBe(true);
  await w.get('[aria-label="试卷类型"]').trigger('change', { detail: { value: 1 } });
  await flushPromises();

  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({ paperType: 'PRACTICE', page: 1 }),
  });
});

it('refreshes returned list permissions while preserving selected filters and the last successful rows', async () => {
  currentPaper = paper(false);
  const w = await open(Papers, {
    keyword: '模拟',
    grade: '9',
    schoolStage: 'JUNIOR',
    year: '2026',
  });
  await click(w, '[aria-label="更多筛选"]');
  await w.get('[aria-label="学科"]').trigger('change', { detail: { value: 2 } });
  await flushPromises();
  hooks.hide.forEach((fn) => fn());
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('接口试卷');
  expect(w.text()).toContain('需有效会员');
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({
      keyword: '模拟',
      grade: '9',
      schoolStage: 'JUNIOR',
      year: '2026',
      subject: 'MATH',
      page: 1,
    }),
  });
  expect(resolve).toBeTypeOf('function');
  resolve({ items: [paper(true)], page: 1, pageSize: 6, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('可预览 · 可下载');
  expect(w.text()).not.toContain('需有效会员');
});

it('retains successful rows after a failed return refresh and retries page one rather than the next page', async () => {
  const w = await open(Papers);
  hooks.hide.forEach((fn) => fn());
  hooks.request.mockRejectedValueOnce(new Error('https://private.invalid/list'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('接口试卷');
  expect(w.text()).toContain('试卷列表加载失败');
  expect(w.text()).not.toContain('private.invalid');
  currentPaper.title = '返回刷新成功';
  await click(w, '.retry-btn');
  expect(w.text()).toContain('返回刷新成功');
  expect(w.text()).not.toContain('接口试卷');
  expect(hooks.request).toHaveBeenLastCalledWith('/papers', {
    query: expect.objectContaining({ page: 1 }),
  });
});

it('clears old account list rows immediately and suppresses its late response', async () => {
  const w = await open(Papers);
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  login('other-account');
  await flushPromises();
  expect(w.text()).not.toContain('接口试卷');
  expect(resolve).toBeTypeOf('function');
  currentPaper.title = '新账号试卷';
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  resolve({ items: [{ ...paper(), title: '旧账号迟到试卷' }], page: 1, pageSize: 6, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('新账号试卷');
  expect(w.text()).not.toContain('旧账号迟到试卷');
});

it.each(['hide', 'unload'] as const)('suppresses pending list results after %s', async (event) => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = await open(Papers);
  hooks[event].forEach((fn) => fn());
  resolve({
    items: [{ ...paper(), title: '已离开页面的迟到试卷' }],
    page: 1,
    pageSize: 6,
    total: 1,
  });
  await flushPromises();
  expect(w.text()).not.toContain('已离开页面的迟到试卷');
  expect(w.text()).not.toContain('加载中');
});
