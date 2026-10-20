// @vitest-environment jsdom
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import List from '../src/pages/order/list.vue';
import Logistics from '../src/pages/order/logistics.vue';
import { session } from '../src/api/session';
const hooks = vi.hoisted(() => ({
  load: [] as Array<(v: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  hide: [] as Array<() => void>,
  pull: [] as Array<() => void>,
  bottom: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (v: Record<string, string>) => void) => hooks.load.push(fn),
  onShow: (fn: () => void) => hooks.show.push(fn),
  onHide: (fn: () => void) => hooks.hide.push(fn),
  onPullDownRefresh: (fn: () => void) => hooks.pull.push(fn),
  onReachBottom: (fn: () => void) => hooks.bottom.push(fn),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api/v1' },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const store = new Map<string, string>();
  return {
    session: createSession({
      get: (k) => store.get(k) ?? null,
      set: (k, v) => {
        store.set(k, v);
      },
      remove: (k) => {
        store.delete(k);
      },
    }),
  };
});
const order = {
  id: 'actual/id',
  orderNo: 'PRIVATE_ORDER',
  orderStatus: 'PAID',
  fulfillmentStatus: 'SHIPPED',
  refundStatus: 'PARTIAL',
  goodsAmountFen: 100,
  freightAmountFen: 0,
  payableAmountFen: 100,
  paidAmountFen: 100,
  item: {
    productId: 'real/course',
    title: '真实商品',
    type: 'COURSE',
    quantity: 1,
    unitPriceFen: 100,
  },
  allowedActions: ['PAY', 'VIEW_CONTENT', 'VIEW_LOGISTICS'],
};
const shipment = {
  carrierCode: 'SERVER_CARRIER',
  trackingNo: 'PRIVATE_TRACKING',
  status: 'SERVER_STATUS',
  shippedAt: null,
  receivedAt: null,
  events: [] as Array<{ occurredAt: string; description: string }>,
};
const wrappers: VueWrapper[] = [];
function login(id = 'a') {
  session.set({
    accessToken: id,
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id, nickname: id, distributor: false },
  });
}
beforeEach(() => {
  hooks.load = [];
  hooks.show = [];
  hooks.hide = [];
  hooks.pull = [];
  hooks.bottom = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', {
    navigateTo: vi.fn(),
    setClipboardData: vi.fn(),
    showToast: vi.fn(),
    stopPullDownRefresh: vi.fn(),
  });
  session.clear();
  login();
});
afterEach(() => {
  vi.useRealTimers();
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function open(component: typeof List | typeof Logistics, route: Record<string, string> = {}) {
  const w = mount(component, { global: { stubs: { BaseNav: true } } });
  wrappers.push(w);
  hooks.load.forEach((fn) => fn(route));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return w;
}
async function click(w: VueWrapper, text: string) {
  const button = w.findAll('button').find((b) => b.text() === text);
  expect(button, text).toBeDefined();
  await button!.trigger('click');
  await flushPromises();
}
it('honors legacy route filters, pages server results, resets tabs and displays independent states', async () => {
  hooks.request
    .mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 21 })
    .mockResolvedValueOnce({
      items: [{ ...order, id: 'second', orderNo: 'SECOND' }],
      page: 2,
      pageSize: 20,
      total: 21,
    })
    .mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 });
  const w = await open(List, { tab: 'SHIPPED' });
  expect(hooks.request).toHaveBeenLastCalledWith('/orders', {
    query: { tab: 'SHIPPING', page: 1, pageSize: 20 },
  });
  expect(w.text()).toContain('部分退款');
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  expect(hooks.request).toHaveBeenLastCalledWith('/orders', {
    query: { tab: 'SHIPPING', page: 2, pageSize: 20 },
  });
  expect(w.text()).toContain('SECOND');
  for (const [label, tab] of [
    ['全部', 'ALL'],
    ['待付款', 'UNPAID'],
    ['待发货', 'UNSHIPPED'],
    ['已完成', 'COMPLETED'],
    ['售后', 'AFTERSALE'],
  ]) {
    await w
      .findAll('.tab-item')
      .find((t) => t.text() === label)!
      .trigger('click');
    await flushPromises();
    expect(hooks.request).toHaveBeenLastCalledWith('/orders', {
      query: { tab, page: 1, pageSize: 20 },
    });
    expect(w.text()).not.toContain('PRIVATE_ORDER');
  }
});
it('navigates actual IDs and gates buttons by allowedActions', async () => {
  hooks.request
    .mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 1 })
    .mockResolvedValue({
      id: order.item.productId,
      type: 'COURSE',
      course: { courseId: 'independent/course' },
    });
  const w = await open(List);
  await click(w, '立即付款');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({ url: '/pages/order/detail?id=actual%2Fid' });
  await click(w, '查看物流');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/order/logistics?orderId=actual%2Fid',
  });
  await click(w, '进入学习');
  expect(uni.navigateTo).toHaveBeenLastCalledWith({
    url: '/pages/course/learn?id=independent%2Fcourse&productId=real%2Fcourse',
  });
  hooks.request.mockResolvedValue({
    items: [{ ...order, allowedActions: [] }],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  const labels = w.findAll('button').map((b) => b.text());
  for (const forbidden of ['立即付款', '查看物流', '进入学习'])
    expect(labels).not.toContain(forbidden);
  expect(uni.showToast).not.toHaveBeenCalled();
});
it('does not navigate to a product ID when course resolution fails and allows an explicit retry', async () => {
  hooks.request
    .mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 1 })
    .mockRejectedValueOnce(new Error('课程查询失败'));
  const w = await open(List);
  await click(w, '进入学习');
  expect(uni.navigateTo).not.toHaveBeenCalled();
  expect(w.text()).toContain('课程查询失败');
  hooks.request.mockResolvedValueOnce({ type: 'COURSE', course: { courseId: '700' } });
  await click(w, '重试进入学习');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=700&productId=real%2Fcourse',
  });
});
it('ignores a resolved course after the viewing account changes', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request
    .mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 1 })
    .mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
  const w = await open(List);
  await w
    .findAll('button')
    .find((b) => b.text() === '进入学习')!
    .trigger('click');
  login('b');
  resolve({ type: 'COURSE', course: { courseId: '700' } });
  await flushPromises();
  expect(uni.navigateTo).not.toHaveBeenCalled();
});
it('discards course lookup on tab change and lets the new list enter learning', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request
    .mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 1 })
    .mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
  const w = await open(List);
  await w
    .findAll('button')
    .find((b) => b.text() === '进入学习')!
    .trigger('click');
  hooks.request.mockResolvedValueOnce({
    items: [{ ...order, id: 'new-order' }],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  await w
    .findAll('.tab-item')
    .find((t) => t.text() === '已完成')!
    .trigger('click');
  await flushPromises();
  resolve({ type: 'COURSE', course: { courseId: 'old-course' } });
  await flushPromises();
  expect(uni.navigateTo).not.toHaveBeenCalled();
  hooks.request.mockResolvedValueOnce({ type: 'COURSE', course: { courseId: 'new-course' } });
  await click(w, '进入学习');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=new-course&productId=real%2Fcourse',
  });
});
it('clears list data on failed refresh and session change, ignoring late previous actor results', async () => {
  hooks.request.mockResolvedValue({ items: [order], page: 1, pageSize: 20, total: 1 });
  const w = await open(List);
  hooks.request.mockRejectedValueOnce(new Error('查询失败'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_ORDER');
  expect(w.text()).toContain('查询失败');
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  await click(w, '重试');
  login('b');
  resolve({ items: [order], page: 1, pageSize: 20, total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_ORDER');
  hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('暂无订单');
});
it('shows waiting shipment, syncing, and only server events with retry', async () => {
  hooks.request.mockResolvedValueOnce(null);
  const w = await open(Logistics, { orderId: 'actual/id' });
  expect(hooks.request).toHaveBeenLastCalledWith('/shipments/actual%2Fid');
  expect(w.text()).toContain('等待发货');
  expect(w.text()).not.toContain('顺丰');
  hooks.request.mockResolvedValueOnce(shipment);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('物流同步中');
  hooks.request.mockRejectedValueOnce(new Error('物流查询失败'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_TRACKING');
  hooks.request.mockResolvedValueOnce({
    ...shipment,
    events: [{ occurredAt: '2026-10-09T23:30:00Z', description: 'server-event' }],
  });
  await click(w, '重试');
  expect(w.text()).toContain('server-event');
  expect(w.text()).toContain('2026-10-10 07:30');
  expect(w.text()).toContain('SERVER_CARRIER');
  await click(w, '复制');
  expect(uni.setClipboardData).toHaveBeenCalledWith(
    expect.objectContaining({ data: 'PRIVATE_TRACKING' }),
  );
});
it('localizes shipment labels without changing tracking numbers or provider events', async () => {
  hooks.request.mockResolvedValue({ ...shipment, carrierCode: 'shunfeng', status: 'SIGNED' });
  const w = await open(Logistics, { orderId: 'actual/id' });
  expect(w.text()).toContain('顺丰速运');
  expect(w.text()).toContain('已签收');
  expect(w.text()).not.toContain('SIGNED');
  expect(w.text()).toContain('PRIVATE_TRACKING');
  hooks.request.mockResolvedValue({ ...shipment, status: 'NEW_PROVIDER_STATE' });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('物流状态待确认');
  expect(w.text()).not.toContain('NEW_PROVIDER_STATE');
  expect(w.text()).toContain('SERVER_CARRIER');
});
it('uses a Chinese error for English failures and Chinese fallbacks for new order states', async () => {
  hooks.request.mockResolvedValue({
    items: [
      {
        ...order,
        orderStatus: 'NEW_ORDER',
        fulfillmentStatus: 'NEW_FULFILLMENT',
        refundStatus: 'NEW_REFUND',
      },
    ],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  const w = await open(List);
  expect(w.text()).toContain('状态待确认');
  expect(w.text()).not.toContain('NEW_ORDER');
  expect(w.text()).not.toContain('NEW_FULFILLMENT');
  expect(w.text()).not.toContain('NEW_REFUND');
  hooks.request.mockRejectedValue(new Error('Internal Server Error'));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(w.text()).toContain('订单加载失败');
  expect(w.text()).not.toContain('Internal Server Error');
});
it('rejects late shipment results after session replacement and preserves login return route', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const w = await open(Logistics, { orderId: 'actual-id' });
  session.clear();
  resolve(shipment);
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_TRACKING');
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/login/index' });
  expect(session.takeReturnTo()).toBe('/pages/order/logistics?orderId=actual-id');
});
it('retries the same failed next page without duplicating the first page', async () => {
  hooks.request.mockResolvedValueOnce({ items: [order], page: 1, pageSize: 20, total: 21 });
  const w = await open(List);
  hooks.request.mockRejectedValueOnce(new Error('分页失败'));
  hooks.bottom.forEach((fn) => fn());
  await flushPromises();
  expect(w.findAll('.order-card')).toHaveLength(1);
  hooks.request.mockResolvedValueOnce({
    items: [{ ...order, id: 'second', orderNo: 'SECOND' }],
    page: 2,
    pageSize: 20,
    total: 21,
  });
  await click(w, '重试');
  expect(hooks.request).toHaveBeenLastCalledWith('/orders', {
    query: { tab: 'ALL', page: 2, pageSize: 20 },
  });
  expect(w.findAll('.order-card')).toHaveLength(2);
  expect(w.text()).toContain('SECOND');
});
it('discards a late former-tab response and late old-session failure', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const w = await open(List);
  hooks.request.mockResolvedValueOnce({ items: [], page: 1, pageSize: 20, total: 0 });
  await w
    .findAll('.tab-item')
    .find((t) => t.text() === '待付款')!
    .trigger('click');
  await flushPromises();
  resolve({ items: [order], page: 1, pageSize: 20, total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_ORDER');
  let reject!: (reason: Error) => void;
  hooks.request.mockImplementationOnce(
    () =>
      new Promise((_r, j) => {
        reject = j;
      }),
  );
  hooks.show.forEach((fn) => fn());
  login('b');
  reject(new Error('PRIVATE_OLD_ACTOR_ERROR'));
  await flushPromises();
  expect(w.text()).not.toContain('PRIVATE_OLD_ACTOR_ERROR');
});
it('does not manufacture tracking events when the server has not assigned tracking', async () => {
  hooks.request.mockResolvedValue({
    ...shipment,
    trackingNo: null,
    events: [{ occurredAt: 'server-time', description: 'server-event' }],
  });
  const w = await open(Logistics, { orderId: 'actual-id' });
  expect(w.text()).toContain('等待发货');
  expect(w.text()).not.toContain('server-event');
  expect(w.findAll('button').some((b) => b.text() === '复制')).toBe(false);
});
it('refreshes a visible timeline on pull-down and after a minute of staying open', async () => {
  vi.useFakeTimers();
  hooks.request.mockResolvedValue({
    ...shipment,
    trackingNo: 'TRACK-1',
    events: [{ occurredAt: '2026-10-19T02:00:00Z', description: '已揽收' }],
  });
  const w = await open(Logistics, { orderId: 'actual-id' });
  expect(w.text()).toContain('TRACK-1');
  // Staying on the page refreshes silently without blanking the timeline first.
  expect(hooks.pull).toHaveLength(1);
  hooks.pull.forEach((fn) => fn());
  await vi.advanceTimersByTimeAsync(0);
  expect(hooks.request).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(hooks.request).toHaveBeenCalledTimes(3);
  expect(w.text()).toContain('TRACK-1');
  // Leaving the page stops the timer.
  hooks.hide.forEach((fn) => fn());
  await vi.advanceTimersByTimeAsync(120_000);
  expect(hooks.request).toHaveBeenCalledTimes(3);
});
