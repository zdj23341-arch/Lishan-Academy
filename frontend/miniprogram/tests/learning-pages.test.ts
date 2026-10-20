// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import Learn from '../src/pages/course/learn.vue';
import Learning from '../src/pages/learning/index.vue';
import ProductDetail from '../src/pages/product/detail.vue';
import { session, sessionStorage } from '../src/api/session';
import type { CourseView } from '../../shared/src/learning/types';
import { createDevCatalogStore } from '../../shared/src/dev/catalog';

const hooks = vi.hoisted(() => ({
  load: [] as Array<(options: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  hide: [] as Array<() => void>,
  unload: [] as Array<() => void>,
  bottom: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (options: Record<string, string>) => void) => hooks.load.push(fn),
  onShow: (fn: () => void) => hooks.show.push(fn),
  onHide: (fn: () => void) => hooks.hide.push(fn),
  onUnload: (fn: () => void) => hooks.unload.push(fn),
  onReachBottom: (fn: () => void) => hooks.bottom.push(fn),
  onShareAppMessage: vi.fn(),
}));
vi.mock('../src/api/client', () => ({
  client: {
    request: async (path: string, options?: unknown) => {
      const value = await (options === undefined
        ? hooks.request(path)
        : hooks.request(path, options));
      const row = (v: CourseView) => ({
        courseId: v.id,
        productId: v.productId,
        title: v.title,
        status: v.entitlement,
        expiresAt: v.expiresAt,
        completedChapters: v.chapters?.filter((ch) => ch.completed).length ?? 0,
        totalChapters: v.chapters?.length ?? 0,
      });
      if (value?.chapters && value.productId) {
        if (path === '/learning/courses')
          return { items: [row(value)], page: 1, pageSize: 100, total: 1 };
        if (path.endsWith('/chapters'))
          return { items: value.chapters, page: 1, pageSize: 100, total: value.chapters.length };
        if (path.startsWith('/products/'))
          return {
            ...value,
            id: value.productId,
            type: 'COURSE',
            course: { courseId: value.id, entitlement: value.entitlement },
          };
      }
      if (path === '/learning/courses' && value?.items)
        return { ...value, items: value.items.map((v: CourseView) => row(v)) };
      return value;
    },
  },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const sessionStorage = { get: () => null, set: vi.fn(), remove: vi.fn() };
  return { session: createSession(sessionStorage), sessionStorage };
});

const course: CourseView = {
  id: 'real/course',
  productId: 'purchase/product',
  title: '接口返回的课程',
  entitlement: 'ACTIVE',
  expiresAt: '2098-06-15T10:00:00Z',
  allowedActions: ['VIEW_CONTENT'],
  chapters: [
    {
      id: 'trial',
      title: '真实试看章节',
      durationSeconds: 60,
      isTrial: true,
      canPlay: true,
      lastPositionSeconds: 7,
      completed: false,
    },
    {
      id: 'paid',
      title: '真实付费章节',
      durationSeconds: 60,
      isTrial: false,
      canPlay: true,
      lastPositionSeconds: 0,
      completed: false,
    },
  ],
};
const grant = () => ({
  url: 'http://localhost:5173/course-sample.mp4?grant=ephemeral',
  expiresAt: new Date(Date.now() + 60000).toISOString(),
  resumeSeconds: 7,
});
const wrappers: VueWrapper[] = [];
function login(id = 'learner') {
  session.set({
    accessToken: id,
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id, nickname: '测试', distributor: false },
  });
}
beforeEach(() => {
  for (const key of ['load', 'show', 'hide', 'unload', 'bottom'] as const) hooks[key] = [];
  hooks.request.mockReset();
  session.clear();
  login();
  vi.mocked(sessionStorage.set).mockClear();
  vi.stubGlobal('uni', {
    navigateTo: vi.fn(),
    switchTab: vi.fn(),
    showToast: vi.fn(),
    showModal: vi.fn(),
    createVideoContext: vi.fn(() => ({ playbackRate: vi.fn() })),
  });
  hooks.request.mockImplementation(async (path: string) =>
    path.endsWith('/play-url')
      ? grant()
      : path.endsWith('/progress')
        ? undefined
        : structuredClone(course),
  );
});
afterEach(async () => {
  wrappers.splice(0).forEach((w) => w.unmount());
  await flushPromises();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function open(component = Learn, options: Record<string, string> = { id: 'real/course' }) {
  const wrapper = mount(component);
  wrappers.push(wrapper);
  hooks.load.forEach((fn) =>
    fn(
      component === Learn && !session.get() ? { ...options, productId: course.productId } : options,
    ),
  );
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return wrapper;
}
async function click(wrapper: VueWrapper, label: string) {
  if (/^(1|1\.25|1\.5|2)x$/.test(label)) {
    if (!wrapper.find('.playback-rate-menu').exists())
      await wrapper.get('.playback-rate-trigger').trigger('click');
    const option = wrapper.findAll('.playback-rate-button').find((item) => item.text() === label);
    expect(option, `rate ${label}`).toBeDefined();
    await option!.trigger('click');
    await flushPromises();
    return;
  }
  const button = wrapper.findAll('button').find((item) => item.text() === label);
  expect(button, `button ${label}`).toBeDefined();
  await button!.trigger('click');
  await flushPromises();
}
async function media(wrapper: VueWrapper, name: string, detail = {}) {
  await wrapper.get('video').trigger(name, { detail });
  await flushPromises();
}

it('renders authoritative course data without purchased or date demo fallback', async () => {
  const w = await open();
  expect(w.text()).toContain('接口返回的课程');
  expect(w.text()).toContain('2098-06-15 18:00');
  expect(w.text()).not.toContain('2027-10-07');
  expect(w.text()).not.toContain('365');
  expect(w.find('video').exists()).toBe(false);
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
});
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'http', apiBaseUrl: '/api/v1' },
}));
it('remembers only actual playback and restores a real chapter without requesting a play grant', async () => {
  const w = await open(Learn, { id: course.id, chapterId: 'paid' });
  expect(w.get('.chapter-item--active').text()).toContain('真实付费章节');
  expect(sessionStorage.set).not.toHaveBeenCalled();
  await click(w, '播放当前章节');
  expect(sessionStorage.set).not.toHaveBeenCalled();
  await media(w, 'timeupdate', { currentTime: 24 });
  const write = vi
    .mocked(sessionStorage.set)
    .mock.calls.find(([key]) => key.startsWith('jxsc.learning.recent'));
  expect(write).toBeDefined();
  expect(JSON.parse(write![1])).toMatchObject({
    courseId: course.id,
    productId: course.productId,
    chapterId: 'paid',
    positionSeconds: 24,
  });
  expect(write![1]).not.toMatch(/grant|url|token/);
  session.clear();
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  expect(
    vi
      .mocked(sessionStorage.set)
      .mock.calls.filter(([key]) => key.startsWith('jxsc.learning.recent')),
  ).toHaveLength(1);
});
it('shows a missing ID error without requesting a demo course', async () => {
  const w = await open(Learn, {});
  expect(w.text()).toContain('缺少课程编号');
  expect(hooks.request).not.toHaveBeenCalled();
});
it('shows loading, failure and retry before empty course chapters', async () => {
  let reject!: (error: Error) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((_, no) => {
        reject = no;
      }),
  );
  const w = await open();
  expect(w.text()).toContain('正在加载');
  reject(new Error('https://secret.invalid/token'));
  await flushPromises();
  expect(w.text()).toContain('课程加载失败');
  expect(w.text()).not.toContain('secret.invalid');
  hooks.request.mockResolvedValue({ ...course, chapters: [] });
  await click(w, '重试加载');
  expect(w.text()).toContain('暂无章节');
});
it('authorizes only explicit play, supplies resume, and renews media before expiry', async () => {
  vi.useFakeTimers();
  const w = await open();
  await click(w, '播放当前章节');
  expect(w.get('video').attributes('src')).toContain('course-sample.mp4');
  expect(w.get('video').attributes('initial-time')).toBe('7');
  expect(sessionStorage.set).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(60001);
  await flushPromises();
  const playUrls = hooks.request.mock.calls.filter(([path]) => path.endsWith('/play-url'));
  expect(playUrls.length).toBeGreaterThanOrEqual(2);
  expect(w.find('video').exists()).toBe(true);
});

