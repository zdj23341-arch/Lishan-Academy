// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import BookCover from '../src/components/ui/BookCover.vue';
import ContentBlocks from '../src/components/ContentBlocks.vue';
import Profile from '../src/pages/profile/index.vue';
import { session } from '../src/api/session';

const hooks = vi.hoisted(() => ({ show: [] as Array<() => void>, request: vi.fn() }));
vi.mock('@dcloudio/uni-app', () => ({
  onShow: (fn: () => void) => hooks.show.push(fn),
  onHide: vi.fn(),
  onUnload: vi.fn(),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  return { session: createSession({ get: () => null, set: vi.fn(), remove: vi.fn() }) };
});
const wrappers: VueWrapper[] = [];
it('opens aftersale orders from the fourth profile order shortcut', async () => {
  const w = mount(Profile, { global: { plugins: [createPinia()] } });
  wrappers.push(w);
  const aftersale = w.findAll('.order-item').find((item) => item.text() === '售后');
  expect(aftersale, 'the approved profile has an aftersale shortcut').toBeDefined();
  await aftersale!.trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/order/list?tab=AFTERSALE' });
});
it('uses the approved playback label in the native course route title', () => {
  const manifest = JSON.parse(
    readFileSync(resolve(process.cwd(), 'miniprogram/src/pages.json'), 'utf8'),
  );
  const page = manifest.pages.find(
    (route: { path: string }) => route.path === 'pages/course/learn',
  );
  expect(page?.style.navigationBarTitleText).toBe('课程播放');
});

beforeEach(() => {
  hooks.show = [];
  hooks.request.mockReset();
  session.clear();
  vi.stubGlobal('uni', { navigateTo: vi.fn(), showModal: vi.fn() });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});

it('keeps the actual title in the approved book art when a cover fails, then retries a new cover', async () => {
  const w = mount(BookCover, { props: { title: '接口返回的课程标题', imageUrl: '/bad.png' } });
  wrappers.push(w);
  await w.get('image').trigger('error');
  expect(w.text()).toContain('接口返回的课程标题');
  expect(w.text()).toContain('力山学堂 · 语文学习');
  await w.setProps({ imageUrl: '/replacement.png' });
  expect(w.get('image').attributes('src')).toBe('/replacement.png');
});

it('provides a readable failed content-image state and retries when its URL is replaced', async () => {
  const image = { type: 'IMAGE' as const, assetId: 'asset', url: '/bad.png', alt: '公开样张' };
  const w = mount(ContentBlocks, { props: { blocks: [image] } });
  wrappers.push(w);
  await w.get('image').trigger('error');
  expect(w.text()).toContain('公开样张');
  expect(w.get('button').text()).toContain('重新加载图片');
  await w.get('button').trigger('click');
  expect(w.get('image').attributes('src')).toBe('/bad.png');
  await w.get('image').trigger('error');
  await w.setProps({ blocks: [{ ...image, url: '/replacement.png' }] });
  expect(w.get('image').attributes('src')).toBe('/replacement.png');
});

it.each([
  ['', false],
  ['historical-disabled-profile', true],
])(
  'shows distribution only for a real archive (%s), retaining a disabled archive entry',
  async (id, visible) => {
    session.set({
      accessToken: 'user',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'user', nickname: '学习者', distributor: false },
    });
    hooks.request.mockImplementation(async (url, options) =>
      url === '/distribution/summary'
        ? options?.query?.optional === true
          ? id
            ? { distributorId: id, status: 'DISABLED' }
            : null
          : Promise.reject(new Error('Profile must use the optional archive lookup'))
        : { level: 'STANDARD', state: 'NONE', startsAt: null, expiresAt: null, plans: [] },
    );
    const w = mount(Profile, { global: { plugins: [createPinia()] } });
    wrappers.push(w);
    hooks.show.forEach((fn) => fn());
    await flushPromises();
    expect(w.findAll('.menu-item').some((row) => row.text().includes('分销'))).toBe(visible);
    expect(hooks.request).toHaveBeenCalledWith('/distribution/summary', {
      method: 'GET',
      query: { optional: true },
    });
    if (visible) {
      await w.get('[data-action="distribution"]').trigger('click');
      expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/distribution/index' });
    }
    session.clear();
    await flushPromises();
    expect(w.find('[data-action="distribution"]').exists()).toBe(false);
  },
);

it('ignores a late distributor archive after the user logs out', async () => {
  session.set({
    accessToken: 'user',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'user', nickname: '学习者', distributor: false },
  });
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation((url) =>
    url === '/distribution/summary'
      ? new Promise((done) => {
          resolve = done;
        })
      : Promise.resolve({
          level: 'STANDARD',
          state: 'NONE',
          startsAt: null,
          expiresAt: null,
          plans: [],
        }),
  );
  const w = mount(Profile, { global: { plugins: [createPinia()] } });
  wrappers.push(w);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(resolve).toBeTypeOf('function');
  session.clear();
  resolve({ distributorId: 'late-archive' });
  await flushPromises();
  expect(w.find('[data-action="distribution"]').exists()).toBe(false);
});
