import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createDevAdminHandler } from '../src/dev/content-admin';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createCommerceApi } from '../src/commerce/api';
import type { CustomerOrder, ShipmentView } from '../src/commerce/order';
import { createAdminCommerceApi } from '../src/commerce/admin-api';
import {
  createAdminInventoryController,
  createAdminShipmentController,
} from '../src/commerce/admin';

const key = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
async function setup() {
  const catalog = createDevCatalogStore();
  const commerce = createDevCommerceHandler(catalog);
  const content = createDevAdminHandler(catalog);
  const values = new Map<string, string>();
  const storage = {
    get: (k: string) => values.get(k) ?? null,
    set: (k: string, v: string) => {
      values.set(k, v);
    },
    remove: (k: string) => {
      values.delete(k);
    },
  };
  const session = createSession(storage);
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: async (request) => {
      const result = await commerce(request);
      return result.status === 404 ? content(request) : result;
    },
  });
  const api = createCommerceApi(client);
  const address = await api.createAddress({
    version: 0,
    name: 'DEV收件人',
    phone: '13800000000',
    provinceCode: '440000',
    cityCode: '440100',
    districtCode: '440106',
    detail: '虚构测试地址',
    isDefault: false,
  });
  const quote = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: address.id });
  const order = await api.createOrder({ quoteId: quote.quoteId }, key(1));
  const post = <T>(path: string, body: unknown, n: number) =>
    client.request<T>(path, { method: 'POST', body, idempotencyKey: key(n) });
  return { client, catalog, order, post, session, storage };
}

it('shared admin controllers complete shipment but refuse stock adjustment when the composed fixture lacks authoritative counters', async () => {
  const { client, order, post, session, storage } = await setup();
  session.set({
    accessToken: 'DEV-admin',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'DEV-admin', nickname: 'DEV管理员', distributor: false },
    permissions: ['ORDER_OPERATOR'],
  });
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  const api = createAdminCommerceApi(client);
  let current = await api.order(order.id);
  let n = 20;
  const common = {
    api,
    session,
    storage,
    namespace: 'admin-fixture-integration',
    keyGenerator: () => key(n++),
  };
  const shipping = createAdminShipmentController({
    ...common,
    orderId: order.id,
    getOrder: () => current,
    onOrder: (value) => {
      current = value;
    },
  });
  await shipping.submit({ carrierCode: 'SF', trackingNo: 'SF123', version: current.version! });
  expect(current).toMatchObject({
    fulfillmentStatus: 'SHIPPED',
    shipment: { trackingNo: 'SF123' },
  });
  shipping.beginNew();
  await shipping.submit({
    carrierCode: 'ZTO',
    trackingNo: 'ZTO456',
    version: current.version!,
    reason: '更正物流',
  });
  expect(await client.request(`/shipments/${order.id}`)).toMatchObject({
    carrierCode: 'ZTO',
    trackingNo: 'ZTO456',
  });
  let product = await api.product('DEV-book');
  const inventory = createAdminInventoryController({
    ...common,
    productId: product.id,
    getProduct: () => product,
    onProduct: (value) => {
      product = value;
    },
  });
  await expect(inventory.submit({ delta: 4, reason: '补货' })).rejects.toMatchObject({
    code: 'INVENTORY_UNAVAILABLE',
  });
  expect(product.book?.availableStock).toBe(1);
  expect(inventory.operation.state.status).toBe('IDLE');
  shipping.dispose();
  inventory.dispose();
});

it('admin shipment publishes the entered tracking to customer logistics and replays only once', async () => {
  const { client, order, post, catalog } = await setup();
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  const current = await client.request<CustomerOrder>(`/admin/orders/${order.id}`);
  expect(current).toMatchObject({
    version: 2,
    address: { name: 'DEV收件人' },
    allowedActions: ['SHIP'],
  });
  const body = { carrierCode: 'SF', trackingNo: 'SF-12345', version: 2 };
  const saved = await post<CustomerOrder>(`/admin/orders/${order.id}/ship`, body, 3);
  expect(saved).toMatchObject({ version: 3, fulfillmentStatus: 'SHIPPED' });
  expect(await post(`/admin/orders/${order.id}/ship`, body, 3)).toEqual(saved);
  expect(await client.request<ShipmentView>(`/shipments/${order.id}`)).toMatchObject({
    carrierCode: 'SF',
    trackingNo: 'SF-12345',
    shippedQuantity: 2,
    events: [],
    simulated: true,
  });
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(1);
  expect(await client.request(`/orders/${order.id}`)).toMatchObject({
    allowedActions: expect.arrayContaining(['CONFIRM_RECEIPT']),
  });
  await post(`/orders/${order.id}/confirm-receipt`, {}, 4);
  expect(await client.request(`/shipments/${order.id}`)).toMatchObject({
    status: 'RECEIVED',
    receivedAt: expect.any(String),
  });
});

