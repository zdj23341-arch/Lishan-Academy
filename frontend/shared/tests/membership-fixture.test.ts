import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevLearningHandler } from '../src/dev/learning';
import { createDevMembershipHandler } from '../src/dev/membership';
import { combineDevDigitalContent } from '../src/dev/digital-content';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createDevTransport } from '../src/dev/transport';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createLearningApi } from '../src/learning/api';
import { createCatalogApi } from '../src/content/api';
import { createCommerceApi } from '../src/commerce/api';
import type { AftersaleView } from '../src/commerce/aftersale';
import type { OrderView } from '../src/contracts';

function setup(initial = '2026-08-31T04:00:00Z', withFiles = true) {
  let clock = Date.parse(initial);
  const now = () => clock;
  const catalog = createDevCatalogStore();
  const fileUrls = Object.fromEntries(
    catalog.papers.flatMap((p) =>
      p.attachments.map((f) => [f.id, `/dev-files/paper-sample.${f.extension.toLowerCase()}`]),
    ),
  );
  const member = createDevMembershipHandler(catalog, { now, fileUrls: withFiles ? fileUrls : {} });
  const learning = createDevLearningHandler(catalog, { now });
  const content = combineDevDigitalContent(learning.content, member.content);
  const commerce = createDevCommerceHandler(catalog, { now, content, refundOutcome: 'SUCCESS' });
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const login = (actor = 'buyer') =>
    session.set({
      accessToken: actor,
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: actor, nickname: 'DEV', distributor: false },
    });
  login();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(
      catalog,
      async (request) => {
        const membership = await member.transport(request);
        if (membership.status !== 404) return membership;
        const course = await learning.transport(request);
        return course.status !== 404 ? course : commerce(request);
      },
      { content },
    ),
  });
  const api = createLearningApi(client);
  const shop = createCommerceApi(client);
  const publicApi = createCatalogApi(client);
  let seq = 0;
  const key = () => `00000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`;
  async function order(productId = 'DEV-member-6') {
    const quote = await shop.preview({ productId, quantity: 1 });
    return shop.createOrder({ quoteId: quote.quoteId }, key());
  }
  const pay = (id: string) =>
    client.request(`/dev/orders/${id}/pay`, {
      method: 'POST',
      body: { scenario: 'SUCCESS' },
      idempotencyKey: key(),
    });
  async function refund(id: string, goodsRefundFen: number) {
    const value = await client.request<AftersaleView>(`/orders/${id}/refund`, {
      method: 'POST',
      idempotencyKey: key(),
      body: {
        type: 'REFUND_ONLY',
        reason: 'DEV会员补偿',
        evidenceAssetIds: [],
        requestedGoodsFen: goodsRefundFen,
        requestedFreightFen: 0,
      },
    });
    await client.request(`/admin/aftersales/${value.id}/approve`, {
      method: 'POST',
      idempotencyKey: key(),
      body: { goodsRefundFen, freightRefundFen: 0, reason: 'DEV核对' },
    });
  }
  return {
    api,
    publicApi,
    catalog,
    client,
    session,
    login,
    order,
    pay,
    refund,
    setTime: (time: string) => {
      clock = Date.parse(time);
    },
  };
}

it('returns public plans and server-like month-end projections without granting membership', async () => {
  const s = setup();
  s.session.clear();
  expect(await s.api.membership()).toMatchObject({
    state: 'NONE',
    canDownload: false,
    startsAt: null,
    expiresAt: null,
    plans: [
      {
        productId: 'DEV-member-6',
        durationMonths: 6,
        priceFen: 5900,
        projectedExpiresAt: '2027-02-28T04:00:00.000Z',
      },
      {
        productId: 'DEV-member-12',
        durationMonths: 12,
        priceFen: 9900,
        projectedExpiresAt: '2027-08-31T04:00:00.000Z',
      },
    ],
  });
  s.setTime('2027-08-31T04:00:00Z');
  expect((await s.api.membership()).plans[0]?.projectedExpiresAt).toBe('2028-02-29T04:00:00.000Z');
  s.setTime('2026-08-30T16:30:00Z');
  expect((await s.api.membership()).plans[0]?.projectedExpiresAt).toBe('2027-02-27T16:30:00.000Z');
});

