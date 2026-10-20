import { describe, expect, it } from 'vitest';
import { createClient, type Transport } from '../src/http';
import { createSession } from '../src/session';
import { createLearningApi } from '../src/learning/api';
import { createAdminLearningApi } from '../src/learning/admin-api';

function setup(routes: Record<string, unknown>, permissions = ['CONTENT_EDIT']) {
  const requests: Parameters<Transport>[0][] = [];
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.set({
    accessToken: 'user',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: '1', nickname: 'User', distributor: false },
  });
  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: async (request) => {
      requests.push(request);
      const key = request.url.replace('/api/v1', '');
      if (key === '/learning/courses?page=1&pageSize=100' && !(key in routes))
        return {
          status: 200,
          body: { code: 'OK', message: '', requestId: 'test', data: page([]) },
        };
      if (!(key in routes)) throw new Error(`Unexpected endpoint ${key}`);
      return {
        status: 200,
        body: { code: 'OK', message: '', requestId: 'test', data: await routes[key] },
      };
    },
  });
  return {
    api: createLearningApi(client),
    admin: createAdminLearningApi(
      client,
      () => permissions,
      () => session.getGeneration(),
    ),
    requests,
    session,
  };
}
const page = <T>(items: T[], n = 1, total = items.length) => ({
  items,
  page: n,
  pageSize: 100,
  total,
});
const product = {
  id: '8',
  version: 4,
  type: 'COURSE',
  title: '真实课程',
  status: 'OFF_SALE',
  subtitle: '',
  coverAssetId: null,
  priceFen: 100,
  originalPriceFen: null,
  sortNo: 3,
  categoryIds: [],
  contentBlocks: [{ type: 'TEXT', text: '介绍' }],
  allowedActions: ['VIEW_CONTENT'],
  course: { courseId: '2', entitlement: 'ACTIVE' },
};
const wireCourse = {
  id: '2',
  version: 3,
  productId: '8',
  validityType: 'DAYS' as const,
  validityDays: 365,
  teacherName: '教师',
};
const chapter = {
  id: '3',
  version: 5,
  courseId: '2',
  title: '章节',
  sortNo: 3,
  videoAssetId: '9',
  durationSeconds: 1800,
  isTrial: false,
  enabled: false,
};
const asset = {
  id: '9',
  name: 'video.mp4',
  extension: 'MP4',
  mime: 'video/mp4',
  bytes: 100,
  version: 1,
  status: 'READY',
  purpose: 'COURSE_VIDEO',
  scanStatus: 'CLEAN',
  durationSeconds: 1800,
};

