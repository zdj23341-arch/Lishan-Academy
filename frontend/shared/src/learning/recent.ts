import type { Session, SessionStorage } from '../session';
import { ApiError } from '../http';
import type { LearningApi } from './api';
import type { ChapterView, CourseView, PlayAuthorization } from './types';
import { hasCourseLearningAccess } from './course';

interface LocalRecord {
  courseId: string;
  productId: string;
  chapterId: string;
  positionSeconds: number;
  updatedAt: number;
}
export interface RecentLearningView {
  course: CourseView;
  chapter: ChapterView;
  positionSeconds: number;
  progressPercent: number;
  updatedAt: number;
  source: 'LOCAL' | 'SERVER';
}
export interface PlaybackRecord {
  course: CourseView;
  chapterId: string;
  positionSeconds: number;
  authorization: PlayAuthorization;
  generation: number;
}

/** Local recency is a navigation hint; server data and play-url remain authoritative. */
export function createRecentLearning(options: {
  session: Session;
  storage: SessionStorage;
  namespace: string;
  api: Pick<LearningApi, 'course'> & Partial<Pick<LearningApi, 'courses'>>;
  now?: () => number;
}) {
  const now = options.now ?? Date.now;
  let sequence = 0;
  function key() {
    const user = options.session.get()?.user;
    return user ? `jxsc.learning.recent.v1:${JSON.stringify([options.namespace, user.id])}` : null;
  }
  function read(storageKey: string): LocalRecord | null {
    try {
      const value: unknown = JSON.parse(options.storage.get(storageKey) ?? 'null');
      if (!value || typeof value !== 'object') return null;
      const record = value as LocalRecord;
      if (
        ![record.courseId, record.productId, record.chapterId].every(
          (id) => typeof id === 'string' && id.trim() && id !== '.' && id !== '..',
        ) ||
        !Number.isFinite(record.positionSeconds) ||
        record.positionSeconds < 0 ||
        !Number.isFinite(record.updatedAt) ||
        record.updatedAt > now()
      )
        return null;
      return record;
    } catch {
      return null;
    }
  }
  function remove(storageKey: string) {
    try {
      options.storage.remove(storageKey);
    } catch {
      /* Optional device history. */
    }
  }
  function record(value: PlaybackRecord) {
    const storageKey = key();
    if (!storageKey || value.generation !== options.session.getGeneration()) return;
    const chapter = value.course.chapters.find((item) => item.id === value.chapterId);
    if (
      !chapter?.canPlay ||
      !Number.isFinite(value.positionSeconds) ||
      value.positionSeconds < 0 ||
      !Number.isFinite(chapter.durationSeconds) ||
      chapter.durationSeconds <= 0 ||
      !Number.isFinite(Date.parse(value.authorization.expiresAt)) ||
      Date.parse(value.authorization.expiresAt) <= now()
    )
      return;
    const next: LocalRecord = {
      courseId: value.course.id,
      productId: value.course.productId,
      chapterId: chapter.id,
      positionSeconds: Math.min(value.positionSeconds, chapter.durationSeconds),
      updatedAt: now(),
    };
    sequence++;
    try {
      options.storage.set(storageKey, JSON.stringify(next));
    } catch {
      /* Playback must continue. */
    }
  }
  async function load(): Promise<RecentLearningView | null> {
    const ticket = ++sequence;
    const storageKey = key();
    if (!storageKey) return null;
    let saved = read(storageKey);
    let source: RecentLearningView['source'] = 'LOCAL';
    const actor = options.session.getGeneration();
    const active = () =>
      key() === storageKey && actor === options.session.getGeneration() && ticket === sequence;
    let course: CourseView;
    try {
      if (options.api.courses) {
        for (let page = 1; ; page++) {
          const result = await options.api.courses({ page, pageSize: 100 });
          if (!active()) return null;
          for (const item of result.items) {
            const resume = item.resume;
            const updatedAt = Date.parse(resume?.lastLearnedAt ?? '');
            if (
              !resume ||
              item.entitlement !== 'ACTIVE' ||
              !resume.chapterId?.trim() ||
              !Number.isFinite(updatedAt) ||
              updatedAt > now() ||
              !Number.isFinite(resume.positionSeconds) ||
              resume.positionSeconds < 0 ||
              (saved && updatedAt <= saved.updatedAt)
            )
              continue;
            saved = {
              courseId: item.id,
              productId: item.productId,
              chapterId: resume.chapterId,
              positionSeconds: resume.positionSeconds,
              updatedAt,
            };
            source = 'SERVER';
          }
          if (!result.items.length || result.page * result.pageSize >= result.total) break;
        }
      }
      if (!saved) return null;
      course = await options.api.course(saved.courseId, saved.productId);
    } catch (cause) {
      if (!active()) return null;
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
        remove(storageKey);
        return null;
      }
      throw cause;
    }
    if (!active()) return null;
    const chapter = course.chapters.find((item) => item.id === saved.chapterId);
    if (
      course.id !== saved.courseId ||
      course.productId !== saved.productId ||
      !chapter?.canPlay ||
      !hasCourseLearningAccess(course) ||
      (course.expiresAt !== null &&
        (!Number.isFinite(Date.parse(course.expiresAt)) || Date.parse(course.expiresAt) <= now()))
    ) {
      remove(storageKey);
      return null;
    }
    const total = course.totalChapters ?? course.chapters.length;
    const completed =
      course.completedChapters ?? course.chapters.filter((item) => item.completed).length;
    return {
      course,
      chapter,
      positionSeconds: Math.max(0, Math.min(saved.positionSeconds, chapter.durationSeconds)),
      progressPercent:
        Number.isFinite(total) && total > 0 && Number.isFinite(completed)
          ? Math.max(0, Math.min(100, Math.round((completed / total) * 100)))
          : 0,
      updatedAt: saved.updatedAt,
      source,
    };
  }
  return {
    record,
    load,
    invalidate: () => {
      sequence++;
    },
  };
}
