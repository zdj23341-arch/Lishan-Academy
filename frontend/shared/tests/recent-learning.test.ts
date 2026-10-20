import { beforeEach, expect, it, vi } from 'vitest';
import { createSession } from '../src/session';
import { createRecentLearning } from '../src/learning/recent';
import type { CourseView } from '../src/learning/types';

let now = Date.parse('2026-10-09T12:00:00Z');
const values = new Map<string, string>();
const storage = {
  get: (key: string) => values.get(key) ?? null,
  set: (key: string, value: string) => {
    values.set(key, value);
  },
  remove: (key: string) => {
    values.delete(key);
  },
};
const session = createSession(storage);
const course: CourseView = {
  id: 'c/1',
  productId: 'p/1',
  title: '真实课程',
  entitlement: 'ACTIVE',
  expiresAt: '2098-01-01T00:00:00Z',
  allowedActions: ['VIEW_CONTENT'],
  chapters: [
    {
      id: 'ch/1',
      title: '真实章节',
      canPlay: true,
      isTrial: false,
      durationSeconds: 100,
      lastPositionSeconds: 20,
      completed: false,
    },
  ],
};
function login(id = 'a') {
  session.set({
    accessToken: id,
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id, nickname: id, distributor: false },
  });
}
function setup(namespace = 'http-local') {
  const api = { course: vi.fn().mockResolvedValue(structuredClone(course)) };
  return {
    api,
    recent: createRecentLearning({ session, storage, namespace, api, now: () => now }),
  };
}
function playback() {
  return {
    course: structuredClone(course),
    chapterId: 'ch/1',
    positionSeconds: 24,
    authorization: {
      url: 'https://media.invalid/private?secret=grant',
      expiresAt: '2098-01-01T00:00:00Z',
      resumeSeconds: 20,
    },
    generation: session.getGeneration(),
  };
}
beforeEach(() => {
  values.clear();
  session.clear();
  now = Date.parse('2026-10-09T12:00:00Z');
  login();
});

