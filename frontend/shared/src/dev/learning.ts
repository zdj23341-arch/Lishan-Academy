import type { CourseProgress, CourseView } from '../learning/types';
import type { Transport } from '../http';
import type { DevCatalogStore } from './catalog';
import type { DevDigitalContent } from './digital-content';
import type { AdminChapter } from '../learning/admin-types';
import { isReadyLearningAsset } from '../learning/admin-validation';
import { RuntimeURL } from '../runtime-url';

export function createDevLearningHandler(
  catalog: DevCatalogStore,
  options: {
    now?: () => number;
    mediaUrl?: string;
    chapters?: (courseId: string) => AdminChapter[];
  } = {},
) {
  const now = options.now ?? Date.now;
  const grants = new Map<string, { expiresAt: string | null; revoked: boolean }>();
  const purchases = new Map<
    string,
    { actor: string; courseId: string; expiresAt: string | null; revoked: boolean }
  >();
  const progress = new Map<string, CourseProgress>();
  const scope = (actor: string, courseId: string) => JSON.stringify([actor, courseId]);
  const progressKey = (actor: string, courseId: string, chapterId: string) =>
    JSON.stringify([actor, courseId, chapterId]);
  function grantCourse(actor: string, courseId: string, expiresAt: string | null) {
    if (!actor || !courseId || (expiresAt !== null && !Number.isFinite(Date.parse(expiresAt))))
      throw new Error('DEV课程权益参数无效');
    grants.set(scope(actor, courseId), { expiresAt, revoked: false });
  }
  function revokeCourse(actor: string, courseId: string) {
    const grant = grants.get(scope(actor, courseId));
    if (grant) grant.revoked = true;
  }
  function courseView(courseId: string, actor: string): CourseView | null {
    const product = catalog.products.find(
      (p) => p.type === 'COURSE' && p.course.courseId === courseId,
    );
    if (product?.type !== 'COURSE') return null;
    const manualGrant = grants.get(scope(actor, courseId));
    const sources = [
      ...(manualGrant ? [manualGrant] : []),
      ...[...purchases.values()].filter(
        (item) => item.actor === actor && item.courseId === courseId,
      ),
    ];
    const active = sources.filter(
      (item) => !item.revoked && (item.expiresAt === null || Date.parse(item.expiresAt) > now()),
    );
    const grant = (active.length ? active : sources).sort(
      (a, b) =>
        (b.expiresAt === null ? Infinity : Date.parse(b.expiresAt)) -
        (a.expiresAt === null ? Infinity : Date.parse(a.expiresAt)),
    )[0];
    const entitlement = !grant
      ? 'NONE'
      : grant.revoked || (grant.expiresAt !== null && Date.parse(grant.expiresAt) <= now())
        ? 'EXPIRED'
        : 'ACTIVE';
    return {
      id: courseId,
      productId: product.id,
      title: product.title,
      entitlement,
      expiresAt: grant?.expiresAt ?? null,
      allowedActions: [
        ...(product.status === 'ON_SALE' && entitlement !== 'ACTIVE' ? ['BUY'] : []),
        ...(entitlement === 'ACTIVE' ? ['VIEW_CONTENT'] : ['TRIAL']),
      ],
      chapters: (
        options.chapters?.(courseId) ??
        [true, false].map((isTrial) => ({
          id: `${courseId}-${isTrial ? 'trial' : 'paid'}`,
          title: isTrial ? 'DEV 公开试看' : 'DEV 已购章节',
          durationSeconds: 60,
          isTrial,
          video: undefined,
        }))
      )
        .filter((chapter) => !('enabled' in chapter) || chapter.enabled !== false)
        .map((chapter) => {
          const { id, title, durationSeconds, isTrial } = chapter;
          const ready = !chapter.video || isReadyLearningAsset(chapter.video, 'COURSE_VIDEO');
          const saved = progress.get(progressKey(actor, courseId, id));
          return {
            id,
            title,
            durationSeconds,
            isTrial,
            canPlay: ready && (isTrial || entitlement === 'ACTIVE'),
            lastPositionSeconds: Math.min(saved?.positionSeconds ?? 0, durationSeconds),
            completed: saved?.completed ?? false,
          };
        }),
    };
  }
  const transport: Transport = async (request) => {
    const parsed = new RuntimeURL(request.url, 'http://localhost');
    const path = parsed.pathname.split('/api/v1')[1] ?? '';
    const actor = request.headers.Authorization?.replace(/^Bearer /, '') ?? '';
    const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
      status,
      body: { code, message, data: JSON.parse(JSON.stringify(data)), requestId: 'DEV-learning' },
    });
    if (path === '/learning/courses' && request.method === 'GET') {
      if (!actor) return reply(null, 401, 'UNAUTHORIZED', '请登录后查看已购课程');
      const filter = null;
      const page = Number(parsed.searchParams.get('page') ?? 1);
      const pageSize = Number(parsed.searchParams.get('pageSize') ?? 20);
      if (
        !Number.isInteger(page) ||
        page < 1 ||
        !Number.isInteger(pageSize) ||
        pageSize < 1 ||
        pageSize > 100 ||
        (filter !== null && !['IN_PROGRESS', 'COMPLETED', 'EXPIRED'].includes(filter))
      )
        return reply(null, 400, 'VALIDATION_ERROR', '课程列表筛选无效');
      const courses = catalog.products.flatMap((product) => {
        if (product.type !== 'COURSE') return [];
        const course = courseView(product.course.courseId, actor);
        return course && course.entitlement !== 'NONE' ? [course] : [];
      });
      const items = courses.filter((course) => {
        const status =
          course.entitlement === 'EXPIRED'
            ? 'EXPIRED'
            : course.chapters.every((chapter) => chapter.completed)
              ? 'COMPLETED'
              : 'IN_PROGRESS';
        return filter === null || filter === status;
      });
      return reply({
        items: items.slice((page - 1) * pageSize, page * pageSize).map((course) => ({
          courseId: course.id,
          productId: course.productId,
          title: course.title,
          status: course.entitlement,
          expiresAt: course.expiresAt,
          completedChapters: course.chapters.filter((ch) => ch.completed).length,
          totalChapters: course.chapters.length,
        })),
        page,
        pageSize,
        total: items.length,
      });
    }
    const chapterList = path.match(/^\/courses\/([^/]+)\/chapters$/);
    if (chapterList && request.method === 'GET') {
      const course = courseView(decodeURIComponent(chapterList[1]!), actor);
      if (!course) return reply(null, 404, 'NOT_FOUND', '课程不存在');
      const page = Number(parsed.searchParams.get('page') ?? 1),
        pageSize = Number(parsed.searchParams.get('pageSize') ?? 20);
      return reply({
        items: course.chapters.slice((page - 1) * pageSize, page * pageSize).map((chapter) => ({
          id: chapter.id,
          title: chapter.title,
          durationSeconds: chapter.durationSeconds,
          isTrial: chapter.isTrial,
          canPlay: chapter.canPlay,
          lastPositionSeconds: chapter.lastPositionSeconds,
        })),
        page,
        pageSize,
        total: course.chapters.length,
      });
    }
    const progressRoute = path.match(/^\/learning\/chapters\/([^/]+)\/progress$/);
    if (progressRoute && request.method === 'PUT') {
      const chapterId = decodeURIComponent(progressRoute[1]!);
      const owner = catalog.products.find(
        (p) =>
          p.type === 'COURSE' &&
          courseView(p.course.courseId, actor)?.chapters.some((ch) => ch.id === chapterId),
      );
      if (owner?.type !== 'COURSE') return reply(null, 404, 'NOT_FOUND', '章节不存在');
      return transport({
        ...request,
        url:
          request.url.split('/learning/chapters/')[0] +
          '/courses/' +
          encodeURIComponent(owner.course.courseId) +
          '/chapters/' +
          encodeURIComponent(chapterId) +
          '/progress',
      });
    }
    const match = path.match(/^\/courses\/([^/]+)(?:\/chapters\/([^/]+)\/(play-url|progress))?$/);
    if (!match) return reply(null, 404, 'NOT_FOUND', '此DEV课程场景未接入');
    const courseId = decodeURIComponent(match[1]!);
    const course = courseView(courseId, actor);
    if (!course) return reply(null, 404, 'NOT_FOUND', '课程不存在');
    if (!match[2] && request.method === 'GET') return reply(course);
    const chapter = course.chapters.find((item) => item.id === decodeURIComponent(match[2] ?? ''));
    if (!chapter) return reply(null, 404, 'NOT_FOUND', '章节不存在');
    if (!chapter.canPlay)
      return reply(
        null,
        403,
        course.entitlement === 'EXPIRED' ? 'ENTITLEMENT_EXPIRED' : 'FORBIDDEN',
        '当前没有该章节观看权益',
      );
    if (match[3] === 'play-url' && request.method === 'GET') {
      if (!options.mediaUrl) return reply(null, 409, 'FILE_NOT_READY', 'DEV测试视频尚未配置');
      const url = new RuntimeURL(options.mediaUrl);
      url.searchParams.set('devChapter', chapter.id);
      const expires = Math.min(
        now() + 60_000,
        !chapter.isTrial && course.expiresAt ? Date.parse(course.expiresAt) : Infinity,
      );
      return reply({
        url: url.href,
        expiresAt: new Date(expires).toISOString(),
        resumeSeconds: chapter.lastPositionSeconds,
      });
    }
    if (match[3] === 'progress' && request.method === 'PUT') {
      if (!actor) return reply(null, 401, 'UNAUTHORIZED', '请登录后保存学习进度');
      const input = request.body as Partial<CourseProgress> | null;
      if (
        !input ||
        typeof input.positionSeconds !== 'number' ||
        !Number.isFinite(input.positionSeconds) ||
        input.positionSeconds < 0 ||
        input.positionSeconds > chapter.durationSeconds ||
        typeof input.completed !== 'boolean' ||
        (input.completed && input.positionSeconds !== chapter.durationSeconds)
      )
        return reply(null, 400, 'VALIDATION_ERROR', '播放进度须在章节时长内，完成进度须位于结尾');
      progress.set(progressKey(actor, courseId, chapter.id), {
        positionSeconds: input.positionSeconds,
        completed: chapter.completed || input.completed,
      });
      return reply(null);
    }
    return reply(null, 404, 'NOT_FOUND', '此DEV课程操作未接入');
  };
  const content: DevDigitalContent = {
    projectProduct(product, actor) {
      if (product.type !== 'COURSE') return product;
      const course = courseView(product.course.courseId, actor);
      if (!course) return product;
      return {
        ...product,
        course: {
          ...product.course,
          entitlement: course.entitlement,
          chaptersSummary: course.chapters.length,
        },
        allowedActions: [
          ...(product.status === 'ON_SALE' && course.entitlement !== 'ACTIVE'
            ? ['BUY' as const]
            : []),
          ...(course.entitlement === 'ACTIVE' ? ['VIEW_CONTENT' as const] : ['TRIAL' as const]),
        ],
      };
    },
    paid(order, product, actor) {
      if (product.type !== 'COURSE') return false;
      if (!purchases.has(order.id)) {
        const paidAt = Date.parse(order.paidAt ?? '');
        const days = product.course.validityDays;
        if (
          !actor ||
          !Number.isFinite(paidAt) ||
          (product.course.validityType === 'DAYS' && (!Number.isSafeInteger(days) || days! <= 0))
        )
          return false;
        purchases.set(order.id, {
          actor,
          courseId: product.course.courseId,
          revoked: false,
          expiresAt:
            product.course.validityType === 'PERMANENT'
              ? null
              : new Date(paidAt + days! * 86_400_000).toISOString(),
        });
      }
      return true;
    },
    refunded(order) {
      const purchase = purchases.get(order.id);
      if (purchase && order.paidAmountFen > 0 && order.refundedAmountFen === order.paidAmountFen)
        purchase.revoked = true;
    },
  };
  return { transport, grantCourse, revokeCourse, content };
}
