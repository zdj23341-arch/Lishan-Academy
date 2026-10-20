import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createCommerceApi } from '../src/commerce/api';
import type { AftersaleView } from '../src/commerce/aftersale';

async function setup(outcome: 'PENDING' | 'SUCCESS' | 'FAILURE' = 'PENDING') {
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    transport: createDevCommerceHandler(catalog, { refundOutcome: outcome }),
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  let sequence = 0;
  const key = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
  const post = <T>(path: string, body: unknown, idempotencyKey = key()) =>
    client.request<T>(path, { method: 'POST', body, idempotencyKey });
  const api = createCommerceApi(client);
  const address = await api.createAddress({
    version: 0,
    name: 'DEV',
    phone: '13800000000',
    provinceCode: '440000',
    cityCode: '440100',
    districtCode: '440106',
    detail: '虚构地址',
    isDefault: false,
  });
  const quote = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: address.id });
  const order = await api.createOrder({ quoteId: quote.quoteId }, key());
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' });
  const ship = () =>
    post(`/admin/orders/${order.id}/ship`, { carrierCode: 'SF', trackingNo: 'SF123', version: 2 });
  const refund = (type = 'REFUND_ONLY') =>
    post<AftersaleView>(`/orders/${order.id}/refund`, {
      type,
      reason: '前端退款场景',
      evidenceAssetIds: [],
      requestedGoodsFen: 3990,
      requestedFreightFen: 0,
    });
  const decision = { goodsRefundFen: 3990, freightRefundFen: 0, reason: '核对申请' };
  return { client, post, key, catalog, order, ship, refund, decision };
}

it('preview is read-only and rejection publishes its reason without refunding money', async () => {
  const { client, post, order, refund, decision } = await setup();
  const c = await refund();
  expect(await post(`/admin/aftersales/${c.id}/preview-decision`, decision)).toMatchObject({
    remainingGoodsFen: 7980,
    remainingFreightFen: 600,
    entitlementImpact: expect.any(String),
    commissionImpact: expect.any(String),
  });
  expect(await client.request(`/aftersales/${c.id}`)).toMatchObject({ status: 'REQUESTED' });
  await expect(post(`/admin/aftersales/${c.id}/reject`, { reason: '' })).rejects.toMatchObject({
    code: 'VALIDATION_ERROR',
  });
  await post(`/admin/aftersales/${c.id}/reject`, { reason: '资料需补充' });
  expect(await client.request(`/aftersales/${c.id}`)).toMatchObject({
    status: 'REJECTED',
    rejectionReason: '资料需补充',
  });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    refundStatus: 'REJECTED',
    refundedAmountFen: 0,
  });
  expect(
    await client.request('/admin/aftersales', {
      query: { id: c.id, status: 'REJECTED', orderNo: order.orderNo },
    }),
  ).toMatchObject({ total: 1, items: [{ id: c.id }] });
  expect(await client.request('/admin/aftersales', { query: { id: 'missing' } })).toMatchObject({
    total: 0,
  });
});

it('return approval, shipment, actual warehouse quantity and second approval form one idempotent lifecycle', async () => {
  const { client, post, key, catalog, order, ship, refund, decision } = await setup('SUCCESS');
  await ship();
  const c = await refund('RETURN_REFUND');
  const firstApprovalKey = key();
  expect(await post(`/admin/aftersales/${c.id}/approve`, decision, firstApprovalKey)).toMatchObject(
    { status: 'RETURN_REQUIRED', returnAddress: expect.any(String), returnedQuantity: null },
  );
  expect(await client.request(`/aftersales/${c.id}`)).toMatchObject({
    allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
  });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({ refundedAmountFen: 0 });
  await post(`/aftersales/${c.id}/return-shipment`, { carrierCode: 'SF', trackingNo: 'RETURN123' });
  const receiveKey = key();
  await expect(
    post(`/admin/aftersales/${c.id}/receive-return`, { returnedQuantity: 3 }),
  ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  await post(`/admin/aftersales/${c.id}/receive-return`, { returnedQuantity: 1 }, receiveKey);
  await post(`/admin/aftersales/${c.id}/receive-return`, { returnedQuantity: 1 }, receiveKey);
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(2);
  expect(await client.request('/admin/aftersales', { query: { id: c.id } })).toMatchObject({
    items: [{ status: 'RETURN_RECEIVED', receivedQuantity: 1, allowedActions: ['APPROVE'] }],
  });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({ refundedAmountFen: 0 });
  const finalKey = key();
  await post(`/admin/aftersales/${c.id}/approve`, decision, finalKey);
  await post(`/admin/aftersales/${c.id}/approve`, decision, finalKey);
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    refundedAmountFen: 3990,
    refundStatus: 'PARTIAL',
  });
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(2);
  // Replaying the original return approval must not re-open the completed case.
  await post(`/admin/aftersales/${c.id}/approve`, decision, firstApprovalKey);
  expect(await client.request(`/aftersales/${c.id}`)).toMatchObject({
    status: 'PARTIAL',
    receivedQuantity: 1,
  });
});

it('default approval remains processing and cannot be applied twice with another key', async () => {
  const { client, post, order, refund, decision, catalog } = await setup();
  const c = await refund();
  expect(await post(`/admin/aftersales/${c.id}/approve`, decision)).toMatchObject({
    status: 'PROCESSING',
  });
  await expect(post(`/admin/aftersales/${c.id}/approve`, decision)).rejects.toMatchObject({
    code: 'ACTION_NOT_ALLOWED',
  });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    refundedAmountFen: 0,
    refundStatus: 'PROCESSING',
  });
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(1);
});

it.each([true, false])(
  'explicit failure retains money and publishes reason (shipped=%s)',
  async (shipped) => {
    const { client, post, order, ship, refund, decision } = await setup('FAILURE');
    if (shipped) await ship();
    const c = await refund();
    await post(`/admin/aftersales/${c.id}/approve`, decision);
    expect(await client.request(`/aftersales/${c.id}`)).toMatchObject({
      status: 'FAILED',
      failureReason: expect.stringContaining('DEV'),
    });
    expect(await client.request(`/orders/${order.id}`)).toMatchObject({
      refundedAmountFen: 0,
      refundStatus: 'FAILED',
    });
  },
);

it('explicit compensation successes use remaining budgets and never infer returned stock from money', async () => {
  const { client, post, order, ship, refund, decision, catalog } = await setup('SUCCESS');
  await ship();
  const first = await refund();
  await post(`/admin/aftersales/${first.id}/approve`, decision);
  const second = await refund();
  await expect(
    post(`/admin/aftersales/${second.id}/approve`, { ...decision, goodsRefundFen: 7980 }),
  ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  expect(await post(`/admin/aftersales/${second.id}/preview-decision`, decision)).toMatchObject({
    remainingGoodsFen: 3990,
    remainingFreightFen: 600,
  });
  await post(`/admin/aftersales/${second.id}/approve`, { ...decision, freightRefundFen: 600 });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    refundedAmountFen: 8580,
    refundStatus: 'FULL',
  });
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(1);
});