it('paid membership uses the order duration snapshot once and completes only its own entitlement', async () => {
  const s = setup();
  const o = await s.order();
  const product = s.catalog.products.find((p) => p.id === 'DEV-member-6')!;
  if (product.type !== 'MEMBERSHIP') throw new Error('Fixture missing');
  product.membership.durationMonths = 12;
  await s.pay(o.id);
  await s.pay(o.id);
  expect(await s.api.membership()).toMatchObject({
    state: 'ACTIVE',
    canDownload: true,
    startsAt: '2026-08-31T04:00:00.000Z',
    expiresAt: '2027-02-28T04:00:00.000Z',
  });
  expect(await s.client.request<OrderView>(`/orders/${o.id}`)).toMatchObject({
    orderStatus: 'COMPLETED',
    allowedActions: expect.arrayContaining(['VIEW_CONTENT']),
  });
  expect((await s.api.course('DEV-course', 'DEV-course')).entitlement).toBe('NONE');
  s.login('other');
  expect((await s.api.membership()).state).toBe('NONE');
});

it('retains partial compensation but replays the renewal chain after an earlier full refund', async () => {
  const s = setup();
  const first = await s.order();
  await s.pay(first.id);
  s.setTime('2026-09-04T04:00:00Z');
  const second = await s.order('DEV-member-12');
  await s.pay(second.id);
  expect((await s.api.membership()).expiresAt).toBe('2028-02-28T04:00:00.000Z');
  await s.refund(first.id, 1000);
  expect((await s.api.membership()).expiresAt).toBe('2028-02-28T04:00:00.000Z');
  await s.refund(first.id, 4900);
  expect((await s.api.membership()).expiresAt).toBe('2027-09-04T04:00:00.000Z');
  await s.refund(second.id, 9900);
  expect(await s.api.membership()).toMatchObject({ state: 'EXPIRED', canDownload: false });
});

it('reauthorizes all paper formats and caps URL expiry at the membership boundary', async () => {
  const s = setup();
  const o = await s.order();
  await s.pay(o.id);
  const paper = await s.publicApi.paper('DEV-paper');
  expect(paper.attachments.length).toBeGreaterThanOrEqual(3);
  for (const file of paper.attachments) {
    expect(file).toMatchObject({ canPreview: true, canDownload: true });
    for (const action of ['PREVIEW', 'DOWNLOAD'] as const) {
      expect(await s.api.authorize(paper.id, file.id, action)).toMatchObject({
        file: { id: file.id, bytes: file.bytes, mime: file.mime },
        url: `/dev-files/paper-sample.${file.extension.toLowerCase()}`,
        expiresAt: '2026-08-31T04:02:00.000Z',
      });
    }
  }
  s.setTime('2027-02-28T03:59:50Z');
  expect((await s.api.authorize(paper.id, paper.attachments[0]!.id, 'DOWNLOAD')).expiresAt).toBe(
    '2027-02-28T04:00:00.000Z',
  );
  s.setTime('2027-02-28T04:00:00Z');
  expect((await s.api.membership()).state).toBe('EXPIRED');
  await expect(
    s.api.authorize(paper.id, paper.attachments[0]!.id, 'DOWNLOAD'),
  ).rejects.toMatchObject({ code: 'ENTITLEMENT_EXPIRED' });
  expect(
    (await s.publicApi.paper(paper.id)).attachments.every((f) => !f.canDownload && !f.canPreview),
  ).toBe(true);
});

it('denies anonymous, nonmember, wrong-file and unconfigured-file authorization', async () => {
  const s = setup('2026-08-31T04:00:00Z', false);
  const paper = await s.publicApi.paper('DEV-paper');
  expect(paper.attachments.every((f) => !f.canPreview && !f.canDownload)).toBe(true);
  await expect(
    s.api.authorize(paper.id, paper.attachments[0]!.id, 'PREVIEW'),
  ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  s.session.clear();
  await expect(
    s.api.authorize(paper.id, paper.attachments[0]!.id, 'DOWNLOAD'),
  ).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  s.login();
  const o = await s.order();
  await s.pay(o.id);
  await expect(s.api.authorize(paper.id, 'missing', 'DOWNLOAD')).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
  await expect(
    s.api.authorize(paper.id, paper.attachments[0]!.id, 'DOWNLOAD'),
  ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
});

it('filters papers by school stage together with grade year and subject', async () => {
  const s = setup();
  expect(
    (
      await s.publicApi.papers({
        schoolStage: 'PRIMARY',
        grade: '6',
        year: '2026',
        subject: 'CHINESE',
      })
    ).items.map((p) => p.id),
  ).toEqual(['DEV-paper']);
  expect((await s.publicApi.papers({ schoolStage: 'JUNIOR', grade: '6' })).items).toEqual([]);
});
