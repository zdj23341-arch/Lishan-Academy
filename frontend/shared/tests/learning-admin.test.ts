import { describe, expect, it } from 'vitest';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevAdminHandler } from '../src/dev/content-admin';
import { createDevTransport } from '../src/dev/transport';
import type { Page } from '../src/contracts';
import { createAdminContentApi } from '../src/content/admin-api';
import { createDevLearningHandler } from '../src/dev/learning';
import { createDevMembershipHandler } from '../src/dev/membership';
import { combineDevDigitalContent } from '../src/dev/digital-content';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createCommerceApi } from '../src/commerce/api';
import { createLearningApi } from '../src/learning/api';
import { createCatalogApi } from '../src/content/api';
import type { AdminAsset, UploadInput } from '../src/content/admin-types';
import { createDevAdminLearningHandler } from '../src/dev/learning-admin';
import { createAdminLearningApi } from '../src/learning/admin-api';
import { parseYuan } from '../src/money';
import {
  validateCourse,
  validateChapter,
  validateCoursePublish,
  validatePaper,
  validateMembershipPlan,
} from '../src/learning/admin-validation';
import type { AftersaleView } from '../src/commerce/aftersale';

function setup() {
  const catalog = createDevCatalogStore();
  const content = createDevAdminHandler(catalog);
  const now = () => Date.parse('2026-08-31T04:00:00Z');
  const membership = createDevMembershipHandler(catalog, {
    now,
    fileUrls: Object.fromEntries(
      catalog.papers.flatMap((paper) =>
        paper.attachments.map((file) => [
          file.id,
          `/dev-files/paper-sample.${file.extension.toLowerCase()}`,
        ]),
      ),
    ),
    fileReady: (id) => content.assets.some((asset) => asset.id === id && asset.status === 'READY'),
  });
  const adminLearning = createDevAdminLearningHandler(catalog, content, {
    membershipRecords: membership.records,
  });
  const learning = createDevLearningHandler(catalog, {
    now,
    mediaUrl: 'http://127.0.0.1:5173/course-sample.mp4',
    chapters: adminLearning.chapters,
  });
  const digital = combineDevDigitalContent(learning.content, membership.content);
  const commerce = createDevCommerceHandler(catalog, {
    now,
    content: digital,
    refundOutcome: 'SUCCESS',
  });
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.set({
    accessToken: 'DEV-admin-front-end-only',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'DEV-admin', nickname: 'DEV', distributor: false },
    permissions: ['CONTENT_EDIT', 'ORDER_OPERATE'],
  });
  let requests = 0;
  const transport = createDevTransport(
    catalog,
    async (request) => {
      for (const handler of [
        learning.transport,
        membership.transport,
        adminLearning.transport,
        commerce,
        content,
      ]) {
        const result = await handler(request);
        if (result.status !== 404) return result;
      }
      return content(request);
    },
    { content: digital },
  );
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: (request) => {
      requests++;
      return transport(request);
    },
  });
  const assets = createAdminContentApi(client, () => ['CONTENT_EDITOR']);
  const shop = createCommerceApi(client);
  let sequence = 0;
  const key = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
  const upload = async (purpose: UploadInput['purpose'], ready = true): Promise<AdminAsset> => {
    const video = purpose === 'COURSE_VIDEO';
    const ticket = await assets.presign({
      name: video ? 'lesson.mp4' : 'paper.pdf',
      mime: video ? 'video/mp4' : 'application/pdf',
      bytes: 3,
      purpose,
    });
    if (ready) {
      await content({
        url: ticket.uploadUrl,
        method: 'PUT',
        headers: { 'X-Upload-Token': ticket.uploadToken },
        body: new Uint8Array([1, 2, 3]),
      });
      return assets.complete(ticket.id);
    }
    return (await assets.assets({ pageSize: 100 })).items.find((asset) => asset.id === ticket.id)!;
  };
  return {
    client,
    session,
    assets,
    upload,
    admin: createAdminLearningApi(client, () => session.get()?.permissions ?? []),
    requestCount: () => requests,
    api: createLearningApi(client),
    publicApi: createCatalogApi(client),
    order: async (productId: string) => {
      const quote = await shop.preview({ productId, quantity: 1 });
      return shop.createOrder({ quoteId: quote.quoteId }, key());
    },
    pay: (id: string) =>
      client.request(`/dev/orders/${id}/pay`, {
        method: 'POST',
        body: { scenario: 'SUCCESS' },
        idempotencyKey: key(),
      }),
    refund: async (id: string, goodsRefundFen: number) => {
      const aftersale = await client.request<AftersaleView>(`/orders/${id}/refund`, {
        method: 'POST',
        body: {
          type: 'REFUND_ONLY',
          reason: 'DEV test',
          evidenceAssetIds: [],
          requestedGoodsFen: goodsRefundFen,
          requestedFreightFen: 0,
        },
        idempotencyKey: key(),
      });
      return client.request(`/admin/aftersales/${aftersale.id}/approve`, {
        method: 'POST',
        body: { goodsRefundFen, freightRefundFen: 0, reason: 'DEV approved' },
        idempotencyKey: key(),
      });
    },
  };
}

