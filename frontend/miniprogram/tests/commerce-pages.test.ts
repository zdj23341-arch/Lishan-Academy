// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import AddressEdit from '../src/pages/address/edit.vue';
import AddressList from '../src/pages/address/list.vue';
import ConfirmOrder from '../src/pages/order/confirm.vue';
import { session, sessionStorage, operationNamespace } from '../src/api/session';
import { createOperationController } from '../../shared/src/commerce/operation';
import { createDevCatalogStore } from '../../shared/src/dev/catalog';
import { selectedAddressId } from '../src/stores/orderDraft';
import RegionPicker from '../src/components/RegionPicker.vue';
import { ApiError } from '../../shared/src/http';
import { createClient } from '../../shared/src/http';
import { createDevTransport } from '../../shared/src/dev/transport';
import { createDevCommerceHandler } from '../../shared/src/dev/commerce';
import type { OrderView } from '../../shared/src/contracts';

const hooks = vi.hoisted(() => ({
  load: [] as Array<(options: Record<string, string>) => void>,
  show: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (cb: (options: Record<string, string>) => void) => hooks.load.push(cb),
  onShow: (cb: () => void) => hooks.show.push(cb),
  onReachBottom: vi.fn(),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const values = new Map<string, string>();
  const sessionStorage = {
    get: (k: string) => values.get(k) ?? null,
    set: (k: string, v: string) => {
      values.set(k, v);
    },
    remove: (k: string) => {
      values.delete(k);
    },
  };
  return {
    session: createSession(sessionStorage),
    sessionStorage,
    operationNamespace: 'test-commerce-pages',
  };
});
const address = {
  id: 'a1',
  version: 2,
  name: '真实收件人',
  phone: '13812345678',
  provinceCode: '110000',
  cityCode: '110100',
  districtCode: '110108',
  detail: '一号院',
  isDefault: true,
};
const wrappers: VueWrapper[] = [];
function switchAccount() {
  session.set({
    accessToken: 'account-b',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'b', nickname: '乙', distributor: false },
  });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  hooks.load = [];
  hooks.show = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', {
    navigateTo: vi.fn(),
    navigateBack: vi.fn(),
    redirectTo: vi.fn(),
    showToast: vi.fn(),
    showModal: vi.fn(),
    removeStorageSync: vi.fn(),
  });
  session.clear();
  session.set({
    accessToken: 'test',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'u', nickname: '测试', distributor: false },
  });
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.unstubAllGlobals();
});
async function open(component: typeof AddressEdit, options: Record<string, string> = {}) {
  const wrapper = mount(component);
  wrappers.push(wrapper);
  hooks.load.forEach((cb) => cb(options));
  hooks.show.forEach((cb) => cb());
  await flushPromises();
  return wrapper;
}
it('shows one region validation message near its field without a reload action on empty save', async () => {
  const w = await open(AddressEdit);
  await w.get('.save-btn').trigger('click');
  await flushPromises();
  expect(w.text().match(/请选择完整地区/g)).toHaveLength(1);
  expect(w.text()).not.toContain('重新加载地址');
  expect(w.get('[data-field="region"]').text()).toContain('请选择完整地区');
  expect(hooks.request).not.toHaveBeenCalled();
});

