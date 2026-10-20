import { describe, expect, it } from 'vitest';
import { createClient, type TransportRequest } from '../src/http';
import { createSession } from '../src/session';
import { createAdminLearningApi } from '../src/learning/admin-api';

const course = {
  id: '20',
  version: 3,
  productId: '10',
  validityType: 'DAYS' as const,
  validityDays: 365,
  teacherName: null,
};
const product = {
  id: '10',
  type: 'COURSE',
  version: 5,
  title: '原商品',
  subtitle: '',
  coverAssetId: null,
  priceFen: 100,
  originalPriceFen: null,
  sortNo: 0,
  categoryIds: [],
  contentBlocks: [],
  status: 'DRAFT',
};
const courseInput = { ...course, title: '新课程', contentBlocks: [], productVersion: 5 };
const chapter = {
  id: '30',
  courseId: '20',
  version: 1,
  title: '新章',
  videoAssetId: '40',
  durationSeconds: 600,
  sortNo: 0,
  enabled: true,
  isTrial: false,
};
const paper = {
  id: '50',
  version: 1,
  title: '试卷',
  stage: 'PRIMARY',
  grade: '1',
  subject: '数学',
  year: 2026,
  coverAssetId: null,
  status: 'DRAFT',
  contentBlocks: [],
  attachments: [{ assetId: '40', title: '答案', sortNo: 0 }],
};
const page = (items: unknown[], n = 1, total = items.length) => ({
  items,
  page: n,
  pageSize: 100,
  total,
});
function setup(handle: (request: TransportRequest) => unknown | Promise<unknown>) {
  const requests: TransportRequest[] = [];
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: async (request) => {
      requests.push(request);
      const data = await handle(request);
      return { status: 200, body: { code: 'OK', message: '', requestId: 'review', data } };
    },
  });
  return { api: createAdminLearningApi(client, () => ['CONTENT_EDIT']), requests };
}
describe('committed learning mutations', () => {
  it('rejects a product bound on a later course page before modifying either resource', async () => {
    const { api, requests } = setup((request) => {
      if (request.url.includes('/admin/courses?page=1'))
        return page([{ ...course, productId: 'other' }], 1, 101);
      if (request.url.includes('/admin/courses?page=2')) return page([course], 2, 101);
      if (request.url.endsWith('/admin/products/10')) return product;
      if (request.url.endsWith('/admin/courses')) throw new Error('unique product binding');
      throw new Error(request.url);
    });
    await expect(api.saveCourse(null, courseInput)).rejects.toMatchObject({
      code: 'PRODUCT_ALREADY_BOUND',
    });
    expect(requests.every((request) => request.method === 'GET')).toBe(true);
  });
  it('a concurrent create rejection leaves the existing product untouched', async () => {
    let title = product.title;
    const { api } = setup((request) => {
      if (request.url.includes('/admin/courses?page=')) return page([]);
      if (request.url.endsWith('/admin/products/10')) {
        if (request.method === 'PATCH') title = (request.body as typeof product).title;
        return { ...product, title };
      }
      if (request.method === 'POST') throw new Error('another writer bound this product');
      throw new Error(request.url);
    });
    await expect(api.saveCourse(null, courseInput)).rejects.toBeTruthy();
    expect(title).toBe('原商品');
  });
  it('preserves a new course identity when its subsequent product update fails', async () => {
    const { api } = setup((request) => {
      if (request.url.includes('/admin/courses?page=')) return page([]);
      if (request.url.endsWith('/admin/products/10') && request.method === 'GET') return product;
      if (request.url.endsWith('/admin/courses') && request.method === 'POST') return course;
      throw new Error('product write failed');
    });
    await expect(api.saveCourse(null, courseInput)).rejects.toMatchObject({
      code: 'MUTATION_COMMITTED',
      resource: { kind: 'COURSE', id: '20', version: 3 },
      completedStages: ['COURSE'],
    });
  });
  it('distinguishes a committed course edit from a failed final composition read', async () => {
    let productReads = 0;
    const { api } = setup((request) => {
      if (request.url.endsWith('/admin/courses/20')) return course;
      if (request.url.endsWith('/admin/products/10')) {
        if (request.method === 'PATCH') return { ...product, version: 6 };
        if (++productReads > 1) throw new Error('read unavailable');
        return product;
      }
      if (request.url.includes('/chapters?')) return page([]);
      throw new Error(request.url);
    });
    await expect(api.saveCourse('20', courseInput)).rejects.toMatchObject({
      code: 'MUTATION_COMMITTED',
      resource: { kind: 'COURSE', id: '20', version: 3 },
      completedStages: ['COURSE', 'PRODUCT'],
    });
  });
  it.each([null, '30'])(
    'preserves a committed chapter identity when file hydration fails (%s)',
    async (id) => {
      const { api } = setup((request) => {
        if (request.method !== 'GET') return chapter;
        throw new Error('file detail unavailable');
      });
      await expect(api.saveChapter('20', id, chapter)).rejects.toMatchObject({
        code: 'MUTATION_COMMITTED',
        resource: { kind: 'CHAPTER', id: '30', version: 1 },
      });
    },
  );
  it('preserves the committed reorder course version when composition fails', async () => {
    const { api } = setup((request) => {
      if (request.method === 'PUT') return { ...course, version: 4 };
      throw new Error('chapter/product unavailable');
    });
    await expect(api.reorderChapters('20', { version: 3, chapters: ['30'] })).rejects.toMatchObject(
      { code: 'MUTATION_COMMITTED', resource: { kind: 'COURSE', id: '20', version: 4 } },
    );
  });
  it.each([null, '50'])(
    'preserves a committed paper identity when file hydration fails (%s)',
    async (id) => {
      const { api } = setup((request) => {
        if (request.method !== 'GET') return paper;
        throw new Error('attachment unavailable');
      });
      await expect(
        api.savePaper(id, {
          ...paper,
          schoolStage: 'PRIMARY',
          year: '2026',
          summary: '',
          visibility: 'HIDDEN',
          status: 'DRAFT',
          attachmentIds: ['40'],
        }),
      ).rejects.toMatchObject({
        code: 'MUTATION_COMMITTED',
        resource: { kind: 'PAPER', id: '50', version: 1 },
      });
    },
  );
});
