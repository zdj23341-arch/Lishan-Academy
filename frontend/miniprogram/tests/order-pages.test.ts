// @vitest-environment jsdom
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import OrderDetail from '../src/pages/order/detail.vue';
import { session, sessionStorage, operationNamespace } from '../src/api/session';
import { createAftersaleController } from '../../shared/src/commerce/aftersale';
import type { CustomerOrder } from '../../shared/src/commerce/order';
import { orderApi } from '../src/api/order';
import { aftersaleApi } from '../src/api/aftersale';
const hooks = vi.hoisted(() => ({
  load: [] as Array<(v: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (fn: (v: Record<string, string>) => void) => hooks.load.push(fn),
  onShow: (fn: () => void) => hooks.show.push(fn),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api/v1' },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const store = new Map<string, string>();
  const sessionStorage = {
    get: (k: string) => store.get(k) ?? null,
    set: (k: string, v: string) => {
      store.set(k, v);
    },
    remove: (k: string) => {
      store.delete(k);
    },
  };
  return {
    session: createSession(sessionStorage),
    sessionStorage,
    operationNamespace: 'order-pages-test',
  };
});
const order = {
  id: 'o1',
  orderNo: '实际订单号',
  orderStatus: 'PENDING_PAYMENT',
  fulfillmentStatus: 'PENDING_SHIPMENT',
  refundStatus: 'NONE',
  goodsAmountFen: 7980,
  freightAmountFen: 600,
  payableAmountFen: 8580,
  paidAmountFen: 0,
  refundedAmountFen: 0,
  paidAt: null,
  expiresAt: '2099-01-01T00:00:00Z',
  completedAt: null,
  item: {
    productId: 'p1',
    title: '接口图书',
    type: 'BOOK',
    quantity: 2,
    unitPriceFen: 3990,
    coverUrl: null,
  },
  allowedActions: ['PAY', 'CANCEL'],
};
const wrappers: VueWrapper[] = [];
beforeEach(() => {
  hooks.load = [];
  hooks.show = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', {
    navigateTo: vi.fn(),
    setClipboardData: vi.fn(),
    showToast: vi.fn(),
    showModal: vi.fn(({ success }) => success({ confirm: true })),
    requestPayment: vi.fn(),
  });
  session.clear();
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'u', nickname: '测试', distributor: false },
  });
});
afterEach(() => {
  vi.useRealTimers();
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function click(w: VueWrapper, label: string) {
  const button = w.findAll('button').find((b) => b.text() === label);
  expect(button, label).toBeDefined();
  await button!.trigger('click');
  await flushPromises();
}
async function saveRefund(pending = false, orderId = 'o1') {
  const controller = createAftersaleController({
    session,
    storage: sessionStorage,
    namespace: operationNamespace,
    orderId,
    api: aftersaleApi,
    orderApi,
    getOrder: () =>
      ({ ...order, paidAmountFen: 8580, allowedActions: ['APPLY_AFTERSALE'] }) as CustomerOrder,
    keyGenerator: () => '12345678-1234-1234-1234-123456789abc',
  });
  hooks.request.mockImplementation(async () => {
    if (pending) throw new Error('timeout');
    return { id: 'journal/case' };
  });
  await controller.refundOperation
    .submit({
      type: 'REFUND_ONLY',
      reason: '退款',
      evidenceAssetIds: [],
      requestedGoodsFen: 100,
      requestedFreightFen: 0,
    })
    .catch(() => undefined);
  controller.dispose();
  hooks.request.mockReset();
}
it('reopens the server case independently of permission to apply and prefers it over the journal', async () => {
  await saveRefund();
  hooks.request.mockResolvedValue({
    ...order,
    refundStatus: 'REQUESTED',
    aftersaleId: 'server/case',
    allowedActions: [],
  });
  const w = await open();
  await click(w, '查看售后');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/aftersale/detail?id=server%2Fcase' });
  expect(w.findAll('button').some((b) => b.text() === '申请售后')).toBe(false);
});
it('reopens a successful own refund journal when the server omits or invalidates the case ID', async () => {
  await saveRefund();
  hooks.request.mockResolvedValue({
    ...order,
    refundStatus: 'REQUESTED',
    aftersaleId: 123,
    allowedActions: [],
  });
  const w = await open();
  await click(w, '查看售后');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/aftersale/detail?id=journal%2Fcase' });
  session.clear();
  await flushPromises();
  expect(w.findAll('button').some((b) => b.text() === '查看售后')).toBe(false);
});
it('recovers a pending original refund read-only and navigates only after a confirmed case ID', async () => {
  await saveRefund(true);
  let succeeded = false;
  hooks.request.mockImplementation(async (path) =>
    path.startsWith('/operations/')
      ? {
          state: succeeded ? 'SUCCEEDED' : 'PROCESSING',
          resourceId: succeeded ? 'recovered/case' : null,
        }
      : { ...order, refundStatus: 'REQUESTED', allowedActions: [] },
  );
  const w = await open();
  await click(w, '查询原售后申请');
  expect(uni.navigateTo).not.toHaveBeenCalled();
  succeeded = true;
  await click(w, '查询原售后申请');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/aftersale/detail?id=recovered%2Fcase',
  });
  expect(
    hooks.request.mock.calls.every(([, options]) => !options?.method || options.method === 'GET'),
  ).toBe(true);
});
it('does not invent case IDs or use another order journal', async () => {
  await saveRefund(false, 'other-order');
  hooks.request.mockResolvedValue({
    ...order,
    refundStatus: 'REQUESTED',
    aftersaleId: {},
    allowedActions: [],
  });
  const w = await open();
  expect(w.findAll('button').some((b) => ['查看售后', '查询原售后申请'].includes(b.text()))).toBe(
    false,
  );
  expect(uni.navigateTo).not.toHaveBeenCalled();
});
it('finds a refund completed while away from the existing order page', async () => {
  hooks.request.mockResolvedValue(order);
  const w = await open();
  await saveRefund();
  hooks.request.mockResolvedValue({ ...order, refundStatus: 'REQUESTED', allowedActions: [] });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  await click(w, '查看售后');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/aftersale/detail?id=journal%2Fcase' });
});
it('suppresses late refund recovery navigation after an account change', async () => {
  await saveRefund(true);
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation(async (path) =>
    path.startsWith('/operations/')
      ? new Promise((r) => {
          resolve = r;
        })
      : { ...order, refundStatus: 'REQUESTED', allowedActions: [] },
  );
  const w = await open();
  await click(w, '查询原售后申请');
  session.clear();
  resolve({ state: 'SUCCEEDED', resourceId: 'old-actor-case' });
  await flushPromises();
  expect(uni.navigateTo).not.toHaveBeenCalled();
  expect(w.findAll('button').some((b) => b.text() === '查看售后')).toBe(false);
});
it('displays a retained adapter rejection after authoritative polling remains pending', async () => {
  vi.useFakeTimers();
  hooks.request.mockImplementation(async (path) =>
    path === '/payments/wechat/prepay'
      ? {
          provider: 'WECHAT_PAY',
          paymentId: 'payment1',
          state: 'PENDING',
          params: { malformed: true },
        }
      : order,
  );
  const w = await open();
  await click(w, '立即付款');
  await vi.runAllTimersAsync();
  await flushPromises();
  expect(w.get('[role="alert"]').text()).toBe('支付参数不完整，请查询订单状态');
  expect(w.text()).toContain('待付款');
  expect(w.text()).toContain('已完成5次自动查询');
});
it('preserves a thrown authoritative reread error after an adapter rejection', async () => {
  hooks.request.mockResolvedValueOnce(order).mockImplementation(async (path) => {
    if (path === '/payments/wechat/prepay')
      return { provider: 'WECHAT_PAY', paymentId: 'payment1', state: 'PENDING', params: {} };
    throw new Error('订单查询失败');
  });
  const w = await open();
  await click(w, '立即付款');
  expect(w.get('[role="alert"]').text()).toBe('订单查询失败');
});
async function open() {
  const w = mount(OrderDetail);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({ id: 'o1' }));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return w;
}
it('renders zero transaction prices, freight, paid and refunded amounts numerically', async () => {
  hooks.request.mockResolvedValue({
    ...order,
    goodsAmountFen: 0,
    freightAmountFen: 0,
    payableAmountFen: 0,
    item: { ...order.item, unitPriceFen: 0 },
  });
  const w = await open();
  const text = w.text().replace(/\s/g, '');
  for (const label of ['商品总价', '运费', '应付金额', '已付金额', '已退金额'])
    expect(text).toContain(`${label}¥0.00`);
  expect(w.text()).not.toContain('免费');
  expect(w.text()).not.toContain('包邮');
});
it('renders the requested pending order without fictitious shipping or paid amounts', async () => {
  hooks.request.mockResolvedValue(order);
  const w = await open();
  expect(w.text()).toContain('实际订单号');
  expect(w.text()).toContain('待付款');
  expect(w.text()).not.toContain('包裹正在派送');
  expect(w.text()).not.toContain('2026-10-07 10:24');
  expect(w.findAll('button').some((b) => b.text() === '确认收货')).toBe(false);
  expect(w.text()).toContain('应付金额');
  expect(w.text()).toContain('已付金额');
});
it('cancels with a key then displays the reread server result', async () => {
  let cancelled = false;
  hooks.request.mockImplementation(async (path) => {
    if (path === '/orders/o1/cancel') {
      cancelled = true;
      return { ...order, orderStatus: 'CANCELLED', allowedActions: [] };
    }
    return {
      ...order,
      orderStatus: cancelled ? 'CANCELLED' : 'PENDING_PAYMENT',
      allowedActions: cancelled ? [] : order.allowedActions,
    };
  });
  const w = await open();
  await w
    .findAll('button')
    .find((b) => b.text() === '取消订单')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/orders/o1/cancel',
    expect.objectContaining({ method: 'POST', body: {}, idempotencyKey: expect.any(String) }),
  );
  expect(w.text()).toContain('已取消');
});