it('loads an existing address and retains edits when API rejects its version', async () => {
  hooks.request.mockImplementation(async (path, options) => {
    if (options?.method === 'PATCH') throw new Error('地址版本冲突');
    return { items: [address], page: 1, pageSize: 20, total: 1 };
  });
  const w = await open(AddressEdit, { id: 'a1' });
  expect((w.findAll('input')[0]!.element as HTMLInputElement).value).toBe(address.name);
  await w.findAll('input')[0]!.setValue('保留的新姓名');
  await w.get('button').trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/addresses/a1',
    expect.objectContaining({
      method: 'PATCH',
      body: expect.objectContaining({ name: '保留的新姓名', version: 2, provinceCode: '110000' }),
    }),
  );
  expect(w.text()).toContain('地址版本冲突');
  expect((w.findAll('input')[0]!.element as HTMLInputElement).value).toBe('保留的新姓名');
  expect(uni.navigateBack).not.toHaveBeenCalled();
});
it('management card clicks do not select or leave, and default writes then reloads', async () => {
  hooks.request.mockResolvedValue({
    items: [{ ...address, isDefault: false }],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  const w = await open(AddressList);
  expect(w.text()).toContain(address.name);
  await w.get('.address-card').trigger('click');
  expect(uni.navigateBack).not.toHaveBeenCalled();
  await w.get('.default-check').trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/addresses/a1',
    expect.objectContaining({
      method: 'PATCH',
      body: expect.objectContaining({ isDefault: true, version: 2 }),
    }),
  );
});
it('redirects unauthenticated address editing to login without loading private data', async () => {
  session.clear();
  await open(AddressEdit, { id: 'a1' });
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/login/index' });
  expect(hooks.request).not.toHaveBeenCalled();
});
it('ignores URL price and creates a pending order from an authoritative quote', async () => {
  const product = createDevCatalogStore().products.find((p) => p.id === 'DEV-member-6')!;
  const quote = {
    quoteId: 'q1',
    expiresAt: '2099-01-01T00:00:00Z',
    item: {
      productId: product.id,
      type: product.type,
      title: product.title,
      coverUrl: null,
      quantity: 1,
      unitPriceFen: 5900,
    },
    availableStock: null,
    goodsAmountFen: 5900,
    freightAmountFen: 0,
    payableAmountFen: 5900,
    currency: 'CNY',
    warnings: [],
  };
  hooks.request.mockImplementation(async (path) => {
    if (path === `/products/${product.id}`) return product;
    if (path === '/orders/preview') return quote;
    if (path === '/orders') return { id: 'o1' };
    throw new Error(`Unexpected ${path}`);
  });
  const w = await open(ConfirmOrder, {
    productId: product.id,
    quantity: '1',
    price: '1',
    title: '不可信标题',
  });
  expect(w.text()).toContain(product.title);
  expect(w.text()).not.toContain('不可信标题');
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(
    '/orders',
    expect.objectContaining({
      method: 'POST',
      body: { quoteId: 'q1', attributionToken: null },
      idempotencyKey: expect.any(String),
    }),
  );
  expect(uni.redirectTo).toHaveBeenCalledWith(
    expect.objectContaining({ url: '/pages/order/detail?id=o1&pay=1' }),
  );
  expect(uni.showToast).not.toHaveBeenCalled();
});

it('selects only an address ID and clears it on account change', async () => {
  hooks.request.mockResolvedValue({ items: [address], page: 1, pageSize: 20, total: 1 });
  const w = await open(AddressList, { select: '1' });
  await w.get('.address-card').trigger('click');
  expect(selectedAddressId.value).toBe('a1');
  expect(uni.navigateBack).toHaveBeenCalledOnce();
  session.clear();
  expect(selectedAddressId.value).toBeNull();
  await flushPromises();
  expect(w.text()).not.toContain(address.name);
});

it.each([false, true])(
  'never republishes account A addresses after B refresh fails (A pending: %s)',
  async (pending) => {
    const page = { items: [address], page: 1, pageSize: 20, total: 1 };
    const oldRequest = deferred<typeof page>();
    hooks.request.mockImplementation(() => (pending ? oldRequest.promise : Promise.resolve(page)));
    const w = await open(AddressList);
    if (!pending) expect(w.text()).toContain(address.name);
    switchAccount();
    oldRequest.resolve(page);
    await flushPromises();
    expect(w.text()).not.toContain(address.name);
    hooks.request.mockRejectedValue(new Error('B地址加载失败'));
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(w.text()).toContain('B地址加载失败');
    for (const privateField of [address.name, address.phone, address.detail])
      expect(w.text()).not.toContain(privateField);
    await w.get('[role="alert"] button').trigger('click');
    await flushPromises();
    expect(w.findAll('.address-card')).toHaveLength(0);
  },
);

it('keeps B checkout address when A address response arrives after B preview', async () => {
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(catalog, createDevCommerceHandler(catalog)),
  });
  const oldRequest = deferred<unknown>();
  hooks.request.mockImplementation((path, options) =>
    path === '/addresses' ? oldRequest.promise : client.request(path, options),
  );
  const w = await open(ConfirmOrder, { productId: 'DEV-book' });
  switchAccount();
  const bAddress = {
    ...address,
    name: '乙收件人',
    phone: '13987654321',
    detail: '乙地址',
    version: 0,
  };
  await client.request('/addresses', { method: 'POST', body: bAddress });
  hooks.request.mockImplementation(client.request);
  hooks.show.forEach((cb) => cb());
  await flushPromises();
  expect(w.text()).toContain(bAddress.name);
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeUndefined();
  oldRequest.resolve({ items: [address], page: 1, pageSize: 20, total: 1 });
  await flushPromises();
  expect(w.text()).toContain(bAddress.name);
  for (const privateField of [address.name, address.phone, address.detail])
    expect(w.text()).not.toContain(privateField);
});

