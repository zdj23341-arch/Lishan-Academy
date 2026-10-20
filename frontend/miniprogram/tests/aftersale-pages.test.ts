// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ApplyPage from '../src/pages/aftersale/apply.vue';
import DetailPage from '../src/pages/aftersale/detail.vue';
import EvidenceUpload from '../src/components/EvidenceUpload.vue';
import { session } from '../src/api/session';
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
  environment: {
    mode: 'test',
    provider: 'mock',
    apiBaseUrl: 'https://dev.local/api/v1',
    uploadBaseUrl: 'https://dev.local/api/v1',
  },
}));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const data = new Map<string, string>();
  const sessionStorage = {
    get: (k: string) => data.get(k) ?? null,
    set: (k: string, v: string) => {
      data.set(k, v);
    },
    remove: (k: string) => {
      data.delete(k);
    },
  };
  return {
    session: createSession(sessionStorage),
    sessionStorage,
    operationNamespace: 'aftersale-pages',
  };
});
const order = {
  id: 'o1',
  orderNo: 'ORDER-1',
  orderStatus: 'PAID',
  fulfillmentStatus: 'SHIPPED',
  refundStatus: 'NONE',
  goodsAmountFen: 3990,
  freightAmountFen: 600,
  payableAmountFen: 4590,
  paidAmountFen: 4590,
  refundedAmountFen: 0,
  refundedGoodsFen: 0,
  refundedFreightFen: 0,
  expiresAt: null,
  paidAt: '2026-10-07T00:00:00Z',
  completedAt: null,
  item: { productId: 'p1', type: 'BOOK', title: '接口图书', unitPriceFen: 3990, quantity: 1 },
  allowedActions: ['APPLY_AFTERSALE'],
};
const caseView = {
  id: 'case1',
  orderId: 'o1',
  type: 'RETURN_REFUND',
  status: 'RETURN_REQUIRED',
  reason: '破损',
  requestedGoodsFen: 3990,
  requestedFreightFen: 0,
  approvedGoodsFen: 3990,
  approvedFreightFen: 0,
  returnedQuantity: 1,
  receivedQuantity: 0,
  returnAddress: '服务端退货地址',
  returnCarrierCode: null,
  returnTrackingNo: null,
  evidenceAssetIds: ['asset1'],
  allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
};
const wrappers: VueWrapper[] = [];
it('keeps zero requested and approved refund amounts as numeric currency', async () => {
  hooks.request.mockImplementation(async (path) =>
    path === '/aftersales/case1'
      ? {
          ...caseView,
          requestedGoodsFen: 0,
          requestedFreightFen: 0,
          approvedGoodsFen: 0,
          approvedFreightFen: 0,
        }
      : order,
  );
  const w = await open(DetailPage, { id: 'case1' });
  for (const label of ['申请商品退款', '申请运费退款', '已批准商品金额', '已批准运费金额'])
    expect(w.text()).toContain(`${label}：¥0.00`);
  expect(w.text()).not.toContain('免费');
});
beforeEach(() => {
  hooks.load = [];
  hooks.show = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', { navigateTo: vi.fn(), redirectTo: vi.fn(), showToast: vi.fn() });
  session.clear();
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'u', nickname: '测试', distributor: false },
  });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function open(component: typeof ApplyPage, options: Record<string, string>) {
  const w = mount(component);
  wrappers.push(w);
  hooks.load.forEach((f) => f(options));
  hooks.show.forEach((f) => f());
  await flushPromises();
  return w;
}
it('submits the return-refund type selected through the native picker for a shipped book', async () => {
  hooks.request.mockImplementation(async (path, options) =>
    path === '/orders/o1/refund' ? { ...caseView, status: 'REQUESTED', ...options.body } : order,
  );
  const w = await open(ApplyPage, { orderId: 'o1' });
  await w.get('picker').trigger('change', { detail: { value: '1' } });
  await w.get('textarea').setValue('包装破损');
  await w.findAll('input')[0]!.setValue('19.90');
  await w.findAll('input')[1]!.setValue('0');
  await w
    .findAll('button')
    .find((b) => b.text() === '提交售后申请')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/orders/o1/refund',
    expect.objectContaining({
      body: expect.objectContaining({
        type: 'RETURN_REFUND',
        requestedGoodsFen: 1990,
        requestedFreightFen: 0,
      }),
    }),
  );
});
it('shows only a refund for a digital order and retains its allowed submission type', async () => {
  hooks.request.mockImplementation(async (path, options) =>
    path === '/orders/o1/refund'
      ? { ...caseView, status: 'REQUESTED', ...options.body }
      : {
          ...order,
          fulfillmentStatus: 'NOT_REQUIRED',
          item: { ...order.item, type: 'COURSE' },
        },
  );
  const w = await open(ApplyPage, { orderId: 'o1' });
  expect(w.find('picker').exists()).toBe(false);
  expect(w.get('.aftersale-type-readonly').text()).toBe('仅退款');
  await w.get('textarea').setValue('课程无法观看');
  await w.findAll('input')[0]!.setValue('19.90');
  await w.findAll('input')[1]!.setValue('0');
  await w
    .findAll('button')
    .find((b) => b.text() === '提交售后申请')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/orders/o1/refund',
    expect.objectContaining({
      body: expect.objectContaining({ type: 'REFUND_ONLY' }),
    }),
  );
});
it('blocks submission during evidence upload and sends only READY IDs with exact integer fen', async () => {
  hooks.request.mockImplementation(async (path, options) =>
    path === '/orders/o1/refund'
      ? { ...caseView, status: 'REQUESTED', ...options.body }
      : path === '/aftersales/case1'
        ? { ...caseView, status: 'REQUESTED' }
        : order,
  );
  const w = await open(ApplyPage, { orderId: 'o1' });
  await w.get('textarea').setValue('包装破损');
  await w.findAll('input')[0]!.setValue('19.90');
  await w.findAll('input')[1]!.setValue('0');
  const uploader = w.getComponent(EvidenceUpload);
  uploader.vm.$emit('busy', true);
  await flushPromises();
  expect(
    w
      .findAll('button')
      .find((b) => b.text() === '提交售后申请')!
      .attributes('disabled'),
  ).toBeDefined();
  uploader.vm.$emit('ready', 'asset-ready');
  uploader.vm.$emit('busy', false);
  await flushPromises();
  await w
    .findAll('button')
    .find((b) => b.text() === '提交售后申请')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/orders/o1/refund',
    expect.objectContaining({
      method: 'POST',
      body: {
        type: 'REFUND_ONLY',
        reason: '包装破损',
        evidenceAssetIds: ['asset-ready'],
        requestedGoodsFen: 1990,
        requestedFreightFen: 0,
      },
      idempotencyKey: expect.any(String),
    }),
  );
  expect(uni.redirectTo).toHaveBeenCalledWith({ url: '/pages/aftersale/detail?id=case1' });
});
it('uses server return instructions and submits tracking only when the case allows it', async () => {
  let returned = false;
  hooks.request.mockImplementation(async (path) => {
    if (path === '/aftersales/case1/return-shipment') returned = true;
    return path.startsWith('/aftersales/')
      ? {
          ...caseView,
          status: returned ? 'RETURNING' : 'RETURN_REQUIRED',
          allowedActions: returned ? [] : caseView.allowedActions,
        }
      : order;
  });
  const w = await open(DetailPage, { id: 'case1' });
  expect(w.text()).toContain('服务端退货地址');
  await w.findAll('input')[0]!.setValue('SF');
  await w.findAll('input')[1]!.setValue('SF123');
  await w
    .findAll('button')
    .find((b) => b.text() === '提交退货物流')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/aftersales/case1/return-shipment',
    expect.objectContaining({
      method: 'POST',
      body: { carrierCode: 'SF', trackingNo: 'SF123' },
      idempotencyKey: expect.any(String),
    }),
  );
  expect(w.text()).toContain('退货中');
  expect(w.findAll('button').some((b) => b.text() === '提交退货物流')).toBe(false);
});
it('offers the server carrier catalog and fills the code from the picker', async () => {
  let returned = false;
  hooks.request.mockImplementation(async (path) => {
    if (path === '/logistics/carriers')
      return [
        { code: 'shunfeng', name: '顺丰速运' },
        { code: 'yunda', name: '韵达快递' },
      ];
    if (path === '/aftersales/case1/return-shipment') returned = true;
    return path.startsWith('/aftersales/')
      ? {
          ...caseView,
          status: returned ? 'RETURNING' : 'RETURN_REQUIRED',
          allowedActions: returned ? [] : caseView.allowedActions,
        }
      : order;
  });
  const w = await open(DetailPage, { id: 'case1' });
  expect(w.text()).toContain('物流公司：请选择');
  await w.get('picker').trigger('change', { detail: { value: '1' } });
  expect(w.text()).toContain('物流公司：韵达快递');
  await w.findAll('input')[1]!.setValue('YD123');
  await w
    .findAll('button')
    .find((b) => b.text() === '提交退货物流')!
    .trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/aftersales/case1/return-shipment',
    expect.objectContaining({ body: { carrierCode: 'yunda', trackingNo: 'YD123' } }),
  );
});

