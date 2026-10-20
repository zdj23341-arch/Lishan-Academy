import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevLearningHandler } from '../src/dev/learning';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createDevTransport } from '../src/dev/transport';
import { createCatalogApi } from '../src/content/api';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createCommerceApi } from '../src/commerce/api';
import { createLearningApi } from '../src/learning/api';
import type { AftersaleView } from '../src/commerce/aftersale';
import type { OrderView, Page } from '../src/contracts';

function setup() {
  let now = Date.parse('2026-10-07T00:00:00Z');
  const catalog = createDevCatalogStore();
  const learning = createDevLearningHandler(catalog, {
    now: () => now,
    mediaUrl: 'http://127.0.0.1:5173/course-sample.mp4',
  });
  const commerce = createDevCommerceHandler(catalog, {
    now: () => now,
    refundOutcome: 'SUCCESS',
    content: learning.content,
  });
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.set({
    accessToken: 'DEV-purchase-buyer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'buyer', nickname: 'DEV', distributor: false },
  });
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(
      catalog,
      async (request) => {
        const result = await learning.transport(request);
        return result.status === 404 ? commerce(request) : result;
      },
      { content: learning.content },
    ),
  });
  let sequence = 0;
  const key = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
  const api = createCommerceApi(client);
  const courseApi = createLearningApi(client);
  async function order(productId = 'DEV-course') {
    const quote = await api.preview({ productId, quantity: 1 });
    return api.createOrder({ quoteId: quote.quoteId }, key());
  }
  const pay = (id: string) =>
    client.request(`/dev/orders/${id}/pay`, {
      method: 'POST',
      body: { scenario: 'SUCCESS' },
      idempotencyKey: key(),
    });
  async function refund(id: string, goodsRefundFen: number) {
    const c = await client.request<AftersaleView>(`/orders/${id}/refund`, {
      method: 'POST',
      body: {
        type: 'REFUND_ONLY',
        reason: 'DEV课程测试',
        evidenceAssetIds: [],
        requestedGoodsFen: goodsRefundFen,
        requestedFreightFen: 0,
      },
      idempotencyKey: key(),
    });
    await client.request(`/admin/aftersales/${c.id}/approve`, {
      method: 'POST',
      body: { goodsRefundFen, freightRefundFen: 0, reason: 'DEV核对' },
      idempotencyKey: key(),
    });
  }
  return {
    catalog,
    learning,
    api,
    courseApi,
    order,
    pay,
    refund,
    getOrder: (id: string) => client.request<OrderView>(`/orders/${id}`),
    listOrders: () => client.request<Page<OrderView>>('/orders'),
    getProduct: createCatalogApi(client).product,
    advance: (ms: number) => {
      now += ms;
    },
  };
}

it('DEV payment grants its course snapshot once and blocks a new purchase while active', async () => {
  const s = setup();
  const order = await s.order();
  const product = s.catalog.products.find((p) => p.id === 'DEV-course')!;
  if (product.type !== 'COURSE') throw new Error('Fixture course missing');
  product.course.validityDays = 1;
  await s.pay(order.id);
  await s.pay(order.id);
  expect(await s.courseApi.course('DEV-course', 'DEV-course')).toMatchObject({
    entitlement: 'ACTIVE',
    expiresAt: '2027-10-07T00:00:00.000Z',
  });
  expect(await s.getOrder(order.id)).toMatchObject({
    orderStatus: 'COMPLETED',
    allowedActions: expect.arrayContaining(['VIEW_CONTENT']),
  });
  await expect(s.order()).rejects.toMatchObject({ code: 'PRODUCT_UNAVAILABLE' });
  expect((await s.getProduct(product.id)).allowedActions).not.toContain('BUY');
});

it('partial compensation preserves access, full refund revokes only the source order grant', async () => {
  const s = setup();
  const order = await s.order();
  await s.pay(order.id);
  await s.refund(order.id, 1000);
  expect((await s.courseApi.course('DEV-course', 'DEV-course')).entitlement).toBe('ACTIVE');
  await s.refund(order.id, 8900);
  expect((await s.courseApi.course('DEV-course', 'DEV-course')).entitlement).toBe('EXPIRED');
  expect((await s.getOrder(order.id)).allowedActions).not.toContain('VIEW_CONTENT');
  const next = await s.order();
  await s.pay(next.id);
  expect((await s.courseApi.course('DEV-course', 'DEV-course')).entitlement).toBe('ACTIVE');
});

it('a membership payment does not unlock a separately purchased course', async () => {
  const s = setup();
  const membership = await s.order('DEV-member-6');
  await s.pay(membership.id);
  expect((await s.courseApi.course('DEV-course', 'DEV-course')).entitlement).toBe('NONE');
});

it('refunding an expired prior purchase retains a later purchase entitlement', async () => {
  const s = setup();
  const product = s.catalog.products.find((p) => p.id === 'DEV-course')!;
  if (product.type !== 'COURSE') throw new Error('Fixture course missing');
  product.course.validityDays = 1;
  const oldOrder = await s.order();
  await s.pay(oldOrder.id);
  s.advance(86_400_000);
  const newOrder = await s.order();
  await s.pay(newOrder.id);
  await s.refund(oldOrder.id, 9900);
  expect(await s.courseApi.course('DEV-course', 'DEV-course')).toMatchObject({
    entitlement: 'ACTIVE',
    expiresAt: '2026-10-09T00:00:00.000Z',
  });
});

it('order list refreshes learning actions when entitlement expires or is restored', async () => {
  const s = setup();
  const product = s.catalog.products.find((p) => p.id === 'DEV-course')!;
  if (product.type !== 'COURSE') throw new Error('Fixture course missing');
  product.course.validityDays = 1;
  const order = await s.order();
  await s.pay(order.id);
  expect((await s.listOrders()).items[0]?.allowedActions).toContain('VIEW_CONTENT');
  s.advance(86_400_000);
  expect((await s.listOrders()).items[0]?.allowedActions).not.toContain('VIEW_CONTENT');
  expect((await s.courseApi.course('DEV-course', 'DEV-course')).entitlement).toBe('EXPIRED');
  await expect(s.courseApi.playUrl('DEV-course', 'DEV-course-paid')).rejects.toMatchObject({
    code: 'ENTITLEMENT_EXPIRED',
  });
  s.learning.grantCourse('DEV-purchase-buyer', 'DEV-course', null);
  expect((await s.listOrders()).items[0]?.allowedActions).toContain('VIEW_CONTENT');
});
