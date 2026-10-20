import { ApiError, type Transport } from '../http';
import { RuntimeURL } from '../runtime-url';
import type { Page } from '../contracts';
import type { CourseInfo } from '../content/types';
import { normalizeContentBlocks } from '../content/validation';
import type { DevCatalogStore } from './catalog';
import type { createDevAdminHandler } from './content-admin';
import type {
  AdminChapter,
  AdminCourse,
  AdminLearningAsset,
  AdminMembershipPlan,
  AdminMembershipRecord,
  AdminPaper,
  ChapterOrderWrite,
  ChapterWrite,
  CourseWrite,
  PaperWrite,
} from '../learning/admin-types';
import {
  isReadyLearningAsset,
  validateChapter,
  validateCourse,
  validateCoursePublish,
  validateMembershipPlan,
  validatePaper,
} from '../learning/admin-validation';

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
  status,
  body: { code, message, data: copy(data), requestId: 'DEV-admin-learning' },
});
const invalid = (message: string, fieldErrors: Record<string, string> = {}): never => {
  throw new ApiError('VALIDATION_ERROR', message, null, fieldErrors, 422);
};
function validate(errors: Record<string, string>) {
  if (Object.keys(errors).length) invalid('请检查表单内容', errors);
}
function version(input: number, current: number) {
  if (!Number.isInteger(input) || input !== current)
    throw new ApiError('VERSION_CONFLICT', '内容已变化，请保留输入并刷新后重试', null, {}, 409);
}
function find<T extends { id: string }>(items: T[], id: string): T {
  const item = items.find((value) => value.id === id);
  if (!item) throw new ApiError('NOT_FOUND', '内容不存在', null, {}, 404);
  return item;
}

