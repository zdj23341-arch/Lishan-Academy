import type { Session } from '../session';
import { ApiError } from '../http';
import type { LearningApi } from './api';
import type { ChapterView, CourseProgress, CourseView, PlayAuthorization } from './types';
export function hasCourseLearningAccess(course: CourseView): boolean {
  return (
    course.entitlement === 'ACTIVE' &&
    (course.allowedActions.includes('LEARN') || course.allowedActions.includes('VIEW_CONTENT'))
  );
}
/** canPlay includes both viewing rights and content readiness; false alone is not a permission denial. */
export function chapterContentUnavailable(course: CourseView, chapter: ChapterView): boolean {
  return !chapter.canPlay && (chapter.isTrial || hasCourseLearningAccess(course));
}
export interface CourseState {
  course: CourseView | null;
  selectedChapterId: string | null;
  authorization: PlayAuthorization | null;
  loading: boolean;
  authorizing: boolean;
  error: string | null;
  progressError: string | null;
  positionSeconds: number;
}
export interface CourseControllerOptions {
  api: Pick<LearningApi, 'course' | 'courses' | 'playUrl' | 'progress'>;
  session: Session;
  courseId: string;
  productId?: string;
  now?: () => number;
  setTimer?: (callback: () => void, milliseconds: number) => ReturnType<typeof setTimeout>;
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void;
}
export function createCourseController(options: CourseControllerOptions) {
  const state: CourseState = {
    course: null,
    selectedChapterId: null,
    authorization: null,
    loading: false,
    authorizing: false,
    error: null,
    progressError: null,
    positionSeconds: 0,
  };
  const now = options.now ?? Date.now;
  const setTimer = options.setTimer ?? setTimeout;
  const clearTimer = options.clearTimer ?? clearTimeout;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;
  let loadGeneration = 0;
  let disposed = false;
  let started = false;
  let paused = false;
  let renewing = false;
  let ticketNow = 0;
  let actorNow = 0;
  let lastSaveAt = 0;
  let retrying: Promise<void> | null = null;
  const queues = new Map<string, Promise<void>>();
  const submitted = new Map<string, CourseProgress>();
  const failed = new Map<string, CourseProgress>();
  const chapter = () => state.course?.chapters.find((item) => item.id === state.selectedChapterId);
  function bound(position: number, duration: number) {
    return Math.max(
      0,
      Math.min(
        Number.isFinite(duration) ? Math.max(0, duration) : 0,
        Number.isFinite(position) ? position : 0,
      ),
    );
  }
  function clearMedia() {
    generation++;
    if (timer !== undefined) clearTimer(timer);
    timer = undefined;
    state.authorization = null;
    state.authorizing = false;
  }
  function actorCurrent(actor: number) {
    options.session.get(); // Expired sessions notify synchronously.
    return !disposed && actor === options.session.getGeneration();
  }
  function current(ticket: number, actor: number) {
    return actorCurrent(actor) && ticket === generation;
  }
  // Playback capabilities are short-lived. Renew before expiry so a long chapter keeps playing
  // instead of unmounting the player every few minutes.
  const RENEW_LEAD_MS = 30_000;
  function scheduleExpiry(ticket: number, actor: number, delay: number) {
    if (timer !== undefined) clearTimer(timer);
    timer = setTimer(
      () => {
        if (!current(ticket, actor)) return;
        clearMedia();
        started = false;
      },
      Math.min(Math.max(0, delay), 2147483647),
    );
  }
  function scheduleRenewal(ticket: number, actor: number, remaining: number) {
    if (timer !== undefined) clearTimer(timer);
    ticketNow = ticket;
    actorNow = actor;
    const lead = Math.min(RENEW_LEAD_MS, Math.floor(remaining / 2));
    timer = setTimer(
      () => {
        if (!current(ticket, actor)) return;
        if (paused) {
          scheduleExpiry(ticket, actor, lead);
          return;
        }
        void renew(ticket, actor);
      },
      Math.min(Math.max(0, remaining - lead), 2147483647),
    );
  }
  async function renew(ticket: number, actor: number) {
    if (renewing || !current(ticket, actor) || !started || paused) return;
    const selected = chapter();
    if (!selected) return;
    renewing = true;
    try {
      const next = await options.api.playUrl(options.courseId, selected.id);
      if (!current(ticket, actor) || !started || paused) return;
      const remaining = Date.parse(next.expiresAt) - now();
      if (!Number.isFinite(remaining) || remaining <= 0 || !/^https?:\/\//i.test(next.url))
        throw new Error();
      state.positionSeconds = bound(state.positionSeconds, selected.durationSeconds);
      state.authorization = { ...next, resumeSeconds: state.positionSeconds };
      scheduleRenewal(ticket, actor, remaining);
    } catch (error) {
      if (current(ticket, actor) && started) {
        clearMedia();
        started = false;
        state.error =
          error instanceof ApiError && error.code === 'ENTITLEMENT_EXPIRED'
            ? '课程权益已过期，请刷新课程信息后重新购买'
            : '播放授权已过期，请重新播放';
      }
    } finally {
      renewing = false;
    }
  }
  const unsubscribe = options.session.subscribe(() => {
    loadGeneration++;
    clearMedia();
    started = false;
    paused = false;
    queues.clear();
    submitted.clear();
    failed.clear();
    retrying = null;
    Object.assign(state, {
      course: null,
      selectedChapterId: null,
      loading: false,
      error: null,
      progressError: null,
      positionSeconds: 0,
    });
  });
  function refreshProgressError() {
    state.progressError = failed.size ? '学习进度保存失败，请重试' : null;
  }
  function save(id: string, body: CourseProgress, retry = false): Promise<void> {
    const user = options.session.get();
    if (disposed || !user) return Promise.resolve();
    const actor = options.session.getGeneration();
    const previous = submitted.get(id);
    if (
      !retry &&
      previous?.positionSeconds === body.positionSeconds &&
      previous.completed === body.completed
    )
      return queues.get(id) ?? Promise.resolve();
    const snapshot = { ...body };
    submitted.set(id, snapshot);
    const run = async () => {
      if (!actorCurrent(actor)) return;
      try {
        await options.api.progress(options.courseId, id, snapshot);
        if (!actorCurrent(actor)) return;
        failed.delete(id);
        refreshProgressError();
        const saved = state.course?.chapters.find((item) => item.id === id);
        if (saved) {
          saved.lastPositionSeconds = snapshot.positionSeconds;
          saved.completed = snapshot.completed;
        }
      } catch {
        if (actorCurrent(actor)) {
          failed.set(id, snapshot);
          refreshProgressError();
        }
      }
    };
    const prior = queues.get(id);
    const task = prior ? prior.then(run) : run();
    queues.set(id, task);
    void task.finally(() => {
      if (queues.get(id) === task) queues.delete(id);
    });
    return task;
  }
  function flush(completed = false) {
    const selected = chapter();
    if (!started || !selected) return Promise.resolve();
    lastSaveAt = now();
    return save(selected.id, {
      positionSeconds: completed ? selected.durationSeconds : state.positionSeconds,
      completed: completed || selected.completed || submitted.get(selected.id)?.completed === true,
    });
  }
  async function load() {
    if (disposed) return;
    clearMedia();
    started = false;
    paused = false;
    options.session.get();
    const actor = options.session.getGeneration();
    const ticket = ++loadGeneration;
    const isCurrentLoad = () => actorCurrent(actor) && ticket === loadGeneration;
    state.loading = true;
    state.error = null;
    try {
      if (!options.courseId.trim()) throw new Error();
      const value = await options.api.course(options.courseId, options.productId);
      if (!isCurrentLoad()) return;
      state.course = value;
      const selected =
        value.chapters.find((item) => item.id === state.selectedChapterId) ?? value.chapters[0];
      state.selectedChapterId = selected?.id ?? null;
      state.positionSeconds = selected
        ? bound(selected.lastPositionSeconds, selected.durationSeconds)
        : 0;
    } catch {
      if (isCurrentLoad()) state.error = '课程加载失败，请重试';
    } finally {
      if (isCurrentLoad()) state.loading = false;
    }
  }
  async function select(id: string) {
    if (disposed || id === state.selectedChapterId) return;
    const selected = state.course?.chapters.find((item) => item.id === id);
    if (!selected) {
      state.error = '章节不存在';
      return;
    }
    const pending = flush();
    clearMedia();
    started = false;
    paused = false;
    state.selectedChapterId = id;
    state.positionSeconds = bound(selected.lastPositionSeconds, selected.durationSeconds);
    state.error = null;
    await pending;
  }
  async function play() {
    options.session.get();
    if (disposed || state.loading || state.authorizing) return;
    const selected = chapter();
    const course = state.course;
    if (!selected || !course) return;
    if (
      !selected.canPlay ||
      (!selected.isTrial && (!options.session.get() || !hasCourseLearningAccess(course)))
    ) {
      clearMedia();
      started = false;
      state.error =
        chapterContentUnavailable(course, selected) && (selected.isTrial || options.session.get())
          ? '当前章节内容暂不可播放，请稍后重试或联系客服'
          : '当前章节暂无播放权限';
      return;
    }
    clearMedia();
    started = false;
    paused = false;
    state.error = null;
    state.authorizing = true;
    const ticket = generation;
    const actor = options.session.getGeneration();
    try {
      const authorization = await options.api.playUrl(options.courseId, selected.id);
      if (!current(ticket, actor)) return;
      const remaining = Date.parse(authorization.expiresAt) - now();
      if (!Number.isFinite(remaining) || remaining <= 0 || !/^https?:\/\//i.test(authorization.url))
        throw new Error();
      state.positionSeconds = bound(authorization.resumeSeconds, selected.durationSeconds);
      state.authorization = { ...authorization, resumeSeconds: state.positionSeconds };
      started = true;
      paused = false;
      lastSaveAt = now();
      scheduleRenewal(ticket, actor, remaining);
    } catch (error) {
      if (current(ticket, actor)) {
        clearMedia();
        const messages: Record<string, string> = {
          FILE_NOT_READY: '当前章节内容暂不可播放，请稍后重试或联系客服',
          UNAUTHORIZED: '请先登录后再播放课程',
          FORBIDDEN: '当前章节暂无播放权限，请刷新课程权益后重试',
          ENTITLEMENT_EXPIRED: '课程权益已过期，请刷新课程信息后重新购买',
        };
        state.error =
          error instanceof ApiError && Object.hasOwn(messages, error.code)
            ? messages[error.code]!
            : '播放授权失败，请重试';
      }
    } finally {
      if (current(ticket, actor)) state.authorizing = false;
    }
  }
  function timeUpdate(position: number) {
    const selected = chapter();
    if (disposed || !started || !selected || !Number.isFinite(position)) return;
    options.session.get();
    if (!started) return;
    if (state.authorization && Date.parse(state.authorization.expiresAt) <= now()) {
      clearMedia();
      started = false;
      return;
    }
    state.positionSeconds = bound(position, selected.durationSeconds);
    if (now() - lastSaveAt >= 15000) void flush();
  }
  function ended() {
    const pending = flush(true);
    clearMedia();
    started = false;
    paused = false;
    return pending;
  }
  function hide() {
    const pending = flush();
    loadGeneration++;
    clearMedia();
    started = false;
    paused = false;
    state.loading = false;
    return pending;
  }
  return {
    state,
    load,
    select,
    play,
    timeUpdate,
    // Native play events also resume after a user pause; renewal must follow that state.
    resume() {
      if (disposed || !started) return;
      paused = false;
      if (!state.authorization) return;
      const remaining = Date.parse(state.authorization.expiresAt) - now();
      if (Number.isFinite(remaining) && remaining > 0)
        scheduleRenewal(ticketNow, actorNow, remaining);
    },
    pause: () => {
      paused = true;
      return flush();
    },
    ended,
    hide,
    retryProgress(): Promise<void> {
      if (disposed) return Promise.resolve();
      if (retrying) return retrying;
      options.session.get();
      const actor = options.session.getGeneration();
      const task = (async () => {
        while (actorCurrent(actor) && queues.size) await Promise.all([...queues.values()]);
        if (!actorCurrent(actor)) return;
        await Promise.all([...failed].map(([id, body]) => save(id, body, true)));
      })();
      retrying = task;
      void task.finally(() => {
        if (retrying === task) retrying = null;
      });
      return task;
    },
    dispose() {
      loadGeneration++;
      clearMedia();
      started = false;
      paused = false;
      disposed = true;
      state.loading = false;
      queues.clear();
      failed.clear();
      submitted.clear();
      unsubscribe();
    },
  };
}
export type CourseController = ReturnType<typeof createCourseController>;