it('recreates the player for a fresh authorization even when the service returns the same URL', async () => {
  const sameGrant = grant();
  hooks.request.mockImplementation(async (path) =>
    path.endsWith('/play-url') ? { ...sameGrant } : structuredClone(course),
  );
  const w = await open();
  await click(w, '播放当前章节');
  const previousVideo = w.get('video').element;
  await click(w, '播放当前章节');
  expect(w.get('video').element).not.toBe(previousVideo);
});

it('offers playback speeds only inside an authorized video through a collapsible menu', async () => {
  const w = await open();
  expect(w.find('.playback-rate-trigger').exists()).toBe(false);
  expect(w.find('.playback-rate-controls').exists()).toBe(false);
  expect(uni.createVideoContext).not.toHaveBeenCalled();
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
  await click(w, '播放当前章节');
  expect(w.get('video .playback-rate-trigger').text()).toBe('倍速 · 1x');
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  expect(w.find('.validity-tag').exists()).toBe(true);
  await w.get('.playback-rate-trigger').trigger('click');
  expect(w.get('video .playback-rate-menu').exists()).toBe(true);
  expect(w.find('.validity-tag').exists()).toBe(false);
  expect(w.findAll('.playback-rate-button').map((button) => button.text())).toEqual([
    '1x',
    '1.25x',
    '1.5x',
    '2x',
  ]);
  expect(w.get('.playback-rate-button--active').text()).toBe('1x');
  await w.get('.playback-rate-dismiss').trigger('click');
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  expect(w.find('.validity-tag').exists()).toBe(true);
});