it('requires acceptance of same-ID edited shipping details and preserves it through submit refresh', async () => {
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(catalog, createDevCommerceHandler(catalog)),
  });
  const saved = await client.request<typeof address>('/addresses', {
    method: 'POST',
    body: { ...address, version: 0 },
  });
  selectedAddressId.value = saved.id;
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: 'DEV-book' });
  const originalAmount = w.get('.pay-amount-box').text();
  await w.get('.address-section').trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/address/list?select=1' });
  await client.request(`/addresses/${saved.id}`, {
    method: 'PATCH',
    body: { ...saved, name: '修改后收件人', detail: '修改后门牌' },
  });
  hooks.show.forEach((cb) => cb());
  await flushPromises();
  expect(w.text()).toContain('修改后收件人');
  expect(w.get('.pay-amount-box').text()).toBe(originalAmount);
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeDefined();
  expect(hooks.request.mock.calls.filter(([path]) => path === '/orders')).toHaveLength(0);
  await w
    .findAll('button')
    .find((b) => b.text() === '确认当前报价')!
    .trigger('click');
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  expect(hooks.request.mock.calls.filter(([path]) => path === '/orders')).toHaveLength(1);
  expect(uni.redirectTo).toHaveBeenCalledOnce();
});

it('saves a new address with actual region codes and blocks duplicate submission', async () => {
  let resolve!: (value: unknown) => void;
  hooks.request.mockImplementation(async (_path, options) =>
    options?.method === 'POST'
      ? new Promise((done) => {
          resolve = done;
        })
      : { items: [], page: 1, pageSize: 20, total: 0 },
  );
  const w = await open(AddressEdit);
  await w.findAll('input')[0]!.setValue(' 新收件人 ');
  await w.findAll('input')[1]!.setValue('13912345678');
  await w.get('textarea').setValue(' 新地址 ');
  w.getComponent(RegionPicker).vm.$emit('update:modelValue', ['310000', '310100', '310104']);
  await w.get('button').trigger('click');
  await w.get('button').trigger('click');
  expect(hooks.request, w.text()).toHaveBeenCalledTimes(1);
  expect(hooks.request).toHaveBeenCalledWith(
    '/addresses',
    expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        name: '新收件人',
        detail: '新地址',
        provinceCode: '310000',
        cityCode: '310100',
        districtCode: '310104',
        version: 0,
      }),
    }),
  );
  resolve({ ...address, id: 'new' });
  await flushPromises();
  expect(uni.navigateBack).toHaveBeenCalledOnce();
});

it('requires explicit acceptance of changed price and recovers an unknown write using its original key', async () => {
  const product = createDevCatalogStore().products.find((p) => p.id === 'DEV-member-6')!;
  let previews = 0;
  hooks.request.mockImplementation(async (path) => {
    if (path === `/products/${product.id}`) return product;
    if (path === '/orders/preview') {
      const amount = ++previews === 1 ? 5900 : 6900;
      return {
        quoteId: `q${previews}`,
        expiresAt: '2099-01-01T00:00:00Z',
        item: {
          productId: product.id,
          type: product.type,
          title: product.title,
          quantity: 1,
          unitPriceFen: amount,
        },
        availableStock: null,
        goodsAmountFen: amount,
        freightAmountFen: 0,
        payableAmountFen: amount,
        currency: 'CNY',
        warnings: [],
      };
    }
    if (path === '/orders') throw new ApiError('TIMEOUT', '请求超时');
    if (path.startsWith('/operations/')) return { state: 'SUCCEEDED', resourceId: 'recovered' };
    throw new Error(`Unexpected ${path}`);
  });
  const w = await open(ConfirmOrder, { productId: product.id });
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  expect(hooks.request.mock.calls.filter(([path]) => path === '/orders')).toHaveLength(0);
  await w
    .findAll('button')
    .find((b) => b.text() === '确认当前报价')!
    .trigger('click');
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  const writes = hooks.request.mock.calls.filter(([path]) => path === '/orders');
  expect(writes).toHaveLength(1);
  expect(w.text()).toContain('原下单结果尚未确认');
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  expect(hooks.request).toHaveBeenCalledWith(`/operations/${writes[0]![1].idempotencyKey}`, {
    query: { scope: 'CREATE_ORDER' },
  });
  expect(hooks.request.mock.calls.filter(([path]) => path === '/orders')).toHaveLength(1);
  expect(uni.redirectTo).toHaveBeenCalledWith(
    expect.objectContaining({ url: '/pages/order/detail?id=recovered&pay=1' }),
  );
});