describe('learning real wire contracts', () => {
  it('uses real membership and progress endpoints and forwards the learning grouping', async () => {
    const { api, requests } = setup({
      '/membership/me': { state: 'ACTIVE', plans: [] },
      '/learning/courses?status=EXPIRED&page=2&pageSize=10': page(
        [
          {
            courseId: '2',
            productId: '8',
            title: '课程',
            status: 'ACTIVE',
            expiresAt: null,
            completedChapters: 2,
            totalChapters: 2,
          },
        ],
        2,
      ),
      '/learning/chapters/3/progress': null,
    });
    expect(await api.membership()).toMatchObject({ state: 'ACTIVE' });
    expect(
      (await api.courses({ status: 'EXPIRED', page: 2, pageSize: 10 })).items[0],
    ).toMatchObject({ id: '2', completedChapters: 2, totalChapters: 2 });
    await api.progress('2', '3', { positionSeconds: 30, completed: false });
    expect(requests.at(-1)).toMatchObject({
      method: 'PUT',
      body: { positionSeconds: 30, completed: false },
    });
  });
  it('composes customer detail from the product and every chapter page', async () => {
    const { api } = setup({
      '/products/8': product,
      '/courses/2/chapters?page=1&pageSize=100': page(
        [
          {
            id: '3',
            title: '首章',
            durationSeconds: 100,
            isTrial: false,
            canPlay: true,
            lastPositionSeconds: 100,
          },
        ],
        1,
        101,
      ),
      '/courses/2/chapters?page=2&pageSize=100': page(
        [
          {
            id: '4',
            title: '次章',
            durationSeconds: 90,
            isTrial: true,
            canPlay: true,
            lastPositionSeconds: 0,
          },
        ],
        2,
        101,
      ),
    });
    expect(await api.course('2', '8')).toMatchObject({
      id: '2',
      productId: '8',
      title: '真实课程',
      entitlement: 'ACTIVE',
      chapters: [
        { id: '3', completed: true },
        { id: '4', completed: false },
      ],
    });
  });
  it('composes admin detail and saves product content through the product contract', async () => {
    const routes = {
      '/admin/courses/2': wireCourse,
      '/admin/products/8': product,
      '/admin/courses/2/chapters?page=1&pageSize=100': page([chapter]),
      '/admin/files/9': asset,
    };
    const { admin, requests } = setup(routes);
    const value = await admin.course('2');
    expect(value).toMatchObject({
      title: '真实课程',
      teacherName: '教师',
      productVersion: 4,
      chapters: [{ ...chapter, video: asset }],
    });
    await admin.saveCourse('2', { ...value, title: '新标题' });
    expect(
      requests.find((r) => r.method === 'PATCH' && r.url.endsWith('/admin/products/8'))?.body,
    ).toMatchObject({ version: 4, title: '新标题', priceFen: 100, sortNo: 3 });
    expect(
      requests.find((r) => r.method === 'PATCH' && r.url.endsWith('/admin/courses/2'))?.body,
    ).toEqual({
      version: 3,
      productId: '8',
      validityType: 'DAYS',
      validityDays: 365,
      teacherName: '教师',
    });
  });
  it('preserves chapter metadata and rejects missing duration instead of inventing one second', async () => {
    const { admin, requests } = setup({ '/admin/chapters/3': chapter, '/admin/files/9': asset });
    await admin.saveChapter('2', '3', { ...chapter, title: '改标题' });
    expect(requests[0]?.body).toMatchObject({ durationSeconds: 1800, enabled: false, sortNo: 3 });
    await expect(
      admin.saveChapter('2', '3', { version: 5, title: '标题', videoAssetId: '9', isTrial: false }),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  });
  it('normalizes numeric paper year and preserves business attachment metadata and cover', async () => {
    const wire = {
      id: '6',
      version: 2,
      title: '试卷',
      stage: 'PRIMARY',
      schoolStage: 'PRIMARY',
      grade: '1',
      subject: '数学',
      paperType: 'FINAL_EXAM',
      year: 2026,
      summary: '公开摘要',
      status: 'DRAFT',
      coverAssetId: '7',
      contentBlocks: [],
      attachments: [{ assetId: '9', title: '教师参考答案', sortNo: 8 }],
    };
    const { admin, requests } = setup({
      '/admin/papers/6': wire,
      '/admin/files/9': {
        ...asset,
        purpose: 'PAPER_ATTACHMENT',
        name: 'answers.pdf',
        extension: 'PDF',
        mime: 'application/pdf',
      },
    });
    const paper = await admin.paper('6');
    expect(paper).toMatchObject({
      schoolStage: 'PRIMARY',
      paperType: 'FINAL_EXAM',
      summary: '公开摘要',
      year: '2026',
      attachmentIds: ['9'],
      coverAssetId: '7',
      attachmentMetadata: wire.attachments,
    });
    await admin.savePaper('6', paper);
    expect(requests.find((r) => r.method === 'PATCH')?.body).toEqual({
      version: 2,
      title: '试卷',
      stage: 'PRIMARY',
      schoolStage: 'PRIMARY',
      grade: '1',
      subject: '数学',
      paperType: 'FINAL_EXAM',
      year: 2026,
      summary: '公开摘要',
      status: 'DRAFT',
      coverAssetId: '7',
      contentBlocks: [],
      attachments: wire.attachments,
    });
  });
  it('forwards supported admin paper classification filters to the backend', async () => {
    const { admin, requests } = setup({
      '/admin/papers?page=2&pageSize=10&keyword=%E6%9C%9F%E6%9C%AB&schoolStage=JUNIOR&stage=JUNIOR&grade=7&subject=CHINESE&year=2026&paperType=FINAL_EXAM&status=PUBLISHED':
        page([], 2, 0),
    });
    await admin.papers({
      page: 2,
      pageSize: 10,
      keyword: '期末',
      schoolStage: 'JUNIOR',
      stage: 'JUNIOR',
      grade: '7',
      subject: 'CHINESE',
      year: 2026,
      paperType: 'FINAL_EXAM',
      status: 'PUBLISHED',
    });
    expect(requests[0]?.url).toContain(
      '/admin/papers?page=2&pageSize=10&keyword=%E6%9C%9F%E6%9C%AB&schoolStage=JUNIOR&stage=JUNIOR&grade=7&subject=CHINESE&year=2026&paperType=FINAL_EXAM&status=PUBLISHED',
    );
  });
  it('reads entitlement records with ORDER_OPERATE and sends only supported plan fields', async () => {
    const { admin, requests } = setup(
      {
        '/admin/membership/records?page=1&pageSize=20&userId=5&status=ACTIVE': page([
          {
            id: '1',
            userId: '5',
            level: 'STANDARD',
            status: 'ACTIVE',
            startsAt: null,
            expiresAt: '2027-01-01',
            version: 2,
          },
        ]),
      },
      ['ORDER_OPERATE'],
    );
    expect(
      (await admin.membershipRecords({ userId: '5', status: 'ACTIVE', page: 1, pageSize: 20 }))
        .items[0],
    ).toMatchObject({ status: 'ACTIVE', expiresAt: '2027-01-01' });
    await expect(admin.membershipPlans()).rejects.toMatchObject({ code: 'FORBIDDEN' });
    const plans = setup({
      '/admin/membership/plans/1': {
        id: '1',
        productId: '8',
        version: 3,
        level: 'STANDARD',
        durationMonths: 6,
        priceFen: 5900,
        status: 'DISABLED',
      },
    });
    await plans.admin.saveMembershipPlan('1', {
      productId: '8',
      version: 2,
      durationMonths: 6,
      priceFen: 5900,
      enabled: false,
      description: '',
    });
    expect(plans.requests[0]?.body).toEqual({ version: 2, durationMonths: 6, status: 'DISABLED' });
    expect(requests).toHaveLength(1);
  });
  it('rejects a stale course version before changing linked product content', async () => {
    const { admin, requests } = setup({
      '/admin/courses/2': { ...wireCourse, version: 4 },
      '/admin/products/8': product,
    });
    await expect(
      admin.saveCourse('2', { ...wireCourse, title: '旧标题覆盖', contentBlocks: [] }),
    ).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
    expect(requests.some((request) => request.method === 'PATCH')).toBe(false);
  });
  it('opens incomplete chapters without inventing a bound video', async () => {
    const { admin } = setup({
      '/admin/courses/2': wireCourse,
      '/admin/products/8': product,
      '/admin/courses/2/chapters?page=1&pageSize=100': page([{ ...chapter, videoAssetId: null }]),
    });
    expect((await admin.course('2')).chapters[0]?.video).toBeUndefined();
  });
  it('stops composed writes when the account changes during preflight', async () => {
    let resolve!: (value: typeof wireCourse) => void;
    const pending = new Promise((done) => {
      resolve = done;
    });
    const { admin, session, requests } = setup({
      '/admin/courses/2': pending,
      '/admin/products/8': product,
    });
    const save = admin.saveCourse('2', { ...wireCourse, title: '新标题', contentBlocks: [] });
    session.clear();
    resolve(wireCourse);
    await expect(save).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(requests.some((request) => request.method === 'PATCH')).toBe(false);
  });
  it.each(['NONE', 'PENDING'])(
    'does not infer active rights from the learning list status %s',
    async (status) => {
      const { api } = setup({
        '/learning/courses': page([
          {
            courseId: '2',
            productId: '8',
            title: '课程',
            status,
            expiresAt: null,
            completedChapters: 0,
            totalChapters: 1,
          },
        ]),
      });
      expect((await api.courses()).items[0]).toMatchObject({
        entitlement: 'NONE',
        allowedActions: [],
      });
    },
  );
});