it('sets the actual current player speed for every offered rate without reauthorizing', async () => {
  const playbackRate = vi.fn();
  vi.mocked(uni.createVideoContext).mockReturnValue({
    playbackRate,
  } as unknown as UniNamespace.VideoContext);
  const w = await open();
  await click(w, '播放当前章节');
  for (const rate of [1.25, 1.5, 2, 1]) {
    await click(w, `${rate}x`);
    expect(playbackRate).toHaveBeenLastCalledWith(rate);
    expect(uni.createVideoContext).toHaveBeenLastCalledWith(
      w.get('video').attributes('id'),
      expect.anything(),
    );
    expect(w.find('.playback-rate-menu').exists()).toBe(false);
    expect(w.get('.playback-rate-trigger').text()).toBe(`倍速 · ${rate}x`);
    await w.get('.playback-rate-trigger').trigger('click');
    expect(w.get('.playback-rate-button--active').attributes('aria-pressed')).toBe('true');
    expect(w.get('.playback-rate-button--active').text()).toBe(`${rate}x`);
    await w.get('.playback-rate-dismiss').trigger('click');
  }
  expect(hooks.request.mock.calls.filter(([path]) => path.endsWith('/play-url'))).toHaveLength(1);
});

it('retains speed across chapter changes and reapplies it when the next video becomes ready', async () => {
  const playbackRate = vi.fn();
  vi.mocked(uni.createVideoContext).mockReturnValue({
    playbackRate,
  } as unknown as UniNamespace.VideoContext);
  const w = await open();
  await click(w, '播放当前章节');
  await click(w, '1.25x');
  const previousId = w.get('video').attributes('id');
  await click(w, '下一讲');
  expect(w.find('video').exists()).toBe(false);
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  await click(w, '播放当前章节');
  expect(w.get('video').attributes('id')).not.toBe(previousId);
  playbackRate.mockClear();
  await media(w, 'loadedmetadata');
  expect(playbackRate).toHaveBeenLastCalledWith(1.25);
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 1.25x');
  expect(uni.createVideoContext).toHaveBeenLastCalledWith(
    w.get('video').attributes('id'),
    expect.anything(),
  );
  await media(w, 'play');
  expect(playbackRate).toHaveBeenLastCalledWith(1.25);
});

it('reapplies speed to a renewed authorization and ignores the retired player readiness', async () => {
  const playbackRate = vi.fn();
  vi.mocked(uni.createVideoContext).mockReturnValue({
    playbackRate,
  } as unknown as UniNamespace.VideoContext);
  const w = await open();
  await click(w, '播放当前章节');
  await click(w, '2x');
  const oldVideo = w.get('video').element;
  await click(w, '播放当前章节');
  playbackRate.mockClear();
  oldVideo.dispatchEvent(new Event('loadedmetadata'));
  await flushPromises();
  expect(playbackRate).not.toHaveBeenCalled();
  await media(w, 'loadedmetadata');
  expect(playbackRate).toHaveBeenLastCalledWith(2);
});