/** DEV-only scenario state shared with the established content uploader and product actions. */
export function createDevAdminLearningHandler(
  catalog: DevCatalogStore,
  content: ReturnType<typeof createDevAdminHandler>,
  options: { membershipRecords?: () => AdminMembershipRecord[] } = {},
) {
  let sequence = 1;
  const nextId = (kind: string) => `DEV-${kind}-admin-${sequence++}`;
  const courses: AdminCourse[] = catalog.products.flatMap((product) => {
    if (product.type !== 'COURSE') return [];
    const chapters: AdminChapter[] = [true, false].map((isTrial) => {
      const id = `${product.course.courseId}-${isTrial ? 'trial' : 'paid'}`;
      const video: AdminLearningAsset = {
        id: `${id}-video`,
        version: 1,
        name: 'DEV示例视频.mp4',
        extension: 'MP4',
        mime: 'video/mp4',
        bytes: 1024,
        purpose: 'COURSE_VIDEO',
        status: 'READY',
        scanStatus: 'DEV_FIXTURE_ONLY',
        publicUrl: null,
        durationSeconds: 60,
      };
      content.assets.push(video);
      return {
        id,
        courseId: product.course.courseId,
        version: 1,
        title: isTrial ? 'DEV 公开试看' : 'DEV 已购章节',
        isTrial,
        sortNo: isTrial ? 0 : 1,
        enabled: true,
        videoAssetId: video.id,
        durationSeconds: video.durationSeconds!,
        video,
      };
    });
    return [
      {
        id: product.course.courseId,
        productId: product.id,
        version: 1,
        title: product.title,
        validityType: product.course.validityType,
        validityDays: product.course.validityDays,
        contentBlocks: normalizeContentBlocks(product.contentBlocks),
        status: product.status,
        chapters,
      },
    ];
  });
  const papers: AdminPaper[] = catalog.papers.map((paper) => {
    const attachments: AdminLearningAsset[] = paper.attachments.map(
      ({ id, name, extension, mime, bytes }) => ({
        id,
        name,
        extension,
        mime,
        bytes,
        version: 1,
        purpose: 'PAPER_ATTACHMENT',
        status: 'READY',
        scanStatus: 'DEV_FIXTURE_ONLY',
        publicUrl: null,
      }),
    );
    content.assets.push(...attachments);
    return {
      id: paper.id,
      version: 1,
      title: paper.title,
      schoolStage: paper.schoolStage ?? '',
      grade: paper.grade,
      subject: paper.subject,
      year: paper.year == null ? null : String(paper.year),
      paperType: paper.paperType ?? null,
      summary: paper.summary ?? '',
      contentBlocks: normalizeContentBlocks(paper.contentBlocks),
      attachmentIds: attachments.map((asset) => asset.id),
      attachments,
      visibility: 'PUBLIC',
      status: 'PUBLISHED',
    };
  });
  const product = (id: string) => find(content.products, id);
  function readyAsset(
    id: string,
    purpose: 'COURSE_VIDEO' | 'PAPER_ATTACHMENT',
  ): AdminLearningAsset {
    const asset = content.assets.find((item) => item.id === id);
    if (
      !isReadyLearningAsset(asset, purpose) ||
      (purpose === 'COURSE_VIDEO' &&
        (!Number.isFinite(asset?.durationSeconds) || asset!.durationSeconds! <= 0))
    )
      throw new ApiError('FILE_NOT_READY', '请先上传并绑定已就绪的文件', null, {}, 409);
    return asset!;
  }
  function chapterView(chapter: AdminChapter): AdminChapter {
    const video =
      content.assets.find((asset) => asset.id === chapter.videoAssetId) ?? chapter.video;
    return {
      ...chapter,
      video: video ? copy(video) : undefined,
      durationSeconds: chapter.durationSeconds,
    };
  }
  function courseView(course: AdminCourse): AdminCourse {
    const linked = product(course.productId);
    return {
      ...copy(course),
      title: linked.title,
      contentBlocks: copy(linked.contentBlocks),
      status: linked.status,
      chapters: course.chapters.map(chapterView),
    };
  }
  function courseInfo(productId: string): CourseInfo | null {
    const course = courses.find((item) => item.productId === productId);
    return course
      ? {
          courseId: course.id,
          validityType: course.validityType,
          validityDays: course.validityDays,
          chaptersSummary: course.chapters.length,
          entitlement: 'NONE',
        }
      : null;
  }
  content.setLearningHooks({
    courseInfo,
    productChanged(linked) {
      const course = courses.find((item) => item.productId === linked.id);
      if (
        course &&
        (course.title !== linked.title ||
          JSON.stringify(course.contentBlocks) !== JSON.stringify(linked.contentBlocks))
      ) {
        course.title = linked.title;
        course.contentBlocks = copy(linked.contentBlocks);
      }
    },
    validatePublish(productId) {
      const course = courses.find((item) => item.productId === productId);
      if (!course) throw new ApiError('INVALID_STATE', '请先配置课程元数据', null, {}, 409);
      const errors = validateCoursePublish(courseView(course));
      if (Object.keys(errors).length)
        throw new ApiError('FILE_NOT_READY', '请完善课程并绑定已就绪视频', null, errors, 409);
    },
  });
  function syncCourse(course: AdminCourse, updateProduct = false) {
    const current = product(course.productId);
    if (updateProduct) {
      const value = {
        ...current,
        title: course.title,
        contentBlocks: copy(course.contentBlocks),
        version: current.version + 1,
      };
      content.syncProduct(value);
      content.products.splice(content.products.indexOf(current), 1, value);
    } else content.syncProduct(current);
  }
  function commitCourse(value: AdminCourse, previous: AdminCourse | null, updateProduct = false) {
    const index = previous ? courses.indexOf(previous) : courses.length;
    courses.splice(index, previous ? 1 : 0, value);
    try {
      syncCourse(value, updateProduct);
    } catch (error) {
      courses.splice(index, 1, ...(previous ? [previous] : []));
      throw error;
    }
  }
  function syncPaper(paper: AdminPaper) {
    const index = catalog.papers.findIndex((value) => value.id === paper.id);
    if (index >= 0) catalog.papers.splice(index, 1);
    if (paper.status === 'PUBLISHED' && paper.visibility === 'PUBLIC')
      catalog.papers.push({
        id: paper.id,
        title: paper.title,
        schoolStage: paper.schoolStage,
        grade: paper.grade,
        subject: paper.subject,
        year: paper.year ?? '',
        summary: paper.summary,
        contentBlocks: content.blocks(paper.contentBlocks),
        attachments: paper.attachmentIds.map((id) => {
          const { name, extension, mime, bytes } = find(content.assets, id);
          return { id, name, extension, mime, bytes, canPreview: false, canDownload: false };
        }),
      });
  }
  function plans(): AdminMembershipPlan[] {
    return content.products.flatMap((p) =>
      p.type === 'MEMBERSHIP' && p.membership
        ? [
            {
              id: p.id,
              productId: p.id,
              version: planStates.get(p.id)?.version ?? 1,
              durationMonths: p.membership.durationMonths,
              priceFen: p.priceFen,
              enabled: (planStates.get(p.id)?.status ?? 'ENABLED') === 'ENABLED',
              description: '',
              level: 'STANDARD',
              status: planStates.get(p.id)?.status ?? 'ENABLED',
            },
          ]
        : [],
    );
  }
  const planStates = new Map<string, { version: number; status: 'ENABLED' | 'DISABLED' }>();
  const courseWire = (value: AdminCourse) => ({
    id: value.id,
    version: value.version,
    productId: value.productId,
    validityType: value.validityType,
    validityDays: value.validityDays,
    teacherName: value.teacherName ?? null,
  });
  const chapterWire = (value: AdminChapter) => ({
    id: value.id,
    courseId: value.courseId,
    version: value.version,
    title: value.title,
    sortNo: value.sortNo,
    videoAssetId: value.videoAssetId,
    durationSeconds: value.durationSeconds,
    isTrial: value.isTrial,
    enabled: value.enabled,
  });
  const paperWire = (value: AdminPaper) => ({
    id: value.id,
    version: value.version,
    title: value.title,
    stage: value.schoolStage,
    grade: value.grade,
    subject: value.subject,
    year: value.year == null ? null : Number(value.year),
    status: value.status,
    coverAssetId: value.coverAssetId ?? null,
    contentBlocks: value.contentBlocks,
    attachments:
      value.attachmentMetadata ??
      value.attachments.map((asset, index) => ({
        assetId: asset.id,
        title: asset.name,
        sortNo: index,
      })),
  });
  const transport: Transport = async (request) => {
    const url = new RuntimeURL(request.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const courseRoute = path.match(
      /^\/admin\/courses(?:\/([^/]+)(?:\/(chapters|chapter-order))?)?$/,
    );
    const chapterRoute = path.match(/^\/admin\/chapters\/([^/]+)$/);
    const paperRoute = path.match(/^\/admin\/papers(?:\/([^/]+))?$/);
    const planRoute = path.match(/^\/admin\/membership\/plans(?:\/([^/]+))?$/);
    const recordsRoute = path === '/admin/membership/records';
    if (!courseRoute && !chapterRoute && !paperRoute && !planRoute && !recordsRoute)
      return reply(null, 404, 'NOT_FOUND', '此DEV课程管理操作未接入');
    try {
      const actor = request.headers.Authorization?.replace(/^Bearer /, '') ?? '';
      if (actor !== 'DEV-admin-front-end-only')
        throw new ApiError('FORBIDDEN', '没有内容管理权限', null, {}, 403);
      const page = <T extends object>(items: T[]): Page<T> => {
        const page = Number(url.searchParams.get('page') ?? 1),
          pageSize = Number(url.searchParams.get('pageSize') ?? 20);
        if (
          !Number.isInteger(page) ||
          page < 1 ||
          !Number.isInteger(pageSize) ||
          pageSize < 1 ||
          pageSize > 100
        )
          invalid('分页参数无效');
        const filtered = items;
        return {
          items: filtered.slice((page - 1) * pageSize, page * pageSize),
          page,
          pageSize,
          total: filtered.length,
        };
      };
      if (recordsRoute && request.method === 'GET') {
        const userId = url.searchParams.get('userId');
        const status = url.searchParams.get('status');
        return reply(
          page(
            (options.membershipRecords?.() ?? []).filter(
              (item) => (!userId || item.userId === userId) && (!status || item.status === status),
            ),
          ),
        );
      }
      if (courseRoute) {
        const id = courseRoute[1] ? decodeURIComponent(courseRoute[1]) : null;
        const action = courseRoute[2];
        if (request.method === 'GET') {
          if (!id) return reply(page(courses.map(courseWire)));
          const course = courseView(find(courses, id));
          if (!action) return reply(courseWire(course));
          if (action === 'chapters') return reply(page(course.chapters.map(chapterWire)));
        }
        if ((!id && request.method === 'POST') || (id && !action && request.method === 'PATCH')) {
          const input = request.body as CourseWrite;
          const previous = id ? find(courses, id) : null;
          version(input.version, previous?.version ?? 0);
          const linked = product(input.productId);
          validate(
            validateCourse({ ...input, title: linked.title, contentBlocks: linked.contentBlocks }),
          );
          if (
            linked.type !== 'COURSE' ||
            (previous && previous.productId !== input.productId) ||
            (!previous && courses.some((item) => item.productId === input.productId))
          )
            invalid('课程必须绑定尚未配置的课程商品，已有绑定不可更改');
          const blocks = normalizeContentBlocks(linked.contentBlocks);
          content.blocks(blocks);
          const value: AdminCourse = {
            id: previous?.id ?? nextId('course'),
            productId: input.productId,
            version: (previous?.version ?? 0) + 1,
            title: linked.title,
            teacherName: input.teacherName,
            validityType: input.validityType,
            validityDays: input.validityDays,
            contentBlocks: blocks,
            status: linked.status,
            chapters: previous?.chapters ?? [],
          };
          commitCourse(value, previous);
          return reply(courseWire(value));
        }
        if (id && action === 'chapters' && request.method === 'POST') {
          const course = find(courses, id);
          const input = request.body as ChapterWrite;
          version(input.version, 0);
          validate(validateChapter(input));
          const video = readyAsset(input.videoAssetId, 'COURSE_VIDEO');
          const value: AdminChapter = {
            id: nextId('chapter'),
            courseId: id,
            version: 1,
            title: input.title.trim(),
            videoAssetId: video.id,
            isTrial: input.isTrial,
            durationSeconds: input.durationSeconds!,
            sortNo: input.sortNo,
            enabled: input.enabled,
            video: copy(video),
          };
          commitCourse(
            { ...course, version: course.version + 1, chapters: [...course.chapters, value] },
            course,
          );
          return reply(chapterWire(value));
        }
        if (id && action === 'chapter-order' && request.method === 'PUT') {
          const course = find(courses, id);
          const input = request.body as ChapterOrderWrite;
          version(input.version, course.version);
          if (
            !Array.isArray(input.chapters) ||
            input.chapters.length !== course.chapters.length ||
            new Set(input.chapters).size !== input.chapters.length ||
            input.chapters.some((value) => !course.chapters.some((c) => c.id === value))
          )
            invalid('章节排序须包含当前课程全部章节且不能重复');
          course.chapters = input.chapters.map((value) => find(course.chapters, value));
          course.chapters = course.chapters.map((chapter, index) => ({
            ...chapter,
            sortNo: index,
            version: chapter.version + (chapter.sortNo === index ? 0 : 1),
          }));
          course.version++;
          return reply(courseWire(course));
        }
      }
      if (chapterRoute && request.method === 'PATCH') {
        const id = decodeURIComponent(chapterRoute[1]!);
        const course = courses.find((item) => item.chapters.some((chapter) => chapter.id === id));
        if (!course) throw new ApiError('NOT_FOUND', '章节不存在', null, {}, 404);
        const previous = find(course.chapters, id);
        const input = request.body as ChapterWrite;
        version(input.version, previous.version);
        validate(validateChapter(input));
        const video = readyAsset(input.videoAssetId, 'COURSE_VIDEO');
        const value = {
          ...previous,
          version: previous.version + 1,
          title: input.title.trim(),
          videoAssetId: video.id,
          isTrial: input.isTrial,
          durationSeconds: input.durationSeconds!,
          sortNo: input.sortNo,
          enabled: input.enabled,
          video: copy(video),
        };
        course.chapters.splice(course.chapters.indexOf(previous), 1, value);
        course.version++;
        return reply(chapterWire(value));
      }
      if (paperRoute) {
        const id = paperRoute[1] ? decodeURIComponent(paperRoute[1]) : null;
        if (request.method === 'GET')
          return reply(id ? paperWire(find(papers, id)) : page(papers.map(paperWire)));
        if ((!id && request.method === 'POST') || (id && request.method === 'PATCH')) {
          const previous = id ? find(papers, id) : null;
          const wire = request.body as {
            stage: string;
            year: number | null;
            attachments: Array<{ assetId: string; title: string; sortNo: number }>;
          } & PaperWrite;
          const input: PaperWrite = {
            ...wire,
            schoolStage: wire.stage,
            year: wire.year == null ? null : String(wire.year),
            attachmentIds: wire.attachments.map((a) => a.assetId),
            attachmentMetadata: wire.attachments,
            summary: '',
            visibility: wire.status === 'PUBLISHED' ? 'PUBLIC' : 'HIDDEN',
          };
          version(input.version, previous?.version ?? 0);
          validate(validatePaper(input));
          const attachments = input.attachmentIds.map((value) =>
            readyAsset(value, 'PAPER_ATTACHMENT'),
          );
          const blocks = normalizeContentBlocks(input.contentBlocks);
          content.blocks(blocks);
          const value: AdminPaper = {
            id: previous?.id ?? nextId('paper'),
            version: (previous?.version ?? 0) + 1,
            title: input.title.trim(),
            schoolStage: input.schoolStage.trim(),
            grade: input.grade.trim(),
            subject: input.subject.trim(),
            year: input.year || null,
            summary: input.summary?.trim() ?? '',
            contentBlocks: blocks,
            attachmentIds: [...input.attachmentIds],
            attachmentMetadata: input.attachmentMetadata,
            coverAssetId: input.coverAssetId,
            attachments: copy(attachments),
            visibility: input.visibility,
            status: input.status,
          };
          if (previous) papers.splice(papers.indexOf(previous), 1, value);
          else papers.push(value);
          syncPaper(value);
          return reply(paperWire(value));
        }
      }
      if (planRoute) {
        const id = planRoute[1] ? decodeURIComponent(planRoute[1]) : null;
        if (!id && request.method === 'GET') return reply(page(plans()));
        if (id && request.method === 'PATCH') {
          const previous = find(plans(), id);
          const input = request.body as {
            version: number;
            durationMonths: 6 | 12;
            status: 'ENABLED' | 'DISABLED';
          };
          version(input.version, previous.version);
          validate(
            validateMembershipPlan({
              ...previous,
              durationMonths: input.durationMonths,
              enabled: input.status === 'ENABLED',
            }),
          );
          if (!['ENABLED', 'DISABLED'].includes(input.status)) invalid('计划状态无效');
          const current = product(previous.productId);
          if (
            current.status === 'ON_SALE' &&
            (input.durationMonths !== previous.durationMonths || input.status !== 'ENABLED')
          )
            invalid('修改周期或禁用前请先下架商品');
          if (
            input.status === 'ENABLED' &&
            plans().some(
              (plan) =>
                plan.id !== id && plan.enabled && plan.durationMonths === input.durationMonths,
            )
          )
            invalid('每个周期最多启用一个方案');
          planStates.set(id, { version: previous.version + 1, status: input.status });
          const value = { ...current, membership: { durationMonths: input.durationMonths } };
          content.syncProduct(value);
          content.products.splice(content.products.indexOf(current), 1, value);
          return reply(find(plans(), id));
        }
      }
      return reply(null, 404, 'NOT_FOUND', '此DEV课程管理操作未接入');
    } catch (error) {
      return error instanceof ApiError
        ? reply({ fieldErrors: error.fieldErrors }, error.status || 422, error.code, error.message)
        : reply(null, 422, 'VALIDATION_ERROR', '提交内容格式不正确');
    }
  };
  return {
    transport,
    chapters: (courseId: string) =>
      courses.find((course) => course.id === courseId)?.chapters.map(chapterView) ?? [],
  };
}
