// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import Fulfillment from '../src/views/order/OrderFulfillment.vue';
import { session } from '../src/api/session';
import { ApiError } from '../../shared/src/http';
import { createRouter, createMemoryHistory } from 'vue-router';
import Orders from '../src/views/commerce/Orders.vue';
import OrderDetail from '../src/views/commerce/OrderDetail.vue';
import Home from '../src/views/HomeView.vue';
import { createPinia } from 'pinia';
const homeRequest = vi.hoisted(() => vi.fn());
vi.mock('../src/api/client', () => ({ client: { request: homeRequest } }));
const api = vi.hoisted(() => ({
  orders: vi.fn(),
  order: vi.fn(),
  product: vi.fn(),
  products: vi.fn(),
  inventory: vi.fn(),
  ship: vi.fn(),
  aftersales: vi.fn(),
  previewDecision: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  receiveReturn: vi.fn(),
  operation: vi.fn(),
}));
vi.mock('../src/api/commerce', () => ({ commerceApi: api }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api' },
}));
const wrappers: VueWrapper[] = [];
const order = {
  id: 'server-order',
  orderNo: 'SERVER-9',
  version: 4,
  item: { title: 'Book', quantity: 1 },
  address: { recipientName: '服务器收货人' },
  allowedActions: ['SHIP'],
  orderStatus: 'PAID',
  fulfillmentStatus: 'PENDING_SHIPMENT',
  refundStatus: 'NONE',
  goodsAmountFen: 1000,
  freightAmountFen: 100,
  payableAmountFen: 1100,
};
function login(permissions = ['ORDER_OPERATOR']) {
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    user: { id: 'admin', nickname: 'Admin', distributor: false },
  });
}
beforeEach(() => {
  session.clear();
  sessionStorage.clear();
  vi.resetAllMocks();
  api.orders.mockResolvedValue({
    items: [structuredClone(order)],
    total: 1,
    page: 1,
    pageSize: 20,
  });
  api.order.mockResolvedValue(structuredClone(order));
});
afterEach(() => wrappers.splice(0).forEach((w) => w.unmount()));
it('does not expose invented orders or recipients without an authorized authoritative read', async () => {
  const { w: wrapper } = await page(Fulfillment);
  expect(wrapper.text()).not.toContain('张同学');
  expect(wrapper.text()).not.toContain('SO2026100700192');
  expect(api.ship).not.toHaveBeenCalled();
});
it('uses a BOOK inventory read, retains failed delta and rereads server counters after corrected retry', async () => {
  login(['CONTENT_EDIT', 'ORDER_OPERATE']);
  api.products.mockResolvedValue({
    items: [
      {
        id: 'book1',
        title: 'Book',
        type: 'BOOK',
        book: { availableStock: 9, lockedStock: 2, physicalStock: 11, inventoryVersion: 3 },
      },
    ],
    total: 1,
  });
  api.product.mockResolvedValue({
    id: 'book1',
    title: 'Book',
    type: 'BOOK',
    version: 999,
    book: { availableStock: 9, lockedStock: 2, physicalStock: 11, inventoryVersion: 3 },
  });
  const { default: Inventory } = await import('../src/views/commerce/Inventory.vue');
  const { w } = await page(Inventory);
  await button(w, '调整库存').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('可用库存：9');
  await w.get('[name="delta"]').setValue('3');
  await w.get('[name="reason"]').setValue('盘点');
  api.inventory.mockRejectedValueOnce(new ApiError('VALIDATION_ERROR', '库存不符', null, {}, 422));
  await button(w, '提交调整').trigger('click');
  await flushPromises();
  expect((w.get('[name="delta"]').element as HTMLInputElement).value).toBe('3');
  expect(api.inventory).toHaveBeenCalledWith(
    'book1',
    { delta: 3, reason: '盘点', version: 3 },
    expect.any(String),
  );
  api.inventory.mockResolvedValue({ id: 'book1' });
  api.product.mockResolvedValue({
    id: 'book1',
    title: 'Book',
    type: 'BOOK',
    book: { availableStock: 12, lockedStock: 2, physicalStock: 14, inventoryVersion: 4 },
  });
  await button(w, '更正后重试').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('可用库存：12');
  expect(w.get('tbody').text()).toContain('12');
  expect(api.product).toHaveBeenCalledTimes(2);
});
it.each(['VERSION_CONFLICT', 'INSUFFICIENT_STOCK'])(
  'requires a fresh inventory read after %s and submits the refreshed inventory version',
  async (code) => {
    login(['ORDER_OPERATE']);
    const book = {
      id: 'book1',
      type: 'BOOK',
      title: 'Book',
      version: 999,
      book: { availableStock: 9, lockedStock: 2, physicalStock: 11, inventoryVersion: 3 },
    };
    api.products.mockResolvedValue({ items: [book], total: 1 });
    api.product.mockResolvedValue(book);
    const { default: Inventory } = await import('../src/views/commerce/Inventory.vue');
    const { w } = await page(Inventory);
    await button(w, '调整库存').trigger('click');
    await flushPromises();
    await w.get('[name="delta"]').setValue('1');
    await w.get('[name="reason"]').setValue('盘点');
    api.inventory.mockRejectedValueOnce(new ApiError(code, '库存冲突', null, {}, 409));
    await button(w, '提交调整').trigger('click');
    await flushPromises();
    expect(button(w, '更正后重试').attributes('disabled')).toBeDefined();
    expect(w.text()).toContain('请刷新服务器库存并核对');
    api.product.mockResolvedValue({ ...book, book: { ...book.book, inventoryVersion: 4 } });
    await button(w, '刷新服务器库存').trigger('click');
    await flushPromises();
    api.inventory.mockResolvedValue({ productId: 'book1' });
    await button(w, '更正后重试').trigger('click');
    await flushPromises();
    expect(api.inventory.mock.calls.map((call) => call[1])).toEqual([
      { delta: 1, reason: '盘点', version: 3 },
      { delta: 1, reason: '盘点', version: 4 },
    ]);
    expect(api.inventory.mock.calls[0]![2]).not.toBe(api.inventory.mock.calls[1]![2]);
  },
);
it('invalidates financial preview on every edit and separates finance from warehouse dispatch', async () => {
  login(['FINANCE_OPERATE']);
  const item = {
    id: 'case1',
    orderId: 'server-order',
    status: 'RETURNING',
    type: 'RETURN_REFUND',
    requestedGoodsFen: 1000,
    requestedFreightFen: 100,
    reason: '退货',
    allowedActions: ['APPROVE', 'REJECT', 'RECEIVE_RETURN'],
    permittedReturnQuantity: 1,
  };
  api.aftersales.mockResolvedValue({ items: [item], total: 1 });
  api.previewDecision.mockResolvedValue({
    remainingGoodsFen: 1000,
    remainingFreightFen: 100,
    entitlementImpact: '权益保留',
    commissionImpact: '冻结佣金',
  });
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const { w } = await page(Aftersales);
  await button(w, '选择售后').trigger('click');
  await flushPromises();
  expect(w.find('[name="returnedQuantity"]').exists()).toBe(false);
  await w.get('[name="goodsYuan"]').setValue('10.00');
  await w.get('[name="freightYuan"]').setValue('1.00');
  await w.get('[name="financeReason"]').setValue('审核');
  await button(w, '预览决策').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('冻结佣金');
  await w.get('[name="reviewed"]').setValue(true);
  await w.get('[name="goodsYuan"]').setValue('9.00');
  await w.get('[name="goodsYuan"]').setValue('10.00');
  expect(w.text()).not.toContain('冻结佣金');
  expect(button(w, '批准').attributes('disabled')).toBeDefined();
  expect(api.approve).not.toHaveBeenCalled();
  login(['FINANCE_OPERATE', 'ORDER_OPERATE']);
  await flushPromises();
  expect(w.find('[name="goodsYuan"]').exists()).toBe(false);
  await w.get('form').trigger('submit');
  await flushPromises();
  await button(w, '选择售后').trigger('click');
  await flushPromises();
  expect(w.find('[name="returnedQuantity"]').exists()).toBe(true);
  expect(w.find('[name="financeReason"]').exists()).toBe(true);
  await w.get('[name="returnedQuantity"]').setValue('2');
  await button(w, '确认收到退货').trigger('click');
  await flushPromises();
  expect(api.receiveReturn).not.toHaveBeenCalled();
  api.receiveReturn.mockResolvedValue({ id: 'case1' });
  await w.get('[name="returnedQuantity"]').setValue('1');
  await button(w, '确认收到退货').trigger('click');
  await flushPromises();
  expect(api.receiveReturn).toHaveBeenCalledWith(
    'case1',
    { returnedQuantity: 1 },
    expect.any(String),
  );
  expect(api.aftersales).toHaveBeenCalledWith({ id: 'case1', page: 1, pageSize: 20 });
});
it('rejects a mixed inventory response instead of offering course or membership stock actions', async () => {
  login(['CONTENT_EDIT', 'ORDER_OPERATE']);
  api.products.mockResolvedValue({
    items: [
      { id: 'course1', title: '不属于图书的课程', type: 'COURSE' },
      { id: 'member1', title: '不属于图书的会员', type: 'MEMBERSHIP' },
    ],
    total: 2,
  });
  const { default: Inventory } = await import('../src/views/commerce/Inventory.vue');
  const { w } = await page(Inventory);
  expect(w.findAll('tbody tr')).toHaveLength(0);
  expect(w.text()).toContain('未按图书类型返回');
  expect(api.inventory).not.toHaveBeenCalled();
});
it('permits inspecting unknown book counters but blocks submission until authoritative stock is available', async () => {
  login(['CONTENT_EDIT', 'ORDER_OPERATE']);
  api.products.mockResolvedValue({
    items: [{ id: 'book1', title: 'Book', type: 'BOOK', book: {} }],
    total: 1,
  });
  api.product.mockResolvedValue({ id: 'book1', title: 'Book', type: 'BOOK', book: {} });
  const { default: Inventory } = await import('../src/views/commerce/Inventory.vue');
  const { w } = await page(Inventory);
  await button(w, '查看库存').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('库存数据暂不可用');
  expect(w.get('fieldset').attributes('disabled')).toBeDefined();
  await w.get('[name="delta"]').setValue('3');
  await w.get('[name="reason"]').setValue('盘点');
  await button(w, '提交调整').trigger('click');
  await flushPromises();
  expect(api.inventory).not.toHaveBeenCalled();
});
it('renders the real UTC payment instant as Shanghai time and never shows page 1 of 0', async () => {
  login(['ORDER_OPERATE']);
  api.orders.mockResolvedValue({ items: [{ ...order, paidAt: '2026-10-08T08:26:13Z' }], total: 1 });
  const { w } = await page(Orders);
  expect(w.text()).toContain('2026-10-08 16:26');
  api.orders.mockResolvedValue({ items: [], total: 0 });
  await button(w, '重置').trigger('click');
  await flushPromises();
  expect(w.text()).not.toContain('第 1 / 0 页');
});
it('allows an order-only operator to read inventory and content-only inspection without writes', async () => {
  login(['ORDER_OPERATE']);
  api.products.mockResolvedValue({
    items: [{ id: 'book1', title: 'Book', type: 'BOOK' }],
    total: 1,
  });
  api.product.mockResolvedValue({ id: 'book1', title: 'Book', type: 'BOOK', book: {} });
  const { default: Inventory } = await import('../src/views/commerce/Inventory.vue');
  const { w } = await page(Inventory);
  expect(api.products).toHaveBeenCalledTimes(1);
  expect(w.text()).toContain('Book');
  login(['CONTENT_EDIT']);
  await flushPromises();
  await w.get('form').trigger('submit');
  await flushPromises();
  await button(w, '查看库存').trigger('click');
  await flushPromises();
  expect(api.product).toHaveBeenCalledWith('book1');
  expect(w.get('fieldset').attributes('disabled')).toBeDefined();
  expect(api.inventory).not.toHaveBeenCalled();
});
it('does not request finance-protected aftersales for an order-only warehouse operator', async () => {
  login(['ORDER_OPERATE']);
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const { w } = await page(Aftersales);
  expect(api.aftersales).not.toHaveBeenCalled();
  expect(w.text()).toContain('读取售后单需要财务权限');
});
function button(w: VueWrapper, text: string) {
  const result = w.findAll('button').find((b) => b.text() === text);
  expect(result, `visible button: ${text}`).toBeDefined();
  return result!;
}
async function page(component: object, path = '/page') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component }],
  });
  await router.push(path);
  await router.isReady();
  const w = mount(
    { template: '<router-view />' },
    {
      global: {
        plugins: [router, createPinia()],
        stubs: { ElButton: { template: '<button><slot /></button>' } },
      },
    },
  );
  wrappers.push(w);
  await flushPromises();
  return { w, router };
}
it('ships exact server ID/version, retains conflicting input and requires explicit review before corrected retry', async () => {
  login();
  const { w } = await page(Fulfillment, '/order/fulfillment?id=server-order');
  expect(w.text()).toContain('服务器收货人');
  await w.get('[name="carrierCode"]').setValue('SF');
  await w.get('[name="trackingNo"]').setValue('SF-10');
  await w.get('[name="recipientConfirmed"]').setValue(true);
  api.ship.mockRejectedValueOnce(new ApiError('VERSION_CONFLICT', '版本冲突', null, {}, 409));
  await button(w, '提交物流').trigger('click');
  await flushPromises();
  expect(api.ship).toHaveBeenCalledWith(
    'server-order',
    { carrierCode: 'SF', trackingNo: 'SF-10', version: 4, reason: '' },
    expect.any(String),
  );
  expect((w.get('[name="trackingNo"]').element as HTMLInputElement).value).toBe('SF-10');
  expect(w.text()).toContain('版本冲突');
  api.order.mockResolvedValue({ ...order, version: 5 });
  await button(w, '刷新并重新核对').trigger('click');
  await flushPromises();
  expect((w.get('[name="recipientConfirmed"]').element as HTMLInputElement).checked).toBe(false);
  await w.get('[name="recipientConfirmed"]').setValue(true);
  api.ship.mockResolvedValue({ id: order.id });
  await button(w, '更正后重试').trigger('click');
  await flushPromises();
  expect(api.ship.mock.calls[1]![1].version).toBe(5);
});
it('registers exact role gates for each commerce route and keeps fulfillment reachable', async () => {
  const { router } = await import('../src/router');
  for (const path of ['/commerce/orders', '/commerce/orders/:id', '/order/fulfillment']) {
    expect(router.getRoutes().find((r) => r.path === path)?.meta.permission).toBe('ORDER_OPERATE');
  }
  expect(
    router.getRoutes().find((r) => r.path === '/commerce/inventory')?.meta.permissions,
  ).toEqual(['CONTENT_EDIT', 'ORDER_OPERATE']);
  expect(
    router.getRoutes().find((r) => r.path === '/commerce/aftersales')?.meta.permissions,
  ).toEqual(['FINANCE_OPERATE', 'ORDER_OPERATE']);
});
it('submits every independent filter with explicit ISO bounds and links the actual order ID', async () => {
  login();
  const { w } = await page(Orders);
  for (const [name, value] of Object.entries({
    orderNo: 'SERVER',
    createdFrom: '2026-10-01T10:00',
    createdTo: '2026-10-02T10:00',
    orderStatus: 'PAID',
    fulfillmentStatus: 'SHIPPED',
    refundStatus: 'NONE',
  }))
    await w.get(`[name="${name}"]`).setValue(value);
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(api.orders).toHaveBeenLastCalledWith({
    orderNo: 'SERVER',
    from: new Date('2026-10-01T10:00').toISOString(),
    to: new Date('2026-10-02T10:00').toISOString(),
    orderStatus: 'PAID',
    fulfillmentStatus: 'SHIPPED',
    refundStatus: 'NONE',
    page: 1,
    pageSize: 20,
  });
  expect(w.findAll('a').some((a) => a.attributes('href') === '/commerce/orders/server-order')).toBe(
    true,
  );
});
it('resets the active order tab and rereads all orders, while explicit filters supersede a tab', async () => {
  login();
  const { w } = await page(Orders);
  await button(w, '待发货').trigger('click');
  await flushPromises();
  expect(api.orders).toHaveBeenLastCalledWith(
    expect.objectContaining({ fulfillmentStatus: 'PENDING_SHIPMENT' }),
  );
  await w.get('[name="fulfillmentStatus"]').setValue('SHIPPED');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(api.orders).toHaveBeenLastCalledWith(
    expect.objectContaining({ fulfillmentStatus: 'SHIPPED' }),
  );
  await button(w, '待付款').trigger('click');
  await flushPromises();
  await button(w, '重置').trigger('click');
  await flushPromises();
  const query = api.orders.mock.calls.at(-1)![0];
  expect(query.orderStatus || '').toBe('');
  expect(query.fulfillmentStatus || '').toBe('');
  expect(query.page).toBe(1);
});
it('rejects equal order time bounds before requesting and carries the exact shipment identifier', async () => {
  login();
  const { w } = await page(Orders);
  expect(
    w.findAll('a').some((a) => a.attributes('href') === '/order/fulfillment?id=server-order'),
  ).toBe(true);
  await w.get('[name="createdFrom"]').setValue('2026-10-01T10:00');
  await w.get('[name="createdTo"]').setValue('2026-10-01T10:00');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(api.orders).toHaveBeenCalledTimes(1);
  expect(w.text()).toContain('开始时间必须早于结束时间');
});
it('suppresses a late list response after session replacement and clears the previous filters', async () => {
  login();
  let finish!: (value: unknown) => void;
  api.orders.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w } = await page(Orders);
  session.clear();
  finish({ items: [order], total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain('SERVER-9');
  expect((w.get('[name="orderNo"]').element as HTMLInputElement).value).toBe('');
});
it('suppresses detail and error from an older route while showing the actual new identifier', async () => {
  login();
  let fail!: (error: Error) => void;
  api.order.mockImplementationOnce(
    () =>
      new Promise((_r, reject) => {
        fail = reject;
      }),
  );
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/commerce/orders/:id', component: OrderDetail },
      { path: '/:pathMatch(.*)*', component: { template: '<p />' } },
    ],
  });
  await router.push('/commerce/orders/old');
  const w = mount({ template: '<router-view />' }, { global: { plugins: [router] } });
  wrappers.push(w);
  await flushPromises();
  api.order.mockResolvedValue({ ...order, id: 'new', orderNo: 'NEW' });
  await router.push('/commerce/orders/new');
  await flushPromises();
  fail(new Error('old secret'));
  await flushPromises();
  expect(w.text()).toContain('NEW');
  expect(w.text()).not.toContain('old secret');
  expect(api.order).toHaveBeenLastCalledWith('new');
});
it('drops a late home response after logout', async () => {
  login();
  let finish!: (value: unknown) => void;
  homeRequest.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w } = await page(Home);
  session.clear();
  finish({ revision: 99, banners: [], shortcuts: [], sections: [] });
  await flushPromises();
  expect(w.text()).not.toContain('Rev.99');
});
it('invalidates a pending list request when the same page changes route query', async () => {
  login();
  let finish!: (value: unknown) => void;
  api.orders.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { w, router } = await page(Orders, '/page?a=1');
  await router.push('/page?a=2');
  finish({ items: [order], total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain('SERVER-9');
});
it('restores uncertain finance input on remount and recovers only the original request key', async () => {
  login(['FINANCE_OPERATOR']);
  const item = {
    id: 'case2',
    orderId: 'server-order',
    status: 'REQUESTED',
    type: 'REFUND_ONLY',
    requestedGoodsFen: 1000,
    requestedFreightFen: 100,
    allowedActions: ['APPROVE', 'REJECT'],
  };
  api.aftersales.mockResolvedValue({ items: [item], total: 1 });
  api.previewDecision.mockResolvedValue({
    remainingGoodsFen: 1000,
    remainingFreightFen: 100,
    entitlementImpact: '权益',
    commissionImpact: '佣金',
  });
  api.approve.mockRejectedValue(new Error('offline'));
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const first = await page(Aftersales);
  await button(first.w, '选择售后').trigger('click');
  await flushPromises();
  await first.w.get('[name="goodsYuan"]').setValue('10');
  await first.w.get('[name="freightYuan"]').setValue('0');
  await first.w.get('[name="financeReason"]').setValue('审核理由');
  await button(first.w, '预览决策').trigger('click');
  await flushPromises();
  await first.w.get('[name="reviewed"]').setValue(true);
  await button(first.w, '批准').trigger('click');
  await flushPromises();
  const key = api.approve.mock.calls[0]![2];
  first.w.unmount();
  const { w } = await page(Aftersales);
  await button(w, '选择售后').trigger('click');
  await flushPromises();
  expect((w.get('[name="goodsYuan"]').element as HTMLInputElement).value).toBe('10.00');
  expect(w.text()).toContain('结果待核实');
  api.operation.mockResolvedValue({ state: 'PROCESSING' });
  await button(w, '查询原批准操作').trigger('click');
  await flushPromises();
  expect(api.operation).toHaveBeenCalledWith(key, 'APPROVE_REFUND');
  expect(api.approve).toHaveBeenCalledTimes(1);
});
it('requires deliberate new approval and a fresh review after warehouse receipt', async () => {
  login(['FINANCE_OPERATOR']);
  let item = {
    id: 'case3',
    orderId: 'server-order',
    status: 'REQUESTED',
    type: 'RETURN_REFUND',
    requestedGoodsFen: 1000,
    requestedFreightFen: 100,
    allowedActions: ['APPROVE'],
  };
  api.aftersales.mockImplementation(async () => ({ items: [structuredClone(item)], total: 1 }));
  api.previewDecision.mockResolvedValue({
    remainingGoodsFen: 1000,
    remainingFreightFen: 100,
    entitlementImpact: '权益',
    commissionImpact: '佣金',
  });
  api.approve.mockImplementation(async () => {
    item = { ...item, status: 'RETURN_REQUIRED', allowedActions: [] };
    return { id: item.id };
  });
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const { w } = await page(Aftersales);
  await button(w, '选择售后').trigger('click');
  await flushPromises();
  await w.get('[name="goodsYuan"]').setValue('10');
  await w.get('[name="freightYuan"]').setValue('0');
  await w.get('[name="financeReason"]').setValue('审核');
  await button(w, '预览决策').trigger('click');
  await flushPromises();
  await w.get('[name="reviewed"]').setValue(true);
  await button(w, '批准').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('待寄回商品');
  expect(w.get('tbody').text()).toContain('待寄回商品');
  item = { ...item, status: 'RETURN_RECEIVED', allowedActions: ['APPROVE'] };
  await button(w, '刷新当前售后单').trigger('click');
  await flushPromises();
  expect(api.approve).toHaveBeenCalledTimes(1);
  expect(w.findAll('button').some((b) => b.text() === '批准')).toBe(false);
  await button(w, '明确开始新的批准操作').trigger('click');
  await flushPromises();
  expect(button(w, '批准').attributes('disabled')).toBeDefined();
  await button(w, '预览决策').trigger('click');
  await flushPromises();
  await w.get('[name="reviewed"]').setValue(true);
  await button(w, '批准').trigger('click');
  await flushPromises();
  expect(api.approve).toHaveBeenCalledTimes(2);
  expect(api.approve.mock.calls[1]![2]).not.toBe(api.approve.mock.calls[0]![2]);
});
it('does not continue an old shipment route load under a replacement session', async () => {
  login();
  let finish!: (value: unknown) => void;
  api.orders.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await page(Fulfillment, '/order/fulfillment?id=server-order');
  login();
  finish({ items: [order], total: 1 });
  await flushPromises();
  expect(api.order).not.toHaveBeenCalled();
});
it('selects the exact linked aftersale on mount and when its real route query changes without writes', async () => {
  login(['FINANCE_OPERATOR']);
  const cases = ['approval-case', 'rejection-case'].map((id) => ({
    id,
    orderId: 'server-order',
    status: 'REQUESTED',
    type: 'REFUND_ONLY',
    requestedGoodsFen: 1000,
    requestedFreightFen: 0,
    allowedActions: ['APPROVE', 'REJECT'],
  }));
  api.aftersales.mockImplementation(async (query) => ({
    items: cases.filter((item) => !query.id || item.id === query.id),
    total: query.id ? 1 : 2,
  }));
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/commerce/aftersales', component: Aftersales },
      { path: '/', component: { template: '<p />' } },
    ],
  });
  await router.push('/commerce/aftersales?id=approval-case');
  await router.isReady();
  const w = mount({ template: '<router-view />' }, { global: { plugins: [router] } });
  wrappers.push(w);
  await flushPromises();
  expect(w.find('h2').exists()).toBe(true);
  expect(w.get('h2').text()).toContain('approval-case');
  expect((w.get('form input').element as HTMLInputElement).value).toBe('approval-case');
  expect(w.get('tbody').text()).not.toContain('rejection-case');
  await w.get('[name="financeReason"]').setValue('old input');
  await router.push('/commerce/aftersales?id=rejection-case');
  await flushPromises();
  expect(w.get('h2').text()).toContain('rejection-case');
  expect((w.get('form input').element as HTMLInputElement).value).toBe('rejection-case');
  expect((w.get('[name="financeReason"]').element as HTMLInputElement).value).toBe('');
  expect(api.aftersales).toHaveBeenLastCalledWith({ id: 'rejection-case', page: 1, pageSize: 20 });
  expect(api.approve).not.toHaveBeenCalled();
  expect(api.reject).not.toHaveBeenCalled();
  expect(api.receiveReturn).not.toHaveBeenCalled();
});
it('suppresses an older deep-link response and does not continue it under a replacement actor', async () => {
  login(['FINANCE_OPERATOR']);
  let finish!: (value: unknown) => void;
  const item = {
    id: 'current-case',
    orderId: 'server-order',
    status: 'REQUESTED',
    type: 'REFUND_ONLY',
    requestedGoodsFen: 1000,
    requestedFreightFen: 0,
    allowedActions: ['APPROVE'],
  };
  api.aftersales
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValue({ items: [item], total: 1 });
  const { default: Aftersales } = await import('../src/views/commerce/Aftersales.vue');
  const { w, router } = await page(Aftersales, '/commerce/aftersales?id=old-case');
  await router.push('/commerce/aftersales?id=current-case');
  await flushPromises();
  expect(w.find('h2').exists()).toBe(true);
  expect(w.get('h2').text()).toContain('current-case');
  const reads = api.aftersales.mock.calls.length;
  finish({ items: [{ ...item, id: 'old-case' }], total: 1 });
  await flushPromises();
  expect(w.get('h2').text()).toContain('current-case');
  expect(api.aftersales).toHaveBeenCalledTimes(reads);
  api.aftersales.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await router.push('/commerce/aftersales?id=next-case');
  await flushPromises();
  login(['FINANCE_OPERATOR']);
  const before = api.aftersales.mock.calls.length;
  finish({ items: [{ ...item, id: 'next-case' }], total: 1 });
  await flushPromises();
  expect(w.find('h2').exists()).toBe(false);
  expect(api.aftersales).toHaveBeenCalledTimes(before);
});
it('locks unknown shipment input and only queries the original key', async () => {
  login();
  const { w } = await page(Fulfillment, '/order/fulfillment?id=server-order');
  await w.get('[name="carrierCode"]').setValue('SF');
  await w.get('[name="trackingNo"]').setValue('SF-11');
  await w.get('[name="recipientConfirmed"]').setValue(true);
  api.ship.mockRejectedValue(new Error('offline'));
  await button(w, '提交物流').trigger('click');
  await flushPromises();
  expect(w.get('[data-shipment-form]').attributes('disabled')).toBeDefined();
  const key = api.ship.mock.calls[0]![2];
  api.operation.mockResolvedValue({ state: 'PROCESSING' });
  await button(w, '查询原操作').trigger('click');
  await flushPromises();
  expect(api.operation).toHaveBeenCalledWith(key, 'SHIP_ORDER');
  expect(api.ship).toHaveBeenCalledTimes(1);
});