it('reports native rate errors without selecting a rate that failed or leaking platform details', async () => {
  const playbackRate = vi.fn(() => {
    throw new Error('private platform details');
  });
  vi.mocked(uni.createVideoContext).mockReturnValue({
    playbackRate,
  } as unknown as UniNamespace.VideoContext);
  const w = await open();
  await click(w, '播放当前章节');
  await click(w, '1.5x');
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 1x');
  expect(w.text()).toContain('倍速设置失败');
  expect(w.text()).not.toContain('private platform details');
  playbackRate.mockImplementation(() => undefined);
  await click(w, '1.5x');
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 1.5x');
  expect(w.text()).not.toContain('倍速设置失败');
});

it('saves the actual video position at double speed and resets on reopening the course route', async () => {
  const playbackRate = vi.fn();
  vi.mocked(uni.createVideoContext).mockReturnValue({
    playbackRate,
  } as unknown as UniNamespace.VideoContext);
  const w = await open();
  await click(w, '播放当前章节');
  await click(w, '2x');
  await media(w, 'timeupdate', { currentTime: 19 });
  await media(w, 'pause');
  expect(hooks.request).toHaveBeenCalledWith('/learning/chapters/trial/progress', {
    method: 'PUT',
    body: { positionSeconds: 19, completed: false },
  });
  hooks.load.forEach((fn) => fn({ id: course.id }));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.find('.playback-rate-trigger').exists()).toBe(false);
  await click(w, '播放当前章节');
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 1x');
});

