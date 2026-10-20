// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createSession } from '../../shared/src/session';
import { useRecentLearning } from '../src/composables/useRecentLearning';
import { session } from '../src/api/session';
const api = vi.hoisted(() => ({ load: vi.fn(), invalidate: vi.fn() }));
vi.mock('../src/api/recent-learning', () => ({ recentLearning: api }));
vi.mock('../src/api/session', () => ({
  session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
}));
const Harness = defineComponent({
  setup: useRecentLearning,
  template:
    '<div><p v-if="loading">加载中</p><p v-if="error">{{error}}</p><p v-if="recent">{{recent.course.title}}</p><button @click="loadRecent">加载</button><button @click="continueLearning">继续</button></div>',
});
const wrappers: VueWrapper[] = [];
const value = {
  course: { id: 'course/1', productId: 'product/2', title: '真实学习记录' },
  chapter: { id: 'chapter/3' },
  positionSeconds: 24,
  progressPercent: 0,
  source: 'LOCAL',
};
function open() {
  const w = mount(Harness);
  wrappers.push(w);
  return w;
}
beforeEach(() => {
  vi.resetAllMocks();
  session.clear();
  session.set({
    accessToken: 'a',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'a', nickname: 'a', distributor: false },
  });
  vi.stubGlobal('uni', { navigateTo: vi.fn() });
  api.load.mockResolvedValue(value);
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
it('shows real records and navigates with escaped identifiers without authorizing playback', async () => {
  const w = open();
  await w.findAll('button')[0].trigger('click');
  await flushPromises();
  expect(w.text()).toContain('真实学习记录');
  await w.findAll('button')[1].trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=course%2F1&productId=product%2F2&chapterId=chapter%2F3',
  });
  session.clear();
  await flushPromises();
  expect(w.text()).not.toContain('真实学习记录');
  await w.findAll('button')[1].trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledTimes(1);
});
it('separates failures from empty history and supports retry without leaking response details', async () => {
  const w = open();
  api.load.mockRejectedValueOnce(new Error('https://secret/grant'));
  await w.findAll('button')[0].trigger('click');
  await flushPromises();
  expect(w.text()).toContain('最近学习加载失败');
  expect(w.text()).not.toContain('secret');
  api.load.mockResolvedValueOnce(null);
  await w.findAll('button')[0].trigger('click');
  await flushPromises();
  expect(w.text()).not.toContain('失败');
  expect(w.text()).not.toContain('真实学习记录');
});
it('clears loading and drops late results on logout', async () => {
  let finish!: (value: unknown) => void;
  api.load.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  const w = open();
  await w.findAll('button')[0].trigger('click');
  expect(w.text()).toContain('加载中');
  session.clear();
  finish(value);
  await flushPromises();
  expect(w.text()).not.toContain('加载中');
  expect(w.text()).not.toContain('真实学习记录');
});
it('keeps the newest request when overlapping refreshes complete out of order', async () => {
  let finish!: (value: unknown) => void;
  api.load.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  const w = open();
  await w.findAll('button')[0].trigger('click');
  await w.findAll('button')[0].trigger('click');
  await flushPromises();
  finish({ ...value, course: { ...value.course, title: '旧结果' } });
  await flushPromises();
  expect(w.text()).toContain('真实学习记录');
  expect(w.text()).not.toContain('旧结果');
});
