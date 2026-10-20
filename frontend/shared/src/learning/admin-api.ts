import { type Page, hasPermission } from '../contracts';
import { ApiError, type ApiClient, type RequestOptions } from '../http';
import { contentBlocksForWrite } from '../content/validation';
import type { AdminProduct } from '../content/admin-types';
import type {
  AdminChapter,
  AdminCourse,
  AdminLearningAsset,
  AdminLearningQuery,
  AdminMembershipPlan,
  AdminMembershipRecord,
  AdminMembershipRecordQuery,
  AdminPaper,
  ChapterOrderWrite,
  ChapterWrite,
  CourseWrite,
  MembershipPlanWrite,
  PaperAttachmentInput,
  PaperWrite,
} from './admin-types';

type CourseWire = Pick<
  AdminCourse,
  'id' | 'version' | 'productId' | 'validityType' | 'validityDays' | 'teacherName'
>;
type ChapterWire = Omit<AdminChapter, 'video'>;
type PaperWire = Pick<
  AdminPaper,
  | 'id'
  | 'version'
  | 'title'
  | 'grade'
  | 'subject'
  | 'status'
  | 'coverAssetId'
  | 'contentBlocks'
  | 'schoolStage'
  | 'stage'
  | 'paperType'
  | 'summary'
> & {
  stage?: string | null;
  schoolStage?: string | null;
  year: number | null;
  attachments: PaperAttachmentInput[];
};
type PlanWire = Omit<AdminMembershipPlan, 'enabled' | 'description'>;
const pagination = (query: { page?: number; pageSize?: number }) => ({
  page: query.page,
  pageSize: query.pageSize,
});

/** A write returned an authoritative identity; replaying a create would duplicate it. */
export class LearningMutationCommittedError extends ApiError {
  constructor(
    public resource: { kind: 'COURSE' | 'CHAPTER' | 'PAPER'; id: string; version: number },
    public completedStages: string[],
    message = '操作已提交，后续读取失败，请重新读取最新版本后再继续',
  ) {
    super('MUTATION_COMMITTED', message);
  }
}