it('creates a real fixture book order with authoritative freight and leaves it unpaid', async () => {
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(catalog, createDevCommerceHandler(catalog)),
  });
  const saved = await client.request<typeof address>('/addresses', {
    method: 'POST',
    body: { ...address, version: 0 },
  });
  selectedAddressId.value = saved.id;
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: 'DEV-book', quantity: '2' });
  expect(w.text()).toContain(address.name);
  expect(w.text()).toContain('85.80');
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  const redirected = vi.mocked(uni.redirectTo).mock.calls[0]![0].url;
  const order = await client.request<OrderView>(
    `/orders/${new URL(redirected, 'https://mini.invalid').searchParams.get('id')}`,
  );
  expect(order).toMatchObject({
    orderStatus: 'PENDING_PAYMENT',
    goodsAmountFen: 7980,
    freightAmountFen: 600,
    paidAmountFen: 0,
    item: { quantity: 2 },
  });
  expect(catalog.products.find((p) => p.id === 'DEV-book')?.book?.availableStock).toBe(1);
});

it('explains unavailable shipping and re-quotes the same book quantity only after voluntary address selection', async () => {
  const catalog = createDevCatalogStore();
  const product = catalog.products.find((p) => p.id === 'DEV-book')!;
  const supportedAddress = { ...address, id: 'a2', isDefault: false, provinceCode: '310000' };
  const previewBodies: unknown[] = [];
  const writes: string[] = [];
  const client = createClient({
    baseUrl: 'https://fixture.local/api/v1',
    session,
    transport: async (request) => {
      const path = new URL(request.url).pathname;
      if (request.method !== 'GET' && path !== '/api/v1/orders/preview') writes.push(path);
      let data: unknown;
      if (path === `/api/v1/products/${product.id}`) data = product;
      else if (path === '/api/v1/addresses')
        data = { items: [address, supportedAddress], total: 2, page: 1, pageSize: 20 };
      else if (path === '/api/v1/orders/preview') {
        previewBodies.push(request.body);
        const body = request.body as { addressId: string; quantity: number };
        if (body.addressId === 'a1')
          return {
            status: 409,
            body: {
              code: 'SHIPPING_UNAVAILABLE',
              message: 'No enabled shipping rule for region',
              requestId: 'shipping-trace',
              data: null,
            },
          };
        data = {
          quoteId: 'supported-quote',
          expiresAt: '2099-01-01T00:00:00Z',
          currency: 'CNY',
          warnings: [],
          goodsAmountFen: 7980,
          freightAmountFen: 800,
          payableAmountFen: 8780,
          availableStock: 3,
          item: {
            productId: product.id,
            type: 'BOOK',
            title: product.title,
            coverUrl: null,
            quantity: 2,
            unitPriceFen: 3990,
          },
        };
      } else throw new Error(`Unexpected path ${path}`);
      return {
        status: 200,
        body: { code: 'OK', message: 'OK', requestId: 'shipping-trace', data },
      };
    },
  });
  selectedAddressId.value = 'a1';
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: product.id, quantity: '2' });
  expect(w.get('[role="alert"]').text()).toBe('当前收货地区暂不支持配送，请更换地址或联系客服');
  expect(selectedAddressId.value).toBe('a1');
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeDefined();
  expect(w.find('.fee-card').exists()).toBe(false);
  expect(w.text()).toContain('× 2');
  await w.get('.address-section').trigger('click');
  expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/address/list?select=1' });
  selectedAddressId.value = 'a2';
  hooks.show.forEach((cb) => cb());
  await flushPromises();
  expect(previewBodies).toEqual([
    { productId: product.id, quantity: 2, addressId: 'a1' },
    { productId: product.id, quantity: 2, addressId: 'a2' },
  ]);
  expect(w.find('[role="alert"]').exists()).toBe(false);
  expect(w.get('.fee-card').text()).toContain('8.00');
  expect(w.get('.pay-amount-box').text()).toContain('87.80');
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeUndefined();
  expect(writes).toEqual([]);
});