it('opens purchased course content using the catalog course ID instead of the order product ID', async () => {
  hooks.request.mockImplementation(async (path) =>
    path === '/products/p1'
      ? {
          id: 'p1',
          type: 'COURSE',
          status: 'OFF_SALE',
          allowedActions: ['VIEW_CONTENT'],
          course: { courseId: 'actual/course', entitlement: 'ACTIVE' },
        }
      : {
          ...order,
          item: { ...order.item, type: 'COURSE' },
          orderStatus: 'PAID',
          allowedActions: ['VIEW_CONTENT'],
        },
  );
  const w = await open();
  await click(w, '进入课程学习');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=actual%2Fcourse&productId=p1',
  });
});

it('suppresses a delayed course navigation after the order session changes', async () => {
  let finish!: (value: unknown) => void;
  hooks.request.mockImplementation(async (path) =>
    path === '/products/p1'
      ? new Promise((done) => {
          finish = done;
        })
      : { ...order, item: { ...order.item, type: 'COURSE' }, allowedActions: ['VIEW_CONTENT'] },
  );
  const w = await open();
  await click(w, '进入课程学习');
  session.clear();
  finish({
    id: 'p1',
    type: 'COURSE',
    allowedActions: ['VIEW_CONTENT'],
    course: { courseId: 'actual/course', entitlement: 'ACTIVE' },
  });
  await flushPromises();
  expect(uni.navigateTo).not.toHaveBeenCalled();
});

