import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createCommerceApi } from '../src/commerce/api';
import type { OrderView } from '../src/contracts';
import type { PrepayView } from '../src/commerce/payment';
import type { AftersaleView } from '../src/commerce/aftersale';
import type { UploadTicket } from '../src/content/admin-types';
const key = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
async function setup() {
  const catalog = createDevCatalogStore();
  const transport = createDevCommerceHandler(catalog);
  const client = createClient({
    baseUrl: '/api/v1',
    transport,
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  const api = createCommerceApi(client);
  const a = await api.createAddress({
    version: 0,
    name: 'DEV收件人',
    phone: '13812345678',
    provinceCode: '110000',
    cityCode: '110100',
    districtCode: '110108',
    detail: 'DEV地址',
    isDefault: true,
  });
  const quote = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id });
  const order = await api.createOrder({ quoteId: quote.quoteId }, key(1));
  const post = <T>(path: string, body: unknown, n: number) =>
    client.request<T>(path, { method: 'POST', body, idempotencyKey: key(n) });
  return { catalog, client, api, order, post, transport };
}
it('prepay is explicitly simulated and never marks the order paid; timeout stays unpaid', async () => {
  const { order, post, client } = await setup();
  const payment = await post<PrepayView>('/payments/wechat/prepay', { orderId: order.id }, 2);
  expect(payment).toMatchObject({ provider: 'DEV', simulated: true });
  expect(await post('/payments/wechat/prepay', { orderId: order.id }, 2)).toEqual(payment);
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'TIMEOUT' }, 3);
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    orderStatus: 'PENDING_PAYMENT',
    paidAmountFen: 0,
  });
  await expect(post(`/orders/${order.id}/cancel`, {}, 4)).rejects.toMatchObject({
    code: 'PAYMENT_UNKNOWN',
  });
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 5);
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    orderStatus: 'PAID',
    paidAmountFen: 8580,
    fulfillmentStatus: 'PENDING_SHIPMENT',
  });
  expect(
    await client.request(`/operations/${key(5)}`, { query: { scope: 'DEV_PAY' } }),
  ).toMatchObject({ state: 'SUCCEEDED', resourceId: order.id });
});
it('cancellation replays once and releases reservation once without permitting receipt', async () => {
  const { order, post, catalog } = await setup();
  await expect(post(`/orders/${order.id}/confirm-receipt`, {}, 2)).rejects.toMatchObject({
    code: 'ACTION_NOT_ALLOWED',
  });
  expect(await post(`/orders/${order.id}/cancel`, {}, 3)).toMatchObject({
    orderStatus: 'CANCELLED',
  });
  await post(`/orders/${order.id}/cancel`, {}, 3);
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(3);
  await expect(post('/payments/wechat/prepay', { orderId: order.id }, 4)).rejects.toMatchObject({
    code: 'ACTION_NOT_ALLOWED',
  });
});
it('paid orders filter correctly and expose an empty awaiting-shipment response', async () => {
  const { order, post, client } = await setup();
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  expect(await client.request('/orders', { query: { tab: 'UNPAID' } })).toMatchObject({ total: 0 });
  expect(await client.request('/orders', { query: { tab: 'UNSHIPPED' } })).toMatchObject({
    total: 1,
  });
  expect(await client.request(`/shipments/${order.id}`)).toMatchObject({
    trackingNo: null,
    events: [],
    simulated: true,
  });
  const detail = await client.request<OrderView & { address: { name: string } }>(
    `/orders/${order.id}`,
  );
  expect(detail.address.name).toBe('DEV收件人');
});
it('refund application is not refund success and duplicate replay preserves one case', async () => {
  const { order, post, client } = await setup();
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  const body = {
    type: 'REFUND_ONLY',
    reason: '不再需要',
    evidenceAssetIds: [],
    requestedGoodsFen: 3990,
    requestedFreightFen: 0,
  };
  const caseView = await post<AftersaleView>(`/orders/${order.id}/refund`, body, 3);
  expect(caseView.status).toBe('REQUESTED');
  expect(await post(`/orders/${order.id}/refund`, body, 3)).toEqual(caseView);
  await expect(
    post(`/orders/${order.id}/refund`, { ...body, reason: '不同内容' }, 3),
  ).rejects.toMatchObject({ code: 'IDEMPOTENCY_CONFLICT' });
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    refundStatus: 'REQUESTED',
    refundedAmountFen: 0,
  });
  expect(await client.request(`/aftersales/${caseView.id}`)).toMatchObject({
    id: caseView.id,
    reason: body.reason,
  });
  await expect(
    post(
      `/aftersales/${caseView.id}/return-shipment`,
      { carrierCode: 'SF', trackingNo: 'SF123' },
      4,
    ),
  ).rejects.toMatchObject({ code: 'ACTION_NOT_ALLOWED' });
});

it('requires completed private evidence upload before binding its ID to an aftersale', async () => {
  const { order, post, client, transport } = await setup();
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  const ticket = await client.request<UploadTicket>('/files/presign', {
    method: 'POST',
    body: { name: 'evidence.jpg', mime: 'image/jpeg', bytes: 3, purpose: 'AFTERSALE_EVIDENCE' },
  });
  const refund = {
    type: 'REFUND_ONLY',
    reason: '图片说明',
    evidenceAssetIds: [ticket.id],
    requestedGoodsFen: 3990,
    requestedFreightFen: 0,
  };
  await expect(post(`/orders/${order.id}/refund`, refund, 3)).rejects.toMatchObject({
    code: 'FILE_NOT_READY',
  });
  await expect(
    client.request(`/files/${ticket.id}/complete`, { method: 'POST', body: {} }),
  ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
  expect(
    (
      await transport({
        url: ticket.uploadUrl,
        method: 'PUT',
        body: new Uint8Array([255, 216, 255]).buffer,
        headers: { 'X-Upload-Token': ticket.uploadToken },
      })
    ).status,
  ).toBe(200);
  expect(
    await client.request(`/files/${ticket.id}/complete`, { method: 'POST', body: {} }),
  ).toMatchObject({ purpose: 'AFTERSALE_EVIDENCE', status: 'READY', publicUrl: null });
  expect(await post(`/orders/${order.id}/refund`, refund, 4)).toMatchObject({
    evidenceAssetIds: [ticket.id],
    status: 'REQUESTED',
  });
});