describe('admin learning real DEV API boundary', () => {
  it('uses pagination while omitting unsupported course and paper filters', async () => {
    const s = setup();
    expect((await s.admin.courses({ keyword: '', page: 1, pageSize: 20 })).total).toBe(2);
    expect((await s.admin.courses({ status: 'OFF_SALE', page: 1, pageSize: 20 })).total).toBe(2);
    expect((await s.admin.papers({ schoolStage: 'PRIMARY', page: 1, pageSize: 20 })).total).toBe(4);
    await expect(s.admin.courses({ page: 0 })).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  });
  it('reads current entitlement fields and leaves unsupported event filters out', async () => {
    const s = setup();
    await s.pay((await s.order('DEV-member-6')).id);
    const value = await s.admin.membershipRecords({
      state: 'EXPIRED',
      orderId: 'ignored',
      page: 1,
      pageSize: 20,
    });
    expect(value.items).toEqual([
      expect.objectContaining({
        userId: 'DEV-admin-front-end-only',
        status: 'ACTIVE',
        level: 'STANDARD',
        expiresAt: '2027-02-28T04:00:00.000Z',
      }),
    ]);
    expect(value.items[0]).not.toHaveProperty('orderId');
  });
  it('rejects an old course form after shared title and introduction are edited through products', async () => {
    const s = setup();
    const oldCourse = await s.admin.course('DEV-course');
    const product = await s.assets.product(oldCourse.productId);
    const edited = await s.assets.saveProduct(product.id, {
      ...product,
      title: 'Concurrent product title',
      contentBlocks: [{ type: 'TEXT', text: 'Concurrent product introduction' }],
    });
    await expect(
      s.admin.saveCourse(oldCourse.id, { ...oldCourse, validityDays: 7 }),
    ).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
    const reread = await s.admin.course(oldCourse.id);
    expect(reread.productVersion).toBeGreaterThan(oldCourse.productVersion!);
    expect(reread).toMatchObject({ title: edited.title, contentBlocks: edited.contentBlocks });
    expect(await s.publicApi.product(product.id)).toMatchObject({
      title: edited.title,
      contentBlocks: edited.contentBlocks,
    });
    await s.admin.saveCourse(reread.id, { ...reread, validityDays: 7 });
    expect((await s.assets.product(edited.id)).version).toBe(edited.version);
  });
  it('maintains plan prices exclusively through the backing product', async () => {
    const s = setup();
    const product = await s.assets.product('DEV-member-6');
    const plan = (await s.admin.membershipPlans()).items.find(
      (item) => item.productId === product.id,
    )!;
    await s.admin.saveMembershipPlan(plan.id, { ...plan, priceFen: 9900 });
    expect((await s.publicApi.product(product.id)).priceFen).toBe(product.priceFen);
    await s.assets.saveProduct(product.id, { ...product, priceFen: 6900 });
    expect(
      (await s.admin.membershipPlans()).items.find((item) => item.id === plan.id)?.priceFen,
    ).toBe(6900);
  });
  it('projects renewals and refund replay into one current entitlement per actor', async () => {
    const s = setup();
    const ids: string[] = [];
    for (let i = 0; i < 4; i++) {
      const order = await s.order('DEV-member-6');
      ids.push(order.id);
      await s.pay(order.id);
    }
    const records = (await s.admin.membershipRecords()).items;
    expect(records).toEqual([
      expect.objectContaining({
        status: 'ACTIVE',
        startsAt: '2026-08-31T04:00:00.000Z',
        expiresAt: '2028-08-28T04:00:00.000Z',
      }),
    ]);
    await s.pay(ids[0]!);
    expect((await s.admin.membershipRecords()).items).toEqual(records);
    await s.refund(ids[0]!, 5900);
    expect((await s.admin.membershipRecords()).items).toEqual([
      expect.objectContaining({ status: 'ACTIVE', expiresAt: '2028-02-28T04:00:00.000Z' }),
    ]);
    expect(await s.api.membership()).toMatchObject({ expiresAt: '2028-02-28T04:00:00.000Z' });
  });
  it('does not partially persist a course if a referenced product asset prevents synchronization', async () => {
    const s = setup();
    const before = await s.admin.course('DEV-course');
    const cover = (await s.assets.assets({ purpose: 'PUBLIC_IMAGE' })).items[0]!;
    await s.assets.disableAsset(cover.id, cover.version, 'Unavailable cover');
    await expect(
      s.admin.saveCourse(before.id, { ...before, title: 'Must remain unsaved' }),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    expect(await s.admin.course(before.id)).toEqual(before);
  });
  it('lists paged course metadata with distinct viewing validity', async () => {
    const { admin } = setup();
    const result = await admin.courses({ keyword: '多章节', page: 1, pageSize: 1 });
    expect(result).toMatchObject({ total: 2, page: 1, pageSize: 1 });
    expect(result.items[0]).toMatchObject({
      id: 'DEV-course',
      productId: 'DEV-course',
      validityType: 'DAYS',
      validityDays: 365,
      chapters: [],
    });
  });
  it('prevents every shared API mutation before network dispatch when content permission is absent', async () => {
    const s = setup();
    const course = await s.admin.course('DEV-course');
    const paper = await s.admin.paper('DEV-paper');
    const plan = (await s.admin.membershipPlans()).items[0]!;
    const count = s.requestCount();
    s.session.set({ ...s.session.get()!, permissions: ['ORDER_OPERATOR'] });
    for (const write of [
      () => s.admin.saveCourse(course.id, course),
      () => s.admin.saveChapter(course.id, course.chapters[0]!.id, course.chapters[0]!),
      () =>
        s.admin.reorderChapters(course.id, {
          version: course.version,
          chapters: course.chapters.map((c) => c.id),
        }),
      () => s.admin.savePaper(paper.id, paper),
      () => s.admin.saveMembershipPlan(plan.id, plan),
    ])
      await expect(write()).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(s.requestCount()).toBe(count);
  });
  it('validates yuan prices, ready asset bindings and publication without conflating validity with video duration', async () => {
    const s = setup();
    const course = await s.admin.course('DEV-course');
    expect(validateCourse({ ...course, validityDays: 0 })).toHaveProperty('validityDays');
    expect(validateCourse({ ...course, validityType: 'PERMANENT', validityDays: null })).toEqual(
      {},
    );
    expect(validateChapter(course.chapters[0]!, course.chapters[0]!.video)).toEqual({});
    expect(validateCoursePublish({ ...course, chapters: [] })).toHaveProperty('chapters');
    expect(
      validateChapter(course.chapters[0]!, { ...course.chapters[0]!.video!, status: 'PENDING' }),
    ).toHaveProperty('videoAssetId');
    expect(
      validatePaper({ ...(await s.admin.paper('DEV-paper')), attachmentIds: [] }),
    ).toHaveProperty('attachmentIds');
    const plan = (await s.admin.membershipPlans()).items[0]!;
    expect(validateMembershipPlan({ ...plan, priceFen: parseYuan('59.99') })).toEqual({});
    expect(validateMembershipPlan({ ...plan, durationMonths: 3 as 6 })).toHaveProperty(
      'durationMonths',
    );
    expect(() => parseYuan('59.999')).toThrow();
    const updated = await s.admin.saveMembershipPlan(plan.id, {
      ...plan,
      priceFen: parseYuan('59.99'),
    });
    expect(updated.priceFen).toBe(plan.priceFen);
  });
  it('revokes new paper-file authorization after the bound READY asset is disabled', async () => {
    const s = setup();
    await s.pay((await s.order('DEV-member-6')).id);
    const paper = await s.admin.paper('DEV-paper');
    const file = paper.attachments[0]!;
    expect((await s.api.authorize(paper.id, file.id, 'DOWNLOAD')).file.id).toBe(file.id);
    await s.assets.disableAsset(file.id, file.version, 'Removed file');
    await expect(s.api.authorize(paper.id, file.id, 'DOWNLOAD')).rejects.toMatchObject({
      code: 'FILE_NOT_READY',
    });
    expect(
      (await s.publicApi.paper(paper.id)).attachments.find((item) => item.id === file.id)
        ?.canDownload,
    ).toBe(false);
  });
  it('reads the current membership interval after renewals and a refund', async () => {
    const s = setup();
    const first = await s.order('DEV-member-6');
    await s.pay(first.id);
    const second = await s.order('DEV-member-12');
    await s.pay(second.id);
    expect((await s.admin.membershipRecords()).items).toEqual([
      expect.objectContaining({ status: 'ACTIVE', expiresAt: '2028-02-28T04:00:00.000Z' }),
    ]);
    await s.refund(first.id, 5900);
    expect((await s.admin.membershipRecords()).items).toEqual([
      expect.objectContaining({ status: 'ACTIVE', expiresAt: '2027-08-31T04:00:00.000Z' }),
    ]);
  });
  it('rejects raw mutation from a non-content role without changing a course', async () => {
    const s = setup();
    s.session.set({
      accessToken: 'DEV-order-operator',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'operator', nickname: 'DEV', distributor: false },
      permissions: ['ORDER_OPERATOR'],
    });
    await expect(
      s.client.request('/admin/courses/DEV-course', {
        method: 'PATCH',
        body: { version: 1, title: 'forbidden' },
      }),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect((await s.publicApi.product('DEV-course')).title).toBe('开发示例 · 多章节语文课程');
  });
  it('rejects nonpositive DAYS and nonempty PERMANENT validity without mutating input', async () => {
    const s = setup();
    for (const body of [
      {
        productId: 'DEV-course',
        version: 1,
        title: 'Invalid',
        validityType: 'DAYS',
        validityDays: 0,
        contentBlocks: [],
      },
      {
        productId: 'DEV-course',
        version: 1,
        title: 'Invalid',
        validityType: 'PERMANENT',
        validityDays: 10,
        contentBlocks: [],
      },
    ]) {
      await expect(
        s.client.request('/admin/courses/DEV-course', { method: 'PATCH', body }),
      ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    }
    expect((await s.publicApi.product('DEV-course')).title).toBe('开发示例 · 多章节语文课程');
  });
  it('binds only READY videos, derives duration from asset metadata and persists trial/order edits', async () => {
    const s = setup();
    const pending = await s.upload('COURSE_VIDEO', false);
    const body = {
      version: 0,
      title: 'New chapter',
      videoAssetId: pending.id,
      isTrial: false,
      durationSeconds: 60,
      sortNo: 2,
      enabled: true,
    };
    await expect(
      s.client.request('/admin/courses/DEV-course/chapters', { method: 'POST', body }),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    const video = await s.upload('COURSE_VIDEO');
    const chapter = await s.client.request<{
      id: string;
      version: number;
      durationSeconds: number;
    }>('/admin/courses/DEV-course/chapters', {
      method: 'POST',
      body: { ...body, videoAssetId: video.id, durationSeconds: 60 },
    });
    expect(chapter.durationSeconds).toBe(60);
    await s.client.request(`/admin/chapters/${chapter.id}`, {
      method: 'PATCH',
      body: { ...body, version: chapter.version, videoAssetId: video.id, isTrial: true },
    });
    const course = await s.admin.course('DEV-course');
    const ids = course.chapters.map((c) => c.id).reverse();
    await s.client.request('/admin/courses/DEV-course/chapter-order', {
      method: 'PUT',
      body: { version: course.version, chapters: ids },
    });
    expect((await s.api.course('DEV-course', 'DEV-course')).chapters.map((c) => c.id)).toEqual(ids);
    expect((await s.api.course('DEV-course', 'DEV-course')).chapters[0]).toMatchObject({
      title: 'New chapter',
      isTrial: true,
      canPlay: true,
      durationSeconds: 60,
    });
    await expect(
      s.client.request('/admin/courses/DEV-course/chapter-order', {
        method: 'PUT',
        body: { version: course.version, chapters: ids },
      }),
    ).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
  });
  it('keeps the unsaved input and prior order snapshot after a course version conflict', async () => {
    const s = setup();
    const order = await s.order('DEV-course');
    const course = await s.admin.course('DEV-course');
    const input = {
      ...course,
      title: 'Edited course',
      validityType: 'DAYS' as const,
      validityDays: 7,
    };
    await s.admin.saveCourse('DEV-course', input);
    await expect(s.admin.saveCourse('DEV-course', input)).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
    });
    expect(input).toMatchObject({ version: 1, title: 'Edited course', validityDays: 7 });
    expect(await s.publicApi.product('DEV-course')).toMatchObject({
      title: 'Edited course',
      course: { validityDays: 7 },
    });
    await s.pay(order.id);
    expect(await s.api.course('DEV-course', 'DEV-course')).toMatchObject({
      entitlement: 'ACTIVE',
      expiresAt: '2027-08-31T04:00:00.000Z',
    });
  });
  it('publishes prepared courses through the existing product action and blocks disabled videos', async () => {
    const s = setup();
    const source = await s.assets.product('DEV-course');
    const product = await s.assets.saveProduct(null, {
      ...source,
      version: 0,
      title: 'New course',
    });
    const course = await s.client.request<{ id: string }>('/admin/courses', {
      method: 'POST',
      body: {
        productId: product.id,
        version: 0,
        title: 'New course',
        validityType: 'PERMANENT',
        validityDays: null,
        contentBlocks: [],
      },
    });
    const configured = await s.assets.product(product.id);
    await expect(
      s.assets.setProductPublished(product.id, true, configured.version),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    const video = await s.upload('COURSE_VIDEO');
    await s.client.request(`/admin/courses/${course.id}/chapters`, {
      method: 'POST',
      body: {
        version: 0,
        title: 'Lesson',
        videoAssetId: video.id,
        isTrial: true,
        durationSeconds: 60,
        sortNo: 0,
        enabled: true,
      },
    });
    const current = await s.assets.product(product.id);
    const published = await s.assets.setProductPublished(product.id, true, current.version);
    expect(await s.publicApi.product(product.id)).toMatchObject({
      status: 'ON_SALE',
      course: { courseId: course.id, validityType: 'PERMANENT', chaptersSummary: 1 },
    });
    await s.assets.disableAsset(video.id, video.version, 'Unavailable video');
    await s.assets.setProductPublished(product.id, false, published.version);
    const off = await s.assets.product(product.id);
    await expect(s.assets.setProductPublished(product.id, true, off.version)).rejects.toMatchObject(
      { code: 'FILE_NOT_READY' },
    );
  });
  it('saves classified papers with READY attachments and keeps originals out of public samples', async () => {
    const s = setup();
    const pending = await s.upload('PAPER_ATTACHMENT', false);
    const input = {
      version: 0,
      title: 'New paper',
      stage: 'PRIMARY',
      grade: '6',
      subject: 'CHINESE',
      year: null,
      contentBlocks: [{ type: 'TEXT', text: 'Public sample' }],
      attachments: [{ assetId: pending.id, title: '试卷', sortNo: 0 }],
      visibility: 'PUBLIC',
      status: 'PUBLISHED',
    };
    await expect(
      s.client.request('/admin/papers', { method: 'POST', body: input }),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    const file = await s.upload('PAPER_ATTACHMENT');
    const paper = await s.client.request<{ id: string; version: number }>('/admin/papers', {
      method: 'POST',
      body: { ...input, attachments: [{ assetId: file.id, title: '试卷', sortNo: 0 }] },
    });
    expect(await s.publicApi.paper(paper.id)).toMatchObject({
      title: 'New paper',
      contentBlocks: [{ type: 'TEXT', text: 'Public sample' }],
      attachments: [{ id: file.id, canDownload: false }],
    });
    const published = await s.client.request<Record<string, unknown>>(`/admin/papers/${paper.id}`);
    await s.client.request(`/admin/papers/${paper.id}`, {
      method: 'PATCH',
      body: { ...published, status: 'UNPUBLISHED' },
    });
    await expect(s.publicApi.paper(paper.id)).rejects.toMatchObject({ code: 'NOT_FOUND' });
    expect(
      (
        await s.client.request<Page<unknown>>('/admin/papers', {
          query: { keyword: 'New paper', pageSize: 1 },
        })
      ).total,
    ).toBe(5);
    await expect(
      s.client.request(`/admin/papers/${paper.id}`, { method: 'DELETE' }),
    ).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });
  it('validates required paper classification and forbids attachment-as-public-sample', async () => {
    const s = setup();
    const file = await s.upload('PAPER_ATTACHMENT');
    const body = {
      version: 0,
      title: 'Invalid paper',
      stage: '',
      grade: '',
      subject: '',
      year: null,
      summary: '',
      contentBlocks: [],
      attachments: [{ assetId: file.id, title: '文件', sortNo: 0 }],
      visibility: 'PUBLIC',
      status: 'PUBLISHED',
    };
    await expect(s.client.request('/admin/papers', { method: 'POST', body })).rejects.toMatchObject(
      { code: 'VALIDATION_ERROR' },
    );
    await expect(
      s.client.request('/admin/papers', {
        method: 'POST',
        body: {
          ...body,
          stage: 'PRIMARY',
          grade: '6',
          subject: 'CHINESE',
          contentBlocks: [{ type: 'IMAGE', assetId: file.id }],
        },
      }),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
  });
  it('requires unpublish before changing plan terms and preserves prior order snapshots', async () => {
    const s = setup();
    const order = await s.order('DEV-member-6');
    const plan = (await s.admin.membershipPlans()).items.find(
      (item) => item.productId === 'DEV-member-6',
    )!;
    await expect(
      s.admin.saveMembershipPlan(plan.id, { ...plan, enabled: false, durationMonths: 12 }),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    const product = await s.assets.product(plan.productId);
    await s.assets.setProductPublished(product.id, false, product.version);
    const updated = await s.admin.saveMembershipPlan(plan.id, {
      ...plan,
      enabled: false,
      durationMonths: 12,
    });
    expect(updated).toMatchObject({
      enabled: false,
      status: 'DISABLED',
      durationMonths: 12,
      priceFen: 5900,
    });
    await expect(s.admin.saveMembershipPlan(plan.id, plan)).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
    });
    await s.pay(order.id);
    expect(await s.api.membership()).toMatchObject({
      state: 'ACTIVE',
      expiresAt: '2027-02-28T04:00:00.000Z',
    });
    expect(await s.client.request(`/orders/${order.id}`)).toMatchObject({
      paidAmountFen: 5900,
      item: { unitPriceFen: 5900 },
    });
    await expect(
      s.client.request('/admin/membership/records/user/expiry', {
        method: 'PATCH',
        body: { expiresAt: '2099-01-01' },
      }),
    ).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });
});