it('shipment refuses unpaid orders, stale versions, edits without reason and active aftersale', async () => {
  const { client, order, post } = await setup();
  const ship = { carrierCode: 'SF', trackingNo: 'SF123', version: 1 };
  await expect(post(`/admin/orders/${order.id}/ship`, ship, 2)).rejects.toMatchObject({
    code: 'ACTION_NOT_ALLOWED',
  });
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 3);
  await expect(post(`/admin/orders/${order.id}/ship`, ship, 4)).rejects.toMatchObject({
    code: 'VERSION_CONFLICT',
  });
  await post(`/admin/orders/${order.id}/ship`, { ...ship, version: 2 }, 5);
  await expect(
    post(`/admin/orders/${order.id}/ship`, { ...ship, trackingNo: 'SF456', version: 3 }, 6),
  ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  await post(
    `/admin/orders/${order.id}/ship`,
    { ...ship, trackingNo: 'SF456', version: 3, reason: '修正录入错误' },
    7,
  );
  expect(await client.request(`/shipments/${order.id}`)).toMatchObject({ trackingNo: 'SF456' });
  await post(
    `/orders/${order.id}/refund`,
    {
      type: 'RETURN_REFUND',
      reason: '退货',
      evidenceAssetIds: [],
      requestedGoodsFen: 7980,
      requestedFreightFen: 0,
    },
    8,
  );
  await expect(
    post(`/admin/orders/${order.id}/ship`, { ...ship, version: 5, reason: '再次修改' }, 9),
  ).rejects.toMatchObject({ code: 'ACTION_NOT_ALLOWED' });
});

it('admin order filters use independent states and preserve authoritative address data', async () => {
  const { client, order, post } = await setup();
  expect(
    await client.request('/admin/orders', {
      query: { orderNo: order.orderNo, orderStatus: 'PENDING_PAYMENT' },
    }),
  ).toMatchObject({ total: 1, items: [{ id: order.id }] });
  expect(
    await client.request('/admin/orders', { query: { fulfillmentStatus: 'SHIPPED' } }),
  ).toMatchObject({ total: 0 });
  await post(`/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' }, 2);
  await post(
    `/admin/orders/${order.id}/ship`,
    { carrierCode: 'SF', trackingNo: 'SF123', version: 2 },
    3,
  );
  expect(
    await client.request('/admin/orders', {
      query: {
        orderStatus: 'PAID',
        fulfillmentStatus: 'SHIPPED',
        refundStatus: 'NONE',
        page: 1,
        pageSize: 1,
      },
    }),
  ).toMatchObject({ total: 1, items: [{ address: { districtCode: '440106' } }] });
});

it('inventory adjustment changes only free stock and a replay cannot double-adjust it', async () => {
  const { client, post, catalog } = await setup();
  const path = '/admin/products/DEV-book/inventory-adjustments';
  await expect(post(path, { delta: -2, reason: '盘点' }, 2)).rejects.toMatchObject({
    code: 'INSUFFICIENT_STOCK',
  });
  await expect(post(path, { delta: 1, reason: '' }, 3)).rejects.toMatchObject({
    code: 'VALIDATION_ERROR',
  });
  const adjustment = { delta: 5, reason: '补货' };
  expect(await post(path, adjustment, 4)).toEqual({ id: 'DEV-book' });
  await post(path, adjustment, 4);
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(6);
  expect(await client.request('/admin/products/DEV-book')).toMatchObject({
    book: { availableStock: 6 },
    allowedActions: ['ADJUST_INVENTORY'],
  });
  await expect(post(path, { delta: 6, reason: '补货' }, 4)).rejects.toMatchObject({
    code: 'IDEMPOTENCY_CONFLICT',
  });
});