it('starts empty and stores only identifiers and real bounded playback, never grants or tokens', async () => {
  const { recent, api } = setup();
  expect(await recent.load()).toBeNull();
  expect(api.course).not.toHaveBeenCalled();
  recent.record({ ...playback(), positionSeconds: 120 });
  const latest = await recent.load();
  expect(latest).toMatchObject({
    course: { title: '真实课程' },
    chapter: { title: '真实章节' },
    positionSeconds: 100,
    progressPercent: 0,
    source: 'LOCAL',
  });
  const persisted = [...values.entries()].filter(([key]) => key.startsWith('jxsc.learning.recent'));
  expect(persisted).toHaveLength(1);
  expect(persisted[0]![1]).not.toMatch(/secret|grant|token|真实课程/);
});
it('isolates accounts and environments while allowing the same account to restore local history', async () => {
  const { recent } = setup();
  recent.record(playback());
  session.clear();
  expect(await recent.load()).toBeNull();
  login('b');
  expect(await recent.load()).toBeNull();
  login('a');
  expect((await recent.load())?.positionSeconds).toBe(24);
  expect(await setup('mock-local').recent.load()).toBeNull();
  expect((await setup().recent.load())?.chapter.id).toBe('ch/1');
});
it('rejects stale actors, anonymous, invalid positions and expired grants without storing a record', async () => {
  const { recent } = setup();
  const previous = playback();
  login('b');
  recent.record(previous);
  recent.record({ ...playback(), positionSeconds: NaN });
  recent.record({
    ...playback(),
    authorization: { ...playback().authorization, expiresAt: '2020-01-01T00:00:00Z' },
  });
  session.clear();
  recent.record(playback());
  login('b');
  expect(await recent.load()).toBeNull();
});
it('revalidates entitlement and chapter permissions and keeps errors from being mistaken for empty history', async () => {
  const { recent, api } = setup();
  recent.record(playback());
  api.course.mockRejectedValueOnce(new Error('network'));
  await expect(recent.load()).rejects.toThrow('network');
  api.course.mockResolvedValueOnce({ ...course, entitlement: 'EXPIRED' });
  expect(await recent.load()).toBeNull();
  expect(await recent.load()).toBeNull();
  expect(api.course).toHaveBeenCalledTimes(2);
  recent.record(playback());
  api.course.mockResolvedValueOnce({
    ...course,
    chapters: [{ ...course.chapters[0], canPlay: false }],
  });
  expect(await recent.load()).toBeNull();
});
it('discards late responses after account replacement and after a newer load', async () => {
  const { recent, api } = setup();
  recent.record(playback());
  let resolve!: (value: CourseView) => void;
  api.course.mockImplementationOnce(
    () =>
      new Promise<CourseView>((done) => {
        resolve = done;
      }),
  );
  const pending = recent.load();
  login('b');
  resolve(course);
  expect(await pending).toBeNull();
  login('a');
  api.course.mockImplementationOnce(
    () =>
      new Promise<CourseView>((done) => {
        resolve = done;
      }),
  );
  const older = recent.load();
  expect(await recent.load()).not.toBeNull();
  resolve(course);
  expect(await older).toBeNull();
});
it('ignores malformed local data and tolerates unavailable storage without breaking playback', async () => {
  const { recent } = setup();
  recent.record(playback());
  const key = [...values.keys()].find((key) => key.startsWith('jxsc.learning.recent'))!;
  values.set(key, '{broken');
  expect(await recent.load()).toBeNull();
  const broken = createRecentLearning({
    session,
    namespace: 'broken',
    api: { course: vi.fn() },
    storage: {
      get() {
        throw new Error();
      },
      set() {
        throw new Error();
      },
      remove() {
        throw new Error();
      },
    },
    now: () => now,
  });
  expect(() => broken.record(playback())).not.toThrow();
  expect(await broken.load()).toBeNull();
});
it('restores the newest real server resume across pages when the device has no local history', async () => {
  const older = {
    ...course,
    resume: {
      chapterId: 'ch/1',
      chapterTitle: '服务器章节',
      positionSeconds: 10,
      lastLearnedAt: '2026-10-09T10:00:00Z',
    },
  };
  const newer = {
    ...older,
    resume: { ...older.resume, positionSeconds: 45, lastLearnedAt: '2026-10-09T11:00:00Z' },
  };
  const api = {
    courses: vi
      .fn()
      .mockResolvedValueOnce({ items: [older], page: 1, pageSize: 1, total: 2 })
      .mockResolvedValueOnce({ items: [newer], page: 2, pageSize: 1, total: 2 }),
    course: vi.fn().mockResolvedValue({ ...course, completedChapters: 1, totalChapters: 4 }),
  };
  const recent = createRecentLearning({
    session,
    storage,
    namespace: 'server',
    api,
    now: () => now,
  });
  expect(await recent.load()).toMatchObject({
    positionSeconds: 45,
    source: 'SERVER',
    progressPercent: 25,
  });
  expect(api.courses).toHaveBeenCalledTimes(2);
  expect(api.course).toHaveBeenCalledWith(course.id, course.productId);
});
it('keeps newer device playback while the backend saves catch up and does not fabricate a server resume', async () => {
  const api = {
    courses: vi.fn().mockResolvedValue({
      items: [
        {
          ...course,
          resume: {
            chapterId: 'ch/1',
            chapterTitle: '章节',
            positionSeconds: 10,
            lastLearnedAt: '2026-10-09T11:00:00Z',
          },
        },
      ],
      page: 1,
      pageSize: 100,
      total: 1,
    }),
    course: vi.fn().mockResolvedValue(course),
  };
  const recent = createRecentLearning({
    session,
    storage,
    namespace: 'server',
    api,
    now: () => now,
  });
  recent.record(playback());
  expect(await recent.load()).toMatchObject({ positionSeconds: 24, source: 'LOCAL' });
  values.clear();
  api.courses.mockResolvedValue({
    items: [{ ...course, resume: null }],
    page: 1,
    pageSize: 100,
    total: 1,
  });
  expect(await recent.load()).toBeNull();
});

it('restores a few seconds of paid playback when the catalog grants LEARN', async () => {
  const { recent, api } = setup();
  recent.record({ ...playback(), positionSeconds: 3 });
  api.course.mockResolvedValue({ ...course, allowedActions: ['LEARN'] });
  expect(await recent.load()).toMatchObject({ positionSeconds: 3, chapter: { id: 'ch/1' } });
});
