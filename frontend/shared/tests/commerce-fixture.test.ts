import { describe, expect, it } from 'vitest';
import { createCommerceApi } from '../src/commerce/api';
import type { AddressWrite } from '../src/commerce/types';
import type { OrderView, Page } from '../src/contracts';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createClient } from '../src/http';
import { createSession } from '../src/session';

const key = (n = 1) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const address: AddressWrite = {
  version: 0,
  name: ' DEV ',
  phone: ' 13800138000 ',
  provinceCode: ' 310000 ',
  cityCode: '310100',
  districtCode: '310101',
  detail: ' DEV address ',
  isDefault: true,
};
function setup() {
  const catalog = createDevCatalogStore();
  let time = Date.parse('2026-10-07T00:00:00Z');
  const transport = createDevCommerceHandler(catalog, { now: () => time });
  const client = createClient({
    baseUrl: '/api/v1',
    transport,
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  return {
    catalog,
    transport,
    client,
    api: createCommerceApi(client),
    advance: () => {
      time += 300000;
    },
  };
}
describe('frontend commerce fixture boundary', () => {
  it('trims addresses, enforces default uniqueness/version and pages/deletes without persistence', async () => {
    const { api } = setup();
    const first = await api.createAddress(address);
    expect(first).toMatchObject({
      name: 'DEV',
      phone: '13800138000',
      provinceCode: '310000',
      version: 1,
    });
    const second = await api.createAddress(address);
    const list = await api.addresses();
    expect(list.items.filter((a) => a.isDefault).map((a) => a.id)).toEqual([second.id]);
    await expect(api.updateAddress(first.id, first)).rejects.toMatchObject({ status: 409 });
    const updated = await api.updateAddress(second.id, { ...second, detail: 'Updated' });
    expect(updated.version).toBe(2);
    expect((await api.addresses(2, 1)).items[0]?.id).toBe(second.id);
    await api.deleteAddress(first.id);
    expect((await api.addresses()).total).toBe(1);
    expect((await setup().api.addresses()).total).toBe(0);
  });
  it('returns field errors for invalid phone/regions and rejects invalid paging', async () => {
    const { api, client } = setup();
    await expect(
      api.createAddress({ ...address, phone: '123', cityCode: '31' }),
    ).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      fieldErrors: { phone: expect.any(String), cityCode: expect.any(String) },
    });
    await expect(client.request('/addresses', { query: { page: 0 } })).rejects.toMatchObject({
      status: 400,
    });
  });
  it('quotes book freight and authoritative stock, digital quantity and availability', async () => {
    const { api, catalog } = setup();
    const a = await api.createAddress(address);
    const q = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id });
    expect(q).toMatchObject({
      expiresAt: '2026-10-07T00:05:00.000Z',
      goodsAmountFen: 7980,
      freightAmountFen: 600,
      payableAmountFen: 8580,
      currency: 'CNY',
      availableStock: 3,
      warnings: [],
      item: { quantity: 2, unitPriceFen: 3990 },
    });
    for (const quantity of [0, 100, 4, 1.5])
      await expect(
        api.preview({ productId: 'DEV-book', quantity, addressId: a.id }),
      ).rejects.toMatchObject({ status: 400 });
    catalog.products.find((p) => p.id === 'DEV-book')!.book!.quantityLimit = 1;
    await expect(
      api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id }),
    ).rejects.toMatchObject({ status: 400 });
    await expect(api.preview({ productId: 'DEV-book', quantity: 1 })).rejects.toMatchObject({
      status: 400,
    });
    await expect(api.preview({ productId: 'DEV-course-off', quantity: 1 })).rejects.toMatchObject({
      status: 400,
    });
    await expect(api.preview({ productId: 'DEV-course', quantity: 2 })).rejects.toMatchObject({
      status: 400,
    });
    await expect(
      api.preview({ productId: 'DEV-course', quantity: 1, addressId: a.id }),
    ).rejects.toMatchObject({ status: 400 });
    expect(await api.preview({ productId: 'DEV-course', quantity: 1 })).toMatchObject({
      freightAmountFen: 0,
      availableStock: null,
    });
    const unsupported = await api.createAddress({ ...address, provinceCode: '810000' });
    await expect(
      api.preview({ productId: 'DEV-book', quantity: 1, addressId: unsupported.id }),
    ).rejects.toMatchObject({ code: 'REGION_NOT_SUPPORTED' });
  });
  it('freezes orders, reserves stock and replays only identical UUID operations', async () => {
    const { api, catalog, client } = setup();
    const a = await api.createAddress(address);
    const quote = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id });
    await expect(api.createOrder({ quoteId: quote.quoteId }, 'invalid')).rejects.toMatchObject({
      status: 400,
    });
    const body = { quoteId: quote.quoteId, attributionToken: null };
    const [order, replay] = await Promise.all([
      api.createOrder(body, key()),
      api.createOrder(body, key()),
    ]);
    expect(replay).toEqual(order);
    expect(order).toMatchObject({
      orderStatus: 'PENDING_PAYMENT',
      paidAmountFen: 0,
      paidAt: null,
      allowedActions: ['PAY', 'CANCEL'],
      freightAmountFen: 600,
    });
    expect(catalog.products.find((p) => p.id === 'DEV-book')!.book!.availableStock).toBe(1);
    await expect(
      api.createOrder({ ...body, attributionToken: 'changed' }, key()),
    ).rejects.toMatchObject({ status: 409 });
    await expect(api.createOrder({ quoteId: quote.quoteId }, key())).rejects.toMatchObject({
      status: 409,
    });
    await expect(api.createOrder(body, key(2))).rejects.toMatchObject({ status: 409 });
    expect(await api.operation(key())).toMatchObject({ state: 'SUCCEEDED', resourceId: order.id });
    await expect(api.operation(key(9))).rejects.toMatchObject({ status: 404 });
    catalog.products.find((p) => p.id === 'DEV-book')!.title = 'Changed';
    expect(await client.request<OrderView>(`/orders/${order.id}`)).toEqual(order);
    expect(
      (
        await client.request<Page<OrderView>>('/orders', {
          query: { tab: 'UNPAID', page: 1, pageSize: 1 },
        })
      ).items,
    ).toEqual([order]);
    expect(
      (await client.request<Page<OrderView>>('/orders', { query: { tab: 'COMPLETED' } })).total,
    ).toBe(0);
  });
  it('rejects a quote invalidated by another stock reservation', async () => {
    const { api } = setup();
    const a = await api.createAddress(address);
    const first = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id });
    const second = await api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id });
    await api.createOrder({ quoteId: first.quoteId }, key());
    await expect(api.createOrder({ quoteId: second.quoteId }, key(2))).rejects.toMatchObject({
      code: 'ORDER_STALE',
    });
    await expect(
      api.preview({ productId: 'DEV-book', quantity: 2, addressId: a.id }),
    ).rejects.toMatchObject({ status: 400 });
  });
  it.each(['product', 'address', 'freight', 'expiry'] as const)(
    'refuses a %s-invalidated quote without creating orders',
    async (cause) => {
      const { api, catalog, advance, client } = setup();
      const a = await api.createAddress(address);
      const q = await api.preview({ productId: 'DEV-book', quantity: 1, addressId: a.id });
      if (cause === 'product') catalog.products.find((p) => p.id === 'DEV-book')!.priceFen++;
      if (cause === 'address') await api.updateAddress(a.id, { ...a, detail: 'New' });
      if (cause === 'freight') await api.updateAddress(a.id, { ...a, provinceCode: '810000' });
      if (cause === 'expiry') advance();
      await expect(api.createOrder({ quoteId: q.quoteId }, key())).rejects.toMatchObject({
        code: cause === 'expiry' ? 'QUOTE_EXPIRED' : 'ORDER_STALE',
      });
      expect((await client.request<Page<OrderView>>('/orders')).total).toBe(0);
    },
  );
  it('returns 404 for routes outside its scope', async () => {
    expect(
      (
        await setup().transport({
          url: '/api/v1/unhandled-development-route',
          method: 'POST',
          headers: {},
        })
      ).status,
    ).toBe(404);
  });
});