it('disables checkout when a previously quoted shipping region becomes unavailable', async () => {
  const catalog = createDevCatalogStore();
  const transport = createDevTransport(catalog, createDevCommerceHandler(catalog));
  let unavailable = false;
  const client = createClient({
    baseUrl: 'https://fixture.local/api/v1',
    session,
    transport: (request) =>
      unavailable && new URL(request.url).pathname === '/api/v1/orders/preview'
        ? Promise.resolve({
            status: 409,
            body: {
              code: 'SHIPPING_UNAVAILABLE',
              message: 'No enabled shipping rule for region',
              requestId: 'shipping-trace',
              data: null,
            },
          })
        : transport(request),
  });
  const saved = await client.request<typeof address>('/addresses', {
    method: 'POST',
    body: { ...address, version: 0 },
  });
  selectedAddressId.value = saved.id;
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: 'DEV-book', quantity: '2' });
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeUndefined();
  unavailable = true;
  await w
    .findAll('button')
    .find((b) => b.text() === '刷新订单预览')!
    .trigger('click');
  await flushPromises();
  expect(w.get('[role="alert"]').text()).toBe('当前收货地区暂不支持配送，请更换地址或联系客服');
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeDefined();
  expect(w.find('.fee-card').exists()).toBe(false);
  expect(w.find('.pay-amount-box').exists()).toBe(false);
  expect(hooks.request.mock.calls.filter(([path]) => path === '/orders')).toHaveLength(0);
  unavailable = false;
  await w
    .findAll('button')
    .find((b) => b.text() === '刷新订单预览')!
    .trigger('click');
  await flushPromises();
  expect(w.find('[role="alert"]').exists()).toBe(false);
  expect(w.get('.pay-amount-box').text()).toContain('85.80');
  expect(w.get('.submit-order-btn').attributes('disabled')).toBeUndefined();
});

it('reopens the created order instead of stranding checkout at a successful journal', async () => {
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(catalog, createDevCommerceHandler(catalog)),
  });
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: 'DEV-member-6', quantity: '1' });
  await w.get('.submit-order-btn').trigger('click');
  await flushPromises();
  expect(uni.redirectTo).toHaveBeenCalledTimes(1);
  hooks.show.forEach((cb) => cb());
  await flushPromises();
  expect(uni.redirectTo).toHaveBeenCalledTimes(2);
  expect(
    hooks.request.mock.calls.filter(
      ([path, options]) => path === '/orders' && options?.method === 'POST',
    ),
  ).toHaveLength(1);
});

it('opens the newly selected product after an older app left a successful global checkout journal', async () => {
  const saved = createOperationController({
    session,
    storage: sessionStorage,
    namespace: operationNamespace,
    scope: 'CREATE_ORDER',
    execute: async (_body: { quoteId: string }) => ({ id: 'old-course-order' }),
    query: vi.fn(),
  });
  await saved.submit({ quoteId: 'old-quote' });
  saved.dispose();
  const catalog = createDevCatalogStore();
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    session,
    transport: createDevTransport(catalog, createDevCommerceHandler(catalog)),
  });
  hooks.request.mockImplementation(client.request);
  const w = await open(ConfirmOrder, { productId: 'DEV-member-6', quantity: '1' });
  expect(w.text()).toContain(catalog.products.find((p) => p.id === 'DEV-member-6')!.title);
  expect(uni.redirectTo).not.toHaveBeenCalled();
  expect(w.get('.submit-order-btn').text()).toBe('提交订单');
  expect(hooks.request.mock.calls.some(([path]) => path === '/orders')).toBe(false);
});