it('opens a completed course order when the current catalog grants LEARN instead of the order VIEW_CONTENT action', async () => {
  hooks.request.mockImplementation(async (path) => {
    if (path === '/products/p1')
      return {
        id: 'p1',
        type: 'COURSE',
        title: '已购课程',
        coverUrl: null,
        priceFen: 3990,
        originalPriceFen: null,
        status: 'ON_SALE',
        subtitle: null,
        version: 1,
        contentBlocks: [],
        allowedActions: ['LEARN'],
        book: null,
        membership: null,
        course: {
          courseId: 'actual/course',
          entitlement: 'ACTIVE',
          validityType: 'PERMANENT',
          validityDays: null,
          durationMinutes: 60,
          chaptersSummary: 2,
        },
      };
    return {
      ...order,
      item: { ...order.item, type: 'COURSE', quantity: 1 },
      orderStatus: 'COMPLETED',
      paidAmountFen: 8580,
      allowedActions: ['VIEW_CONTENT'],
    };
  });
  const w = await open();
  await click(w, '进入课程学习');
  expect(uni.navigateTo).toHaveBeenCalledWith({
    url: '/pages/course/learn?id=actual%2Fcourse&productId=p1',
  });
  expect(w.find('[role="alert"]').exists()).toBe(false);
});

it('continues checkout payment once after loading the authoritative pending order', async () => {
  hooks.request.mockImplementation(async (path) =>
    path === '/payments/wechat/prepay'
      ? {
          provider: 'WECHAT_PAY',
          paymentId: 'auto-pay',
          state: 'PENDING',
          params: {
            timeStamp: '123',
            nonceStr: 'n',
            package: 'prepay_id=p',
            signType: 'RSA',
            paySign: 's',
          },
        }
      : order,
  );
  const w = mount(OrderDetail);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({ id: 'o1', pay: '1' }));
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(uni.requestPayment).toHaveBeenCalledTimes(1);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(uni.requestPayment).toHaveBeenCalledTimes(1);
});
