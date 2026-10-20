import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSession } from '../src/session';
import { ApiError, createClient } from '../src/http';
import { createLearningApi } from '../src/learning/api';
import { createCourseController } from '../src/learning/course';
import type { CourseProgress, CourseView } from '../src/learning/types';

const course: CourseView = {
  id: 'course',
  productId: 'product',
  title: 'Course',
  entitlement: 'ACTIVE',
  expiresAt: null,
  allowedActions: ['VIEW_CONTENT'],
  chapters: [
    {
      id: 'a',
      title: 'A',
      durationSeconds: 100,
      isTrial: true,
      canPlay: true,
      lastPositionSeconds: 12,
      completed: false,
    },
    {
      id: 'b',
      title: 'B',
      durationSeconds: 120,
      isTrial: false,
      canPlay: true,
      lastPositionSeconds: 0,
      completed: false,
    },
    {
      id: 'locked',
      title: 'Locked',
      durationSeconds: 90,
      isTrial: false,
      canPlay: false,
      lastPositionSeconds: 0,
      completed: false,
    },
  ],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
function setup(loggedIn = true) {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-07T00:00:00Z'));
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const login = (id: string) =>
    session.set({
      accessToken: id,
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, nickname: id, distributor: false },
    });
  if (loggedIn) login('one');
  const api = {
    course: vi.fn(async () => structuredClone(course)),
    courses: vi.fn(),
    playUrl: vi.fn(async () => ({
      url: 'https://media.test/a?signature=secret',
      expiresAt: '2026-10-07T00:01:00Z',
      resumeSeconds: 12,
    })),
    progress: vi.fn<(course: string, chapter: string, progress: CourseProgress) => Promise<void>>(
      async () => {},
    ),
  };
  const controller = createCourseController({ api, session, courseId: 'course' });
  return { controller, api, session, login };
}
afterEach(() => vi.useRealTimers());
describe('course playback and progress', () => {
  it.each([
    ['FILE_NOT_READY', '内容暂不可播放'],
    ['UNAUTHORIZED', '请先登录'],
    ['FORBIDDEN', '暂无播放权限'],
    ['ENTITLEMENT_EXPIRED', '课程权益已过期'],
  ])('maps authoritative playback error %s to a safe actionable state', async (code, message) => {
    const { controller: c, api } = setup();
    await c.load();
    await c.select('b');
    api.playUrl.mockRejectedValueOnce(new ApiError(code, 'https://private.invalid/token'));
    await c.play();
    expect(c.state.error).toContain(message);
    expect(c.state.error).not.toContain('private.invalid');
    expect(c.state.authorization).toBeNull();
    expect(c.state.course?.entitlement).toBe('ACTIVE');
    await c.play();
    expect(c.state.authorization).not.toBeNull();
    c.dispose();
  });
  it('keeps refresh usable when a chapter click arrives before the course response', async () => {
    const { controller: c, api } = setup();
    await c.load();
    const refresh = deferred<CourseView>();
    api.course.mockReturnValueOnce(refresh.promise);
    const pending = c.load();
    await c.select('b');
    refresh.resolve({ ...structuredClone(course), title: 'Refreshed course' });
    await pending;
    expect(c.state.loading).toBe(false);
    expect(c.state.course?.title).toBe('Refreshed course');
    expect(c.state.selectedChapterId).toBe('b');
    await c.play();
    expect(c.state.authorization).not.toBeNull();
  });
  it('accepts VIEW_CONTENT for independent active course access', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.select('b');
    await c.play();
    expect(api.playUrl).toHaveBeenCalledWith('course', 'b');
    expect(c.state.authorization).not.toBeNull();
  });
  it.each(['NONE', 'EXPIRED'] as const)(
    'does not grant paid playback from an action without %s course entitlement',
    async (entitlement) => {
      const { controller: c, api } = setup();
      api.course.mockResolvedValue({ ...structuredClone(course), entitlement });
      await c.load();
      await c.select('b');
      await c.play();
      expect(api.playUrl).not.toHaveBeenCalled();
      expect(c.state.authorization).toBeNull();
    },
  );
  it('does not mistake an entitlement type for an allowed playback action', async () => {
    const { controller: c, api } = setup();
    api.course.mockResolvedValue({ ...structuredClone(course), allowedActions: ['COURSE_VIEW'] });
    await c.load();
    await c.select('b');
    await c.play();
    expect(api.playUrl).not.toHaveBeenCalled();
    expect(c.state.authorization).toBeNull();
  });
  it('requires explicit play and denies locked chapters independently of membership', async () => {
    const { controller: c, api } = setup();
    await c.load();
    expect(api.playUrl).not.toHaveBeenCalled();
    await c.select('locked');
    await c.play();
    expect(api.playUrl).not.toHaveBeenCalled();
    expect(c.state.authorization).toBeNull();
    api.course.mockResolvedValue({ ...course, allowedActions: ['MEMBERSHIP_VIEW'] });
    await c.load();
    await c.select('b');
    await c.play();
    expect(api.playUrl).not.toHaveBeenCalled();
  });
  it('authorizes a public trial without a fabricated session and skips anonymous progress', async () => {
    const { controller: c, api, session } = setup(false);
    await c.load();
    await c.play();
    expect(c.state.authorization?.resumeSeconds).toBe(12);
    expect(session.get()).toBeNull();
    c.timeUpdate(30);
    await c.pause();
    expect(api.progress).not.toHaveBeenCalled();
  });
  it('clears old media on chapter switch and discards late chapter authorization', async () => {
    const { controller: c, api } = setup();
    await c.load();
    const a = deferred<Awaited<ReturnType<typeof api.playUrl>>>();
    api.playUrl.mockReturnValueOnce(a.promise);
    const pending = c.play();
    await c.select('b');
    await c.play();
    a.resolve({
      url: 'https://media.test/old',
      expiresAt: '2026-10-07T00:01:00Z',
      resumeSeconds: 0,
    });
    await pending;
    expect(c.state.authorization?.url).not.toContain('old');
    await c.select('a');
    expect(c.state.authorization).toBeNull();
  });
  it.each(['hide', 'dispose', 'session'] as const)(
    'invalidates late playback on %s',
    async (event) => {
      const { controller: c, api, session } = setup();
      await c.load();
      const a = deferred<Awaited<ReturnType<typeof api.playUrl>>>();
      api.playUrl.mockReturnValueOnce(a.promise);
      const pending = c.play();
      if (event === 'session') session.clear();
      else c[event]();
      a.resolve({
        url: 'https://media.test/old',
        expiresAt: '2026-10-07T00:01:00Z',
        resumeSeconds: 0,
      });
      await pending;
      expect(c.state.authorization).toBeNull();
    },
  );
  it('expires ephemeral URLs without revoking course rights and reauthorizes explicitly', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    await vi.advanceTimersByTimeAsync(60000);
    expect(c.state.authorization).toBeNull();
    expect(c.state.course?.entitlement).toBe('ACTIVE');
    api.playUrl.mockResolvedValue({
      url: 'https://media.test/new',
      expiresAt: '2026-10-07T00:02:00Z',
      resumeSeconds: 13,
    });
    await c.play();
    expect(c.state.authorization?.url).toBe('https://media.test/new');
  });
  it('renews the playback capability before expiry so long chapters keep playing', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    c.timeUpdate(40);
    api.playUrl.mockResolvedValueOnce({
      url: 'https://media.test/renewed',
      expiresAt: '2026-10-07T00:06:00Z',
      resumeSeconds: 0,
    });
    await vi.advanceTimersByTimeAsync(29999);
    expect(api.playUrl).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(api.playUrl).toHaveBeenCalledTimes(2);
    expect(c.state.authorization?.url).toBe('https://media.test/renewed');
    expect(c.state.authorization?.resumeSeconds).toBe(40);
    expect(c.state.error).toBeNull();
    c.dispose();
  });
  it('keeps a paused chapter paused at expiry and reauthorizes on explicit play', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    await c.pause();
    const calls = api.playUrl.mock.calls.length;
    await vi.advanceTimersByTimeAsync(60000);
    expect(api.playUrl).toHaveBeenCalledTimes(calls);
    expect(c.state.authorization).toBeNull();
    api.playUrl.mockResolvedValueOnce({
      url: 'https://media.test/again',
      expiresAt: '2026-10-07T00:02:00Z',
      resumeSeconds: 0,
    });
    await c.play();
    expect(c.state.authorization).not.toBeNull();
    c.dispose();
  });
  it('renews again after a user pause is resumed by the native player', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    await c.pause();
    c.resume();
    api.playUrl.mockResolvedValueOnce({
      url: 'https://media.test/after-pause',
      expiresAt: '2026-10-07T00:06:00Z',
      resumeSeconds: 0,
    });
    await vi.advanceTimersByTimeAsync(30000);
    expect(api.playUrl).toHaveBeenCalledTimes(2);
    expect(c.state.authorization?.url).toBe('https://media.test/after-pause');
    c.dispose();
  });
  it('throttles to 15 seconds and flushes pause, switch and end with bounded positions', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    c.timeUpdate(20);
    await vi.advanceTimersByTimeAsync(14999);
    c.timeUpdate(30);
    expect(api.progress).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    c.timeUpdate(40);
    await c.pause();
    expect(api.progress).toHaveBeenLastCalledWith('course', 'a', {
      positionSeconds: 40,
      completed: false,
    });
    c.timeUpdate(91);
    await c.select('b');
    expect(api.progress).toHaveBeenLastCalledWith('course', 'a', {
      positionSeconds: 91,
      completed: false,
    });
    await c.play();
    c.timeUpdate(999);
    await c.ended();
    await c.ended();
    expect(api.progress).toHaveBeenLastCalledWith('course', 'b', {
      positionSeconds: 120,
      completed: true,
    });
    expect(api.progress.mock.calls.filter(([, , p]) => p.completed)).toHaveLength(1);
    expect(c.state.authorization).toBeNull();
  });
  it('serializes pending saves and retains failed completion for explicit retry', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    const first = deferred<void>();
    api.progress.mockReturnValueOnce(first.promise);
    c.timeUpdate(40);
    const pause = c.pause();
    const end = c.ended();
    expect(api.progress).toHaveBeenCalledTimes(1);
    api.progress.mockRejectedValueOnce(new Error('https://secret.test/token'));
    first.resolve();
    await pause;
    await end;
    expect(c.state.progressError).not.toBeNull();
    expect(JSON.stringify(c.state)).not.toContain('secret.test');
    expect(c.state.course?.chapters[0]?.completed).toBe(false);
    await c.retryProgress();
    expect(c.state.progressError).toBeNull();
    expect(c.state.course?.chapters[0]?.completed).toBe(true);
  });
  it('never starts an old actor queued save after session replacement', async () => {
    const { controller: c, api, login } = setup();
    await c.load();
    await c.play();
    const first = deferred<void>();
    api.progress.mockReturnValueOnce(first.promise);
    c.timeUpdate(40);
    const pause = c.pause();
    const end = c.ended();
    login('two');
    first.resolve();
    await pause;
    await end;
    expect(api.progress).toHaveBeenCalledTimes(1);
    expect(c.state.course).toBeNull();
    expect(c.state.authorization).toBeNull();
  });
  it('sanitizes authorization errors and prevents stale load restoration', async () => {
    const { controller: c, api, session } = setup();
    await c.load();
    api.playUrl.mockRejectedValue(new Error('https://secret.test/token'));
    await c.play();
    expect(JSON.stringify(c.state)).not.toContain('secret.test');
    expect(c.state.error).not.toBeNull();
    const pending = deferred<CourseView>();
    api.course.mockReturnValueOnce(pending.promise);
    const load = c.load();
    session.clear();
    pending.resolve(course);
    await load;
    expect(c.state.course).toBeNull();
  });
  it.each(['hide', 'dispose'] as const)('discards a pending course load on %s', async (event) => {
    const { controller: c, api } = setup();
    const response = deferred<CourseView>();
    api.course.mockReturnValueOnce(response.promise);
    const loading = c.load();
    c[event]();
    response.resolve(structuredClone(course));
    await loading;
    expect(c.state.course).toBeNull();
    expect(c.state.loading).toBe(false);
  });
  it.each([
    { position: -20, saved: 0 },
    { position: 999, saved: 100 },
  ])('bounds pause progress $position to $saved seconds', async ({ position, saved }) => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    c.timeUpdate(position);
    await c.pause();
    expect(api.progress).toHaveBeenLastCalledWith('course', 'a', {
      positionSeconds: saved,
      completed: false,
    });
  });
  it('clears active media synchronously on hide before its final progress save finishes', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    c.timeUpdate(27);
    const save = deferred<void>();
    api.progress.mockReturnValueOnce(save.promise);
    const hidden = c.hide();
    expect(c.state.authorization).toBeNull();
    expect(api.progress).toHaveBeenCalledWith('course', 'a', {
      positionSeconds: 27,
      completed: false,
    });
    save.resolve();
    await hidden;
  });
  it('does not replay an older failed position after a newer completion succeeds', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    api.progress.mockRejectedValueOnce(new Error('offline'));
    c.timeUpdate(40);
    await c.pause();
    const completion = deferred<void>();
    api.progress.mockReturnValueOnce(completion.promise);
    const end = c.ended();
    const retry = c.retryProgress();
    completion.resolve();
    await end;
    await retry;
    expect(api.progress).toHaveBeenCalledTimes(2);
    expect(c.state.course?.chapters[0]?.completed).toBe(true);
  });
  it('does not undo pending completion when replay progress is queued', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    const completion = deferred<void>();
    api.progress.mockReturnValueOnce(completion.promise);
    const end = c.ended();
    await c.play();
    c.timeUpdate(20);
    const pause = c.pause();
    completion.resolve();
    await end;
    await pause;
    expect(api.progress).toHaveBeenLastCalledWith('course', 'a', {
      positionSeconds: 20,
      completed: true,
    });
    expect(c.state.course?.chapters[0]?.completed).toBe(true);
  });
  it('does not replay stale failed progress when completion starts immediately after retry', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    api.progress.mockRejectedValueOnce(new Error('offline'));
    c.timeUpdate(40);
    await c.pause();
    const retry = c.retryProgress();
    const end = c.ended();
    await retry;
    await end;
    expect(api.progress).toHaveBeenLastCalledWith('course', 'a', {
      positionSeconds: 100,
      completed: true,
    });
    expect(c.state.course?.chapters[0]?.completed).toBe(true);
  });
  it('coalesces repeated explicit retry clicks while the retained save is in flight', async () => {
    const { controller: c, api } = setup();
    await c.load();
    await c.play();
    api.progress.mockRejectedValueOnce(new Error('offline'));
    await c.ended();
    const retrySave = deferred<void>();
    api.progress.mockReturnValueOnce(retrySave.promise);
    const one = c.retryProgress();
    const two = c.retryProgress();
    retrySave.resolve();
    await one;
    await two;
    expect(api.progress).toHaveBeenCalledTimes(2);
  });
});
it('uses course IDs, explicit progress PUT and paginated course status at the HTTP boundary', async () => {
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const requests: unknown[] = [];
  const api = createLearningApi(
    createClient({
      baseUrl: '/api/v1',
      session,
      transport: async (r) => {
        requests.push(r);
        return {
          status: 200,
          body: {
            code: 'OK',
            message: '',
            requestId: 'test',
            data: r.url.includes('/learning/courses')
              ? { items: [], page: 2, pageSize: 10, total: 0 }
              : null,
          },
        };
      },
    }),
  );
  await api.courses({ status: 'EXPIRED', page: 2, pageSize: 10 });
  await api.playUrl('course', 'a');
  await api.progress('course', 'a', { positionSeconds: 90, completed: false });
  expect(requests).toMatchObject([
    { url: '/api/v1/learning/courses?status=EXPIRED&page=2&pageSize=10', method: 'GET' },
    { url: '/api/v1/courses/course/chapters/a/play-url', method: 'GET' },
    {
      url: '/api/v1/learning/chapters/a/progress',
      method: 'PUT',
      body: { positionSeconds: 90, completed: false },
    },
  ]);
});

it('plays a non-trial chapter with active LEARN rights and saves a short visit on exit', async () => {
  const { controller: c, api } = setup();
  api.course.mockResolvedValue({ ...structuredClone(course), allowedActions: ['LEARN'] });
  await c.load();
  await c.select('b');
  await c.play();
  expect(c.state.authorization).not.toBeNull();
  c.timeUpdate(3);
  await c.hide();
  expect(api.progress).toHaveBeenCalledWith('course', 'b', {
    positionSeconds: 3,
    completed: false,
  });
  c.dispose();
});