it.each([
  ['REQUESTED', '申请中'],
  ['REJECTED', '已拒绝'],
  ['FAILED', '退款失败'],
  ['PARTIAL', '部分退款'],
  ['FULL', '全部退款'],
  ['RETURN_RECEIVED', '待退款审核'],
])(
  'renders authoritative %s state, amounts and reasons without offering another return',
  async (status, label) => {
    hooks.request.mockImplementation(async (path) =>
      path === '/aftersales/case1'
        ? {
            ...caseView,
            status,
            allowedActions: [],
            approvedGoodsFen: 1990,
            rejectionReason: status === 'REJECTED' ? '申请资料需补充' : null,
            failureReason: status === 'FAILED' ? '退款渠道返回失败' : null,
            returnedQuantity: status === 'RETURN_RECEIVED' ? 1 : null,
            receivedQuantity: status === 'RETURN_RECEIVED' ? 1 : null,
          }
        : order,
    );
    const w = await open(DetailPage, { id: 'case1' });
    expect(w.text()).toContain(label);
    expect(w.text()).toContain('已批准商品金额：¥19.90');
    if (status === 'REJECTED') expect(w.text()).toContain('拒绝原因：申请资料需补充');
    if (status === 'FAILED') expect(w.text()).toContain('失败原因：退款渠道返回失败');
    if (status === 'RETURN_RECEIVED') expect(w.text()).toContain('实际退货数量：1');
    expect(w.findAll('input')).toHaveLength(0);
    expect(w.findAll('button').some((button) => button.text() === '提交退货物流')).toBe(false);
  },
);
