import type { ApiClient } from '../http';
import type { MembershipView, Page } from '../contracts';
import { ApiError } from '../http';
import type { ProductDetail } from '../content/types';
import type {
  CourseFilters,
  CourseProgress,
  CourseView,
  FileAction,
  FileAuthorization,
  PlayAuthorization,
} from './types';
function segment(id: string) {
  if (!id.trim() || id === '.' || id === '..') throw new Error('无效课程或章节编号');
  return encodeURIComponent(id);
}
export function createLearningApi(client: ApiClient) {
  type LearningCourseWire = {
    courseId: string;
    productId: string;
    title: string;
    status: 'NONE' | 'ACTIVE' | 'PENDING' | 'EXPIRED';
    expiresAt: string | null;
    completedChapters: number;
    totalChapters: number;
    resume?: import('./types').CourseResume | null;
  };
  async function all<T>(path: string): Promise<T[]> {
    const items: T[] = [];
    for (let page = 1; ; page++) {
      const result = await client.request<Page<T>>(path, { query: { page, pageSize: 100 } });
      items.push(...result.items);
      if (result.page * result.pageSize >= result.total || !result.items.length) return items;
    }
  }
  const courses = async (filters: CourseFilters = {}): Promise<Page<CourseView>> => {
    const result = await client.request<Page<LearningCourseWire>>('/learning/courses', {
      query: { status: filters.status, page: filters.page, pageSize: filters.pageSize },
    });
    return {
      ...result,
      items: result.items.map((value) => ({
        ...value,
        id: value.courseId,
        entitlement:
          value.status === 'ACTIVE' ? 'ACTIVE' : value.status === 'EXPIRED' ? 'EXPIRED' : 'NONE',
        allowedActions: value.status === 'ACTIVE' ? ['VIEW_CONTENT'] : [],
        chapters: [],
      })),
    };
  };
  const membership = () => client.request<MembershipView>('/membership/me');
  const progress = (chapter: string, value: CourseProgress) =>
    client.request<void>(`/learning/chapters/${segment(chapter)}/progress`, {
      method: 'PUT',
      body: { positionSeconds: Math.round(value.positionSeconds), completed: value.completed },
    });
  return {
    membership,
    membershipMe: membership,
    membershipPlans: (page = 1) =>
      client.request<
        Page<{
          id: string;
          productId: string;
          durationMonths: number;
          priceFen: number;
          status: string;
        }>
      >('/membership/plans', { authentication: 'none', query: { page, pageSize: 100 } }),
    authorize: (paper: string, file: string, action: FileAction) =>
      client.request<FileAuthorization>(
        `/papers/${segment(paper)}/files/${segment(file)}/authorize`,
        {
          method: 'POST',
          body: { action },
        },
      ),
    course: async (id: string, productId?: string): Promise<CourseView> => {
      let owned: LearningCourseWire | undefined;
      if (!productId) {
        owned = (await all<LearningCourseWire>('/learning/courses')).find(
          (value) => value.courseId === id,
        );
        productId = owned?.productId;
      }
      if (!productId) throw new ApiError('NOT_FOUND', '找不到课程关联商品，请从商品详情重新进入');
      const [product, chapters] = await Promise.all([
        client.request<ProductDetail>(`/products/${segment(productId)}`),
        all<Omit<import('./types').ChapterView, 'completed'>>(`/courses/${segment(id)}/chapters`),
      ]);
      if (product.type !== 'COURSE' || product.course.courseId !== id)
        throw new ApiError('VALIDATION_ERROR', '课程与商品关联不一致');
      if (!owned && product.course.entitlement !== 'NONE')
        owned = (await all<LearningCourseWire>('/learning/courses')).find(
          (value) => value.courseId === id,
        );
      return {
        id,
        productId,
        title: product.title,
        entitlement: product.course.entitlement,
        expiresAt: owned?.expiresAt ?? null,
        allowedActions: product.allowedActions,
        completedChapters: owned?.completedChapters,
        totalChapters: owned?.totalChapters,
        resume: owned?.resume,
        chapters: chapters.map((value) => ({
          ...value,
          completed: value.lastPositionSeconds >= value.durationSeconds,
        })),
      };
    },
    courseChapters: (id: string, page = 1) =>
      client.request<Page<import('./types').ChapterView>>(`/courses/${segment(id)}/chapters`, {
        query: { page, pageSize: 20 },
      }),
    courses,
    learningCourses: courses,
    playUrl: (course: string, chapter: string) =>
      client.request<PlayAuthorization>(
        `/courses/${segment(course)}/chapters/${segment(chapter)}/play-url`,
      ),
    progress: (_course: string, chapter: string, value: CourseProgress) => progress(chapter, value),
    chapterProgress: (chapter: string, value: { positionSeconds: number; completed?: boolean }) =>
      progress(chapter, {
        positionSeconds: value.positionSeconds,
        completed: value.completed ?? false,
      }),
  };
}
export type LearningApi = ReturnType<typeof createLearningApi>;