it('keeps the speed entry inside the video on fullscreen and clears the menu on lifecycle changes', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  await w.get('.playback-rate-trigger').trigger('click');
  await media(w, 'fullscreenchange', { fullScreen: true });
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  expect(w.get('video .playback-rate-trigger').exists()).toBe(true);
  await click(w, '2x');
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 2x');
  await w.get('.playback-rate-trigger').trigger('click');
  hooks.hide.forEach((fn) => fn());
  await flushPromises();
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  await click(w, '播放当前章节');
  expect(w.find('.playback-rate-menu').exists()).toBe(false);
  expect(w.get('.playback-rate-trigger').text()).toBe('倍速 · 2x');
});
it('allows an anonymous trial but never authorizes a locked paid chapter', async () => {
  session.clear();
  hooks.request.mockImplementation(async (path) =>
    path.endsWith('/play-url')
      ? grant()
      : {
          ...structuredClone(course),
          entitlement: 'NONE',
          allowedActions: [],
          chapters: course.chapters.map((ch) => ({ ...ch, canPlay: ch.isTrial })),
        },
  );
  const w = await open();
  expect(w.text()).not.toContain('已购正版权益');
  await click(w, '播放当前章节');
  expect(w.find('video').exists()).toBe(true);
  await click(w, '下一讲');
  expect(w.find('video').exists()).toBe(false);
  await click(w, '播放当前章节');
  expect(w.text()).toContain('暂无播放权限');
  expect(hooks.request.mock.calls.filter(([path]) => path.endsWith('/play-url'))).toHaveLength(1);
});
it('shows unavailable content rather than missing permission for an owned course with unplayable chapters', async () => {
  let ready = false;
  hooks.request.mockImplementation(async (path) =>
    path.endsWith('/play-url')
      ? grant()
      : {
          ...structuredClone(course),
          chapters: course.chapters.map((ch) => ({ ...ch, canPlay: ready })),
        },
  );
  const w = await open();
  expect(w.text()).toContain('已购正版权益');
  expect(w.text()).toContain('暂不可播放');
  expect(w.findAll('.locked-box')).toHaveLength(0);
  await click(w, '播放当前章节');
  expect(w.text()).toContain('当前章节内容暂不可播放');
  expect(w.text()).not.toContain('暂无播放权限');
  await click(w, '下一讲');
  await click(w, '播放当前章节');
  expect(w.text()).toContain('当前章节内容暂不可播放');
  expect(w.find('video').exists()).toBe(false);
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
  ready = true;
  await click(w, '重试加载');
  await click(w, '播放当前章节');
  expect(w.find('video').exists()).toBe(true);
  expect(w.text()).not.toContain('暂不可播放');
});
it('treats an unplayable free trial as unavailable content while keeping the unowned paid chapter locked', async () => {
  session.clear();
  hooks.request.mockImplementation(async () => ({
    ...structuredClone(course),
    entitlement: 'NONE',
    allowedActions: [],
    chapters: course.chapters.map((ch) => ({ ...ch, canPlay: false })),
  }));
  const w = await open();
  expect(w.findAll('.locked-box')).toHaveLength(1);
  await click(w, '播放当前章节');
  expect(w.text()).toContain('当前章节内容暂不可播放');
  await click(w, '下一讲');
  await click(w, '播放当前章节');
  expect(w.text()).toContain('暂无播放权限');
  expect(w.find('video').exists()).toBe(false);
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
});
it('changes chapters without authorizing and suppresses the late previous grant', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation((path) =>
    path.endsWith('/play-url')
      ? new Promise((done) => {
          resolve = done;
        })
      : Promise.resolve(structuredClone(course)),
  );
  const w = await open();
  await click(w, '播放当前章节');
  await click(w, '下一讲');
  resolve(grant());
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  expect(w.get('.chapter-item--active').text()).toContain('真实付费章节');
  await click(w, '上一讲');
  expect(w.get('.chapter-item--active').text()).toContain('真实试看章节');
});
it('mounts a fresh video for a new grant with the same URL and ignores events from the old media', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  const oldVideo = w.get('video').element;
  await click(w, '下一讲');
  await click(w, '播放当前章节');
  expect(w.get('video').element).not.toBe(oldVideo);
  vi.mocked(sessionStorage.set).mockClear();
  oldVideo.dispatchEvent(new CustomEvent('timeupdate', { detail: { currentTime: 55 } }));
  oldVideo.dispatchEvent(new Event('pause'));
  await flushPromises();
  expect(sessionStorage.set).not.toHaveBeenCalled();
  expect(
    hooks.request.mock.calls.some(([path]) => path === '/learning/chapters/paid/progress'),
  ).toBe(false);
});
it('flushes pause and completed events into authoritative chapter progress', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  await media(w, 'timeupdate', { currentTime: 19 });
  await media(w, 'pause');
  expect(hooks.request).toHaveBeenCalledWith('/learning/chapters/trial/progress', {
    method: 'PUT',
    body: { positionSeconds: 19, completed: false },
  });
  await media(w, 'ended');
  expect(w.find('video').exists()).toBe(false);
  expect(w.get('.chapter-item--active').text()).toContain('已学完');
});
it('shows failed throttled progress and retries the actual failed body', async () => {
  vi.useFakeTimers();
  let fail = true;
  hooks.request.mockImplementation(async (path) => {
    if (path.endsWith('/progress')) {
      if (fail) throw new Error('secret URL');
      return;
    }
    return path.endsWith('/play-url') ? grant() : structuredClone(course);
  });
  const w = await open();
  await click(w, '播放当前章节');
  await vi.advanceTimersByTimeAsync(15000);
  await media(w, 'timeupdate', { currentTime: 22 });
  expect(w.text()).toContain('学习进度保存失败');
  fail = false;
  await click(w, '重试保存进度');
  expect(w.text()).not.toContain('学习进度保存失败');
  expect(
    hooks.request.mock.calls
      .filter(([path]) => path.endsWith('/progress'))
      .map(([, options]) => options.body),
  ).toEqual([
    { positionSeconds: 22, completed: false },
    { positionSeconds: 22, completed: false },
  ]);
});
it('clears media on hide and awaits the queued final save before unload disposal', async () => {
  let finish!: () => void;
  hooks.request.mockImplementation((path) =>
    path.endsWith('/progress')
      ? new Promise<void>((done) => {
          finish = done;
        })
      : Promise.resolve(path.endsWith('/play-url') ? grant() : structuredClone(course)),
  );
  const w = await open();
  await click(w, '播放当前章节');
  await media(w, 'timeupdate', { currentTime: 12 });
  await media(w, 'pause');
  await media(w, 'timeupdate', { currentTime: 24 });
  hooks.hide.forEach((fn) => fn());
  hooks.unload.forEach((fn) => fn());
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  finish();
  await flushPromises();
  const writes = hooks.request.mock.calls.filter(([path]) => path.endsWith('/progress'));
  expect(writes.map(([, options]) => options.body.positionSeconds)).toEqual([12, 24]);
  finish();
  await flushPromises();
});
it('unmount alone flushes the latest progress', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  await media(w, 'timeupdate', { currentTime: 18 });
  w.unmount();
  wrappers.splice(wrappers.indexOf(w), 1);
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/learning/chapters/trial/progress',
    expect.objectContaining({ body: { positionSeconds: 18, completed: false } }),
  );
});
it('clears displayed course and suppresses an old account authorization', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation((path) =>
    path.endsWith('/play-url')
      ? new Promise((done) => {
          resolve = done;
        })
      : Promise.resolve(structuredClone(course)),
  );
  const w = await open();
  await click(w, '播放当前章节');
  session.clear();
  resolve(grant());
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  expect(w.text()).not.toContain(course.title);
});
it('rereads on return, shows expired access and rebuy uses the product ID', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  hooks.hide.forEach((fn) => fn());
  hooks.request.mockResolvedValue({ ...course, entitlement: 'EXPIRED', allowedActions: [] });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  expect(w.text()).toContain('课程权益已过期');
  await click(w, '重新购买');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/product/detail?id=purchase%2Fproduct',
  });
});
it('sanitizes platform errors, clears failed media and reauthorizes a retry', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  await media(w, 'fullscreenchange', { fullScreen: true });
  expect(w.text()).toContain('全屏播放');
  await media(w, 'error', { errMsg: 'https://private.invalid/key' });
  expect(w.find('video').exists()).toBe(false);
  expect(w.text()).toContain('视频播放失败');
  expect(w.text()).not.toContain('private.invalid');
  await click(w, '播放当前章节');
  expect(w.find('video').exists()).toBe(true);
});
it('a new route cannot display the old pending course', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = await open();
  expect(w.text()).toContain('正在加载');
  hooks.load.forEach((fn) => fn({ id: 'new/course' }));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  resolve({ ...course, title: '旧路由敏感课程' });
  await flushPromises();
  expect(w.text()).not.toContain('旧路由敏感课程');
});
it('loads server status tabs and paging with real encoded IDs and no invented summary', async () => {
  hooks.request.mockImplementation(async (_path, options) => ({
    items: [
      {
        ...course,
        id: options.query.page === 1 ? 'actual/id' : 'second',
        title: options.query.page === 1 ? '真实第一页' : '真实第二页',
      },
    ],
    page: options.query.page,
    pageSize: 1,
    total: 2,
  }));
  const w = await open(Learning, { status: 'unknown' });
  expect(hooks.request).toHaveBeenCalledWith(
    '/learning/courses',
    expect.objectContaining({ query: { status: 'IN_PROGRESS', page: 1, pageSize: 10 } }),
  );
  expect(w.text()).not.toContain('18.5');
  expect(w.text()).not.toContain('82');
  expect(w.text()).not.toContain('累计学时');
  expect(w.text()).not.toContain('平均完播率');
  expect(w.text()).toContain('共 2 门');
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('真实第二页');
  await w.get('.enrolled-card').trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=actual%2Fid&productId=purchase%2Fproduct',
  });
  expect(w.findAll('.tab-item').map((tab) => tab.text())).toEqual(['学习中', '已完成', '已过期']);
});
it('preserves the last successful list during refresh error and retries', async () => {
  hooks.request.mockResolvedValue({ items: [course], page: 1, pageSize: 10, total: 1 });
  const w = await open(Learning, {});
  hooks.request.mockRejectedValueOnce(new Error('url secret'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain(course.title);
  expect(w.text()).toContain('课程列表加载失败');
  await click(w, '重试加载');
  expect(w.text()).not.toContain('课程列表加载失败');
});
it('clears the learning list on session replacement and rejects late old results', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = await open(Learning, {});
  expect(w.text()).toContain('正在加载');
  session.clear();
  resolve({ items: [course], page: 1, pageSize: 10, total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain(course.title);
  expect(w.text()).toContain('登录后查看');
  await click(w, '去登录');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/login/index' });
});
it('keeps a refresh retry authoritative when a complete list reaches bottom during it', async () => {
  hooks.request.mockResolvedValue({ items: [course], page: 1, pageSize: 10, total: 1 });
  const w = await open(Learning, {});
  hooks.request.mockRejectedValueOnce(new Error('refresh failed'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('课程列表加载失败');
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await click(w, '重试加载');
  expect(w.text()).toContain('正在加载');
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  resolve({ items: [{ ...course, title: '重试后的课程' }], page: 1, pageSize: 10, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('重试后的课程');
  expect(w.text()).not.toContain('正在加载');
  expect(w.text()).not.toContain('课程列表加载失败');
});
it('shows the API empty list and supports browsing courses', async () => {
  hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0 });
  const w = await open(Learning, { status: 'COMPLETED' });
  expect(w.text()).toContain('暂无课程');
  await click(w, '去选课');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/product/list?type=COURSE' });
});
it('routes trial and off-sale purchased products using the distinct public course ID', async () => {
  const original = createDevCatalogStore().products.find((item) => item.type === 'COURSE')!;
  hooks.request.mockResolvedValue({
    ...original,
    status: 'OFF_SALE',
    allowedActions: ['VIEW_CONTENT', 'TRIAL'],
    course: { ...original.course, courseId: 'distinct/course', entitlement: 'ACTIVE' },
  });
  const w = await open(ProductDetail, { id: 'product' });
  await click(w, '进入学习');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=distinct%2Fcourse&productId=DEV-course',
  });
  await click(w, '免费试看');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/course/learn?id=distinct%2Fcourse&productId=DEV-course',
  });
});

it('keeps the successful list visible while refreshing after a page hide', async () => {
  hooks.request.mockResolvedValue({ items: [course], page: 1, pageSize: 10, total: 1 });
  const w = await open(Learning, {});
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
  expect(w.text()).toContain(course.title);
  expect(w.text()).toContain('正在加载');
  resolve({ items: [{ ...course, title: '刷新后的课程' }], page: 1, pageSize: 10, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('刷新后的课程');
});

it('a membership-only account cannot open a paid chapter even when canPlay is inconsistent', async () => {
  hooks.request.mockImplementation(async (path) =>
    path.endsWith('/play-url')
      ? grant()
      : {
          ...structuredClone(course),
          entitlement: 'NONE',
          allowedActions: ['MEMBERSHIP_DOWNLOAD'],
        },
  );
  const w = await open();
  await w.findAll('.chapter-item')[1]!.trigger('click');
  await flushPromises();
  await click(w, '播放当前章节');
  expect(w.text()).toContain('暂无播放权限');
  expect(w.find('video').exists()).toBe(false);
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
});

it('saves selected chapter progress before switching and explicitly authorizes the next chapter', async () => {
  const w = await open();
  await click(w, '播放当前章节');
  await media(w, 'timeupdate', { currentTime: 31 });
  await w.findAll('.chapter-item')[1]!.trigger('click');
  await flushPromises();
  expect(w.find('video').exists()).toBe(false);
  expect(hooks.request).toHaveBeenCalledWith('/learning/chapters/trial/progress', {
    method: 'PUT',
    body: { positionSeconds: 31, completed: false },
  });
  await click(w, '播放当前章节');
  expect(hooks.request).toHaveBeenLastCalledWith('/courses/real%2Fcourse/chapters/paid/play-url');
  expect(w.find('video').exists()).toBe(true);
});

it('clears a previously rendered list immediately on account replacement and loads the new account on return', async () => {
  hooks.request.mockResolvedValue({ items: [course], page: 1, pageSize: 10, total: 1 });
  const w = await open(Learning, {});
  expect(w.text()).toContain(course.title);
  login('second-learner');
  await flushPromises();
  expect(w.text()).not.toContain(course.title);
  hooks.request.mockResolvedValue({
    items: [{ ...course, title: '新账号课程' }],
    page: 1,
    pageSize: 10,
    total: 1,
  });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('新账号课程');
});

it('ignores a late old-tab result and retries a failed next page without losing the first page', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = await open(Learning, {});
  expect(w.text()).toContain('正在加载');
  hooks.request.mockResolvedValue({
    items: [{ ...course, title: '已完成课程' }],
    page: 1,
    pageSize: 1,
    total: 2,
  });
  await w.findAll('.tab-item')[1]!.trigger('click');
  await flushPromises();
  resolve({ items: [{ ...course, title: '过时在学课程' }], page: 1, pageSize: 10, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('已完成课程');
  expect(w.text()).not.toContain('过时在学课程');
  hooks.request.mockRejectedValueOnce(new Error('offline'));
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('已完成课程');
  expect(w.text()).toContain('课程列表加载失败');
  hooks.request.mockResolvedValue({
    items: [{ ...course, id: 'second', title: '完成的第二页' }],
    page: 2,
    pageSize: 1,
    total: 2,
  });
  await click(w, '重试加载');
  expect(w.text()).toContain('已完成课程');
  expect(w.text()).toContain('完成的第二页');
  expect(hooks.request).toHaveBeenLastCalledWith(
    '/learning/courses',
    expect.objectContaining({ query: { status: 'COMPLETED', page: 2, pageSize: 10 } }),
  );
});

it('keeps only the latest classification when tabs are switched rapidly and resets paging', async () => {
  const pending: Array<(value: unknown) => void> = [];
  hooks.request.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
  const w = await open(Learning, { status: 'IN_PROGRESS' });
  await w.findAll('.tab-item')[1]!.trigger('click');
  await flushPromises();
  await w.findAll('.tab-item')[2]!.trigger('click');
  await flushPromises();
  expect(hooks.request.mock.calls.map(([, options]) => options.query)).toEqual([
    { status: 'IN_PROGRESS', page: 1, pageSize: 10 },
    { status: 'COMPLETED', page: 1, pageSize: 10 },
    { status: 'EXPIRED', page: 1, pageSize: 10 },
  ]);
  pending[2]!({
    items: [{ ...course, entitlement: 'EXPIRED', title: '最新过期课程' }],
    page: 1,
    pageSize: 10,
    total: 1,
  });
  await flushPromises();
  pending[1]!({ items: [{ ...course, title: '迟到完成课程' }], page: 1, pageSize: 10, total: 1 });
  pending[0]!({ items: [{ ...course, title: '迟到在学课程' }], page: 1, pageSize: 10, total: 1 });
  await flushPromises();
  expect(w.text()).toContain('最新过期课程');
  expect(w.text()).toContain('课程权益已过期');
  expect(w.text()).not.toContain('迟到');
  expect(w.get('.tab-item--active').text()).toBe('已过期');
  expect(w.text()).not.toContain('正在加载');
});

it('opens the requested completed classification and retries the same filter after a failure', async () => {
  hooks.request.mockRejectedValueOnce(new Error('offline'));
  const w = await open(Learning, { status: 'COMPLETED' });
  expect(w.get('.tab-item--active').text()).toBe('已完成');
  expect(w.text()).toContain('课程列表加载失败');
  hooks.request.mockResolvedValueOnce({ items: [], page: 1, pageSize: 10, total: 0 });
  await click(w, '重试加载');
  expect(hooks.request).toHaveBeenLastCalledWith('/learning/courses', {
    query: { status: 'COMPLETED', page: 1, pageSize: 10 },
  });
  expect(w.text()).toContain('当前分类暂无课程');
  expect(w.text()).not.toContain('课程列表加载失败');
});

it('shows trial and locked chapter information on the product page and opens the chosen trial', async () => {
  const original = createDevCatalogStore().products.find((item) => item.type === 'COURSE')!;
  const trial = { ...course.chapters[0], id: 'trial/one' };
  const locked = { ...course.chapters[1], canPlay: false };
  hooks.request.mockImplementation(async (path) =>
    path.includes('/chapters')
      ? { items: [trial, locked], page: 1, pageSize: 20, total: 2 }
      : {
          ...original,
          allowedActions: ['BUY', 'TRIAL'],
          course: { ...original.course, courseId: 'preview/course', entitlement: 'NONE' },
        },
  );
  const w = await open(ProductDetail, { id: original.id });
  expect(w.get('.chapter-preview').text()).toContain('真实试看章节');
  expect(w.get('.chapter-preview').text()).toContain('真实付费章节');
  expect(w.get('.chapter-preview').text()).toContain('购买后解锁');
  const rows = w.findAll('.chapter-preview__row');
  expect(rows[1].attributes('disabled')).toBeDefined();
  await rows[0].trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=preview%2Fcourse&productId=DEV-course&chapterId=trial%2Fone',
  });
  expect(hooks.request.mock.calls.some(([path]) => path.endsWith('/play-url'))).toBe(false);
});