export function createAdminLearningApi(
  client: ApiClient,
  permissions: () => string[],
  generation: () => number = () => 0,
) {
  const segment = (id: string) => {
    if (!id.trim() || id === '.' || id === '..')
      throw new ApiError('VALIDATION_ERROR', '内容编号无效');
    return encodeURIComponent(id);
  };
  async function call<T>(
    path: string,
    options?: RequestOptions,
    permission = 'CONTENT_EDIT',
  ): Promise<T> {
    if (!hasPermission(permissions(), permission))
      throw new ApiError('FORBIDDEN', '没有相应管理权限');
    return client.request<T>(path, options);
  }
  async function all<T>(path: string): Promise<T[]> {
    const items: T[] = [];
    for (let page = 1; ; page++) {
      const result = await call<Page<T>>(path, { query: { page, pageSize: 100 } });
      items.push(...result.items);
      if (result.page * result.pageSize >= result.total || !result.items.length) return items;
    }
  }
  const file = (id: string) => call<AdminLearningAsset>(`/admin/files/${segment(id)}`);
  async function chapter(value: ChapterWire): Promise<AdminChapter> {
    return {
      ...value,
      videoAssetId: value.videoAssetId ?? '',
      video: value.videoAssetId ? await file(value.videoAssetId) : undefined,
    };
  }
  async function chapters(id: string): Promise<AdminChapter[]> {
    return Promise.all(
      (await all<ChapterWire>(`/admin/courses/${segment(id)}/chapters`)).map(chapter),
    );
  }
  async function composeCourse(value: CourseWire, includeChapters = true): Promise<AdminCourse> {
    const [product, items] = await Promise.all([
      call<AdminProduct>(`/admin/products/${segment(value.productId)}`),
      includeChapters ? chapters(value.id) : Promise.resolve([]),
    ]);
    return {
      ...value,
      title: product.title,
      contentBlocks: product.contentBlocks,
      status: product.status,
      productVersion: product.version,
      chapters: items,
    };
  }
  async function paper(value: PaperWire, includeAssets = true): Promise<AdminPaper> {
    return {
      ...value,
      schoolStage: value.schoolStage ?? value.stage ?? '',
      stage: value.stage ?? value.schoolStage ?? '',
      year: value.year == null ? null : String(value.year),
      paperType: value.paperType ?? null,
      summary: value.summary ?? '',
      visibility: value.status === 'PUBLISHED' ? 'PUBLIC' : 'HIDDEN',
      attachmentIds: value.attachments.map((a) => a.assetId),
      attachmentMetadata: value.attachments.map((a) => ({ ...a })),
      attachments: includeAssets
        ? await Promise.all(value.attachments.map((a) => file(a.assetId)))
        : [],
    };
  }
  const plan = (value: PlanWire): AdminMembershipPlan => ({
    ...value,
    enabled: value.status === 'ENABLED',
    description: '',
  });
  async function afterCommit<T>(
    resource: LearningMutationCommittedError['resource'],
    hydrate: () => Promise<T>,
  ): Promise<T> {
    try {
      return await hydrate();
    } catch {
      throw new LearningMutationCommittedError(resource, [resource.kind]);
    }
  }
  return {
    courses: async (query: AdminLearningQuery = {}) => {
      const result = await call<Page<CourseWire>>('/admin/courses', { query: pagination(query) });
      return {
        ...result,
        items: await Promise.all(result.items.map((v) => composeCourse(v, false))),
      };
    },
    course: async (id: string) =>
      composeCourse(await call<CourseWire>(`/admin/courses/${segment(id)}`)),
    saveCourse: async (id: string | null, body: CourseWrite) => {
      const actor = generation();
      const check = () => {
        if (actor !== generation())
          throw new ApiError('FORBIDDEN', '登录状态已变化，请重新读取课程');
      };
      if (id) {
        const current = await call<CourseWire>(`/admin/courses/${segment(id)}`);
        check();
        if (current.version !== body.version || current.productId !== body.productId)
          throw new ApiError('VERSION_CONFLICT', '课程已变化，请重新读取');
      } else {
        const existing = await all<CourseWire>('/admin/courses');
        check();
        if (existing.some((course) => course.productId === body.productId))
          throw new ApiError(
            'PRODUCT_ALREADY_BOUND',
            '该商品已绑定课程，请编辑已有课程或选择其他课程商品',
          );
      }
      const product = await call<AdminProduct>(`/admin/products/${segment(body.productId)}`);
      check();
      if (product.type !== 'COURSE') throw new ApiError('VALIDATION_ERROR', '请选择课程商品');
      if (body.productVersion !== undefined && body.productVersion !== product.version)
        throw new ApiError('VERSION_CONFLICT', '关联商品已变化，请重新读取课程');
      const blocks = contentBlocksForWrite(body.contentBlocks);
      // The unique course/product binding is claimed before changing the public product.
      // These are separate transactions; a later failure must retain the committed course.
      const value = await call<CourseWire>(
        id ? `/admin/courses/${segment(id)}` : '/admin/courses',
        {
          method: id ? 'PATCH' : 'POST',
          body: {
            version: body.version,
            productId: body.productId,
            validityType: body.validityType,
            validityDays: body.validityDays,
            teacherName: body.teacherName ?? null,
          },
        },
      );
      const resource = { kind: 'COURSE' as const, id: value.id, version: value.version };
      const completedStages = ['COURSE'];
      let stage = 'PRODUCT';
      try {
        check();
        if (
          product.title !== body.title ||
          JSON.stringify(product.contentBlocks) !== JSON.stringify(blocks)
        ) {
          await call(`/admin/products/${segment(product.id)}`, {
            method: 'PATCH',
            body: {
              version: product.version,
              type: product.type,
              title: body.title,
              subtitle: product.subtitle,
              coverAssetId: product.coverAssetId,
              priceFen: product.priceFen,
              originalPriceFen: product.originalPriceFen,
              sortNo: product.sortNo,
              categoryIds: product.categoryIds,
              contentBlocks: blocks,
            },
          });
          completedStages.push('PRODUCT');
          check();
        }
        stage = 'READ';
        const composed = await composeCourse(value);
        check();
        return composed;
      } catch {
        throw new LearningMutationCommittedError(
          resource,
          completedStages,
          stage === 'PRODUCT'
            ? '课程信息已提交，商品内容保存结果尚未确认；请重新读取课程和关联商品后再继续'
            : '课程保存已提交，后续读取失败；请重新读取最新版本后再继续',
        );
      }
    },
    chapters,
    saveChapter: async (courseId: string, chapterId: string | null, body: ChapterWrite) => {
      if (
        !Number.isInteger(body.durationSeconds) ||
        body.durationSeconds! < 1 ||
        body.durationSeconds! > 86400 ||
        !Number.isInteger(body.sortNo) ||
        typeof body.enabled !== 'boolean'
      )
        throw new ApiError('VALIDATION_ERROR', '请提供真实章节时长、排序和启用状态');
      const value = await call<ChapterWire>(
        chapterId
          ? `/admin/chapters/${segment(chapterId)}`
          : `/admin/courses/${segment(courseId)}/chapters`,
        {
          method: chapterId ? 'PATCH' : 'POST',
          body: {
            version: body.version,
            title: body.title,
            videoAssetId: body.videoAssetId,
            isTrial: body.isTrial,
            durationSeconds: body.durationSeconds,
            enabled: body.enabled,
            sortNo: body.sortNo,
          },
        },
      );
      return afterCommit({ kind: 'CHAPTER', id: value.id, version: value.version }, () =>
        chapter(value),
      );
    },
    reorderChapters: async (courseId: string, body: ChapterOrderWrite) => {
      const value = await call<CourseWire>(`/admin/courses/${segment(courseId)}/chapter-order`, {
        method: 'PUT',
        body: { version: body.version, chapters: [...body.chapters] },
      });
      return afterCommit({ kind: 'COURSE', id: value.id, version: value.version }, () =>
        composeCourse(value),
      );
    },
    papers: async (query: AdminLearningQuery = {}) => {
      const result = await call<Page<PaperWire>>('/admin/papers', {
        query: {
          ...pagination(query),
          keyword: query.keyword,
          schoolStage: query.schoolStage,
          stage: query.stage,
          grade: query.grade,
          subject: query.subject,
          year: query.year,
          paperType: query.paperType,
          status: query.status,
        },
      });
      return { ...result, items: await Promise.all(result.items.map((v) => paper(v, false))) };
    },
    paper: async (id: string) => paper(await call<PaperWire>(`/admin/papers/${segment(id)}`)),
    savePaper: async (id: string | null, body: PaperWrite) => {
      const attachments = body.attachmentIds.map((assetId, index) => {
        const saved = body.attachmentMetadata?.find((a) => a.assetId === assetId);
        const input = body.attachments?.find((a) =>
          'assetId' in a ? a.assetId === assetId : a.id === assetId,
        );
        return (
          saved ??
          (input && 'assetId' in input
            ? input
            : {
                assetId,
                title: input && 'name' in input ? input.name : `附件_${index + 1}`,
                sortNo: index,
              })
        );
      });
      const value = await call<PaperWire>(id ? `/admin/papers/${segment(id)}` : '/admin/papers', {
        method: id ? 'PATCH' : 'POST',
        body: {
          version: body.version,
          title: body.title,
          stage: body.schoolStage,
          schoolStage: body.schoolStage,
          grade: body.grade,
          subject: body.subject,
          paperType: body.paperType ?? null,
          year: body.year == null || body.year === '' ? null : Number(body.year),
          summary: body.summary ?? null,
          status: body.status,
          coverAssetId: body.coverAssetId ?? null,
          contentBlocks: contentBlocksForWrite(body.contentBlocks),
          attachments,
        },
      });
      return afterCommit({ kind: 'PAPER', id: value.id, version: value.version }, () =>
        paper(value),
      );
    },
    membershipRecords: (query: AdminMembershipRecordQuery = {}) =>
      call<Page<AdminMembershipRecord>>(
        '/admin/membership/records',
        { query: { ...pagination(query), userId: query.userId, status: query.status } },
        'ORDER_OPERATE',
      ),
    membershipPlans: async (query: { page?: number; pageSize?: number } = {}) => {
      const result = await call<Page<PlanWire>>('/admin/membership/plans', {
        query: pagination(query),
      });
      return { ...result, items: result.items.map(plan) };
    },
    saveMembershipPlan: async (id: string, body: MembershipPlanWrite) =>
      plan(
        await call<PlanWire>(`/admin/membership/plans/${segment(id)}`, {
          method: 'PATCH',
          body: {
            version: body.version,
            durationMonths: body.durationMonths,
            status: body.enabled ? 'ENABLED' : 'DISABLED',
          },
        }),
      ),
  };
}
export type AdminLearningApi = ReturnType<typeof createAdminLearningApi>;
