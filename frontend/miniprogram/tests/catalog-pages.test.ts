// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import ProductList from '../src/pages/product/list.vue';
import ProductDetail from '../src/pages/product/detail.vue';
import ProductCardView from '../src/components/ProductCard.vue';
import SearchPage from '../src/pages/search/index.vue';
import ArticleDetail from '../src/pages/article/detail.vue';
import { createDevCatalogStore } from '../../shared/src/dev/catalog';
const hooks = vi.hoisted(() => ({
  load: [] as Array<(options: Record<string, string>) => void>,
  bottom: [] as Array<() => void>,
  show: [] as Array<() => void>,
  request: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (callback: (options: Record<string, string>) => void) => hooks.load.push(callback),
  onReachBottom: (callback: () => void) => hooks.bottom.push(callback),
  onShow: (callback: () => void) => hooks.show.push(callback),
  onHide: vi.fn(),
  onUnload: vi.fn(),
  onShareAppMessage: vi.fn(),
}));
vi.mock('../src/api/client', () => ({ client: { request: hooks.request } }));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('@jxsc/shared');
  return { session: createSession({ get: () => null, set: () => {}, remove: () => {} }) };
});
const book = createDevCatalogStore().products.find((item) => item.id === 'DEV-book')!;
const wrappers: VueWrapper[] = [];
it('shows public article metadata only when supplied by the server', async () => {
  hooks.request.mockResolvedValue({
    id: 'article1',
    title: '公开图文',
    contentBlocks: [],
    authorName: '真实作者',
    categoryName: '备考',
    updatedAt: '2026-10-01T12:00:00Z',
  });
  const w = mount(ArticleDetail);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({ id: 'article1' }));
  await flushPromises();
  expect(w.text()).toContain('真实作者');
  expect(w.text()).toContain('备考');
  expect(w.text()).toContain('2026-10-01 20:00');
});
it('reads an agreement as a document with its title and full text instead of a teaching cover', async () => {
  hooks.request.mockResolvedValue({
    id: '11',
    title: '用户协议',
    coverUrl: null,
    contentBlocks: [{ type: 'TEXT', text: '客户确认的协议正文。' }],
    updatedAt: '2026-10-01T12:00:00Z',
  });
  const w = mount(ArticleDetail);
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({ id: '11', agreement: 'terms' }));
  await flushPromises();
  expect(w.text()).toContain('客户确认的协议正文。');
  expect(w.text()).not.toContain('读懂文本');
  expect(uni.setNavigationBarTitle).toHaveBeenCalledWith({ title: '用户协议' });
});
it('notifies the login opener when an agreement body cannot be read and permits retry', async () => {
  hooks.request.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({
    id: '11',
    title: '用户协议',
    coverUrl: null,
    contentBlocks: [{ type: 'TEXT', text: '恢复后的协议正文。' }],
  });
  const emit = vi.fn();
  const w = mount(ArticleDetail, {
    global: { mocks: { getOpenerEventChannel: () => ({ emit }) } },
  });
  wrappers.push(w);
  hooks.load.forEach((fn) => fn({ id: '11', agreement: 'terms' }));
  await flushPromises();
  expect(emit).toHaveBeenCalledWith('agreementUnavailable');
  await w
    .findAll('button')
    .find((b) => b.text() === '重新加载')!
    .trigger('click');
  await flushPromises();
  expect(w.text()).toContain('恢复后的协议正文。');
  expect(emit).toHaveBeenCalledTimes(1);
});
beforeEach(() => {
  hooks.load = [];
  hooks.bottom = [];
  hooks.show = [];
  hooks.request.mockReset();
  vi.stubGlobal('uni', {
    getStorageSync: () => '',
    setStorageSync: vi.fn(),
    setNavigationBarTitle: vi.fn(),
    showModal: vi.fn(),
  });
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.unstubAllGlobals();
});
it('loads the next catalog page on the platform reach-bottom event', async () => {
  hooks.request.mockImplementation(async (path, options) =>
    path === '/categories'
      ? { items: [], page: 1, pageSize: 20, total: 0 }
      : {
          items:
            options.query.page === 1
              ? Array.from({ length: 6 }, (_, i) => ({ ...book, id: `b${i}`, title: `书${i}` }))
              : [{ ...book, id: 'last', title: '最后一本' }],
          page: options.query.page,
          pageSize: 6,
          total: 7,
        },
  );
  const wrapper = mount(ProductList);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({}));
  await flushPromises();
  hooks.bottom.forEach((callback) => callback());
  await flushPromises();
  expect(wrapper.text()).toContain('最后一本');
});
it('refreshes the catalog when the search keyword is cleared', async () => {
  const matchingProduct = { ...book, id: 'matching', title: '阅读结果' };
  const unfilteredProduct = { ...book, id: 'unfiltered', title: '未筛选商品' };
  hooks.request.mockImplementation(async (_path, options) => ({
    items: options.query.keyword ? [matchingProduct] : [unfilteredProduct],
    page: 1,
    pageSize: 6,
    total: 1,
  }));
  const wrapper = mount(ProductList);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ keyword: '阅读', type: 'BOOK' }));
  await flushPromises();
  expect(wrapper.text()).toContain('阅读结果');

  await wrapper.get('.app-search-bar__clear').trigger('click');
  await flushPromises();

  expect((wrapper.get('input').element as HTMLInputElement).value).toBe('');
  expect(wrapper.text()).toContain('未筛选商品');
  expect(wrapper.text()).not.toContain('阅读结果');
  const productRequests = hooks.request.mock.calls.filter(([path]) => path === '/products');
  expect(productRequests).toHaveLength(2);
  expect(productRequests.at(-1)).toEqual([
    '/products',
    {
      query: { keyword: '', type: 'BOOK', page: 1, pageSize: 6, sort: 'DEFAULT' },
    },
  ]);
});

it('keeps invalid or fractional book quantities invalid', async () => {
  hooks.request.mockResolvedValue(book);
  const wrapper = mount(ProductDetail);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ id: book.id }));
  hooks.show.forEach((callback) => callback());
  await flushPromises();
  for (const value of ['0', '-1', '1.5', '', '4']) {
    await wrapper.get('input').setValue(value);
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '立即购买')!
        .attributes('disabled'),
    ).toBeDefined();
  }
});
it('shows the server course content duration separately from viewing validity', async () => {
  const course = {
    ...book,
    id: 'course-duration',
    type: 'COURSE' as const,
    title: '课程时长契约',
    course: {
      courseId: 'course-duration',
      validityType: 'DAYS' as const,
      validityDays: 365,
      durationMinutes: 125,
      entitlement: 'NONE' as const,
      chaptersSummary: 8,
    },
    allowedActions: ['BUY' as const],
  };
  hooks.request.mockResolvedValue(course);
  const wrapper = mount(ProductDetail);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ id: course.id }));
  hooks.show.forEach((callback) => callback());
  await flushPromises();
  expect(wrapper.text()).toContain('125 分钟内容');
  expect(wrapper.text()).toContain('365 天');
});
it('clearing the search cancels an in-flight result without a blank catalog query', async () => {
  let finishSearch!: (value: unknown) => void;
  let finishRecommendations: ((value: unknown) => void) | undefined;
  hooks.request.mockImplementation(
    (_path, options) =>
      new Promise((resolve) => {
        if (options.query.keyword) finishSearch = resolve;
        else finishRecommendations = resolve;
      }),
  );
  const wrapper = mount(SearchPage);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ keyword: '语文' }));
  await flushPromises();
  await wrapper.get('.app-search-bar__clear').trigger('click');
  await flushPromises();
  expect(finishRecommendations).toBeTypeOf('function');
  finishSearch({ items: [{ ...book, title: '迟到商品' }], page: 1, pageSize: 20, total: 1 });
  finishRecommendations!({
    items: [{ ...book, id: 'recommendation', type: 'COURSE', title: '清空后的推荐课程' }],
    page: 1,
    pageSize: 2,
    total: 1,
  });
  await flushPromises();
  expect(wrapper.text()).not.toContain('迟到商品');
  expect(wrapper.text()).toContain('清空后的推荐课程');
  expect(hooks.request).toHaveBeenCalledTimes(2);
  expect(hooks.request).toHaveBeenNthCalledWith(2, '/products', {
    query: { type: 'COURSE', page: 1, pageSize: 2, sort: 'DEFAULT' },
  });
});

it('clearing search restores recommendations without an empty-keyword error', async () => {
  const searchResult = {
    ...book,
    id: 'search-result',
    type: 'COURSE' as const,
    title: '搜索结果课程',
  };
  const recommendation = {
    ...book,
    id: 'recommended-course',
    type: 'COURSE' as const,
    title: '推荐课程',
  };
  hooks.request.mockImplementation(async (_path, options) => ({
    items: [options.query.keyword ? searchResult : recommendation],
    page: 1,
    pageSize: 20,
    total: 1,
  }));
  const wrapper = mount(SearchPage);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ keyword: '语文' }));
  await flushPromises();
  expect(wrapper.text()).toContain('搜索结果课程');

  await wrapper.get('.app-search-bar__clear').trigger('click');
  await flushPromises();

  expect((wrapper.get('input').element as HTMLInputElement).value).toBe('');
  expect(wrapper.text()).toContain('推荐课程');
  expect(wrapper.text()).not.toContain('请输入搜索关键词');
  expect(wrapper.text()).not.toContain('搜索结果课程');
  expect(hooks.request).toHaveBeenNthCalledWith(2, '/products', {
    query: { type: 'COURSE', page: 1, pageSize: 2, sort: 'DEFAULT' },
  });
});

it.each([{}, { keyword: '' }])(
  'opens a fresh search without restoring a previous keyword: %j',
  async (options) => {
    vi.stubGlobal('uni', {
      getStorageSync: () => '%2525E8%2525AF%2525AD',
      setStorageSync: vi.fn(),
    });
    hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 2, total: 0 });
    const wrapper = mount(SearchPage);
    wrappers.push(wrapper);
    hooks.load.forEach((callback) => callback(options));
    await flushPromises();
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('');
    expect(wrapper.text()).toContain('推荐关键词');
    expect(hooks.request).toHaveBeenCalledWith('/products', {
      query: { type: 'COURSE', page: 1, pageSize: 2, sort: 'DEFAULT' },
    });
  },
);

for (const [name, component] of [
  ['search', SearchPage],
  ['catalog', ProductList],
] as const) {
  it.each([
    ['%E8%AF%AD%E6%96%87', '语文'],
    ['语文', '语文'],
    ['%E8%AF%AD%E6%96%87%20%26%20%2B%2050%25', '语文 & + 50%'],
    ['%2520', '%20'],
    ['50%优惠', '50%优惠'],
  ])(
    `loads the ${name} route keyword as text without repeated decoding: %s`,
    async (keyword, expected) => {
      hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 });
      const wrapper = mount(component);
      wrappers.push(wrapper);
      hooks.load.forEach((callback) => callback({ keyword }));
      await flushPromises();
      expect((wrapper.get('input').element as HTMLInputElement).value).toBe(expected);
      expect(hooks.request).toHaveBeenCalledWith('/products', {
        query: expect.objectContaining({ keyword: expected }),
      });
    },
  );
}
it('keeps product discovery to one type row and a single price-sort action', async () => {
  hooks.request.mockResolvedValue({ items: [], page: 1, pageSize: 6, total: 0 });
  const wrapper = mount(ProductList);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({}));
  await flushPromises();

  expect(wrapper.text()).toContain('综合排序 · 0 个内容');
  expect(wrapper.find('.category-scroll').exists()).toBe(false);
  expect(wrapper.findAll('.product-type-tab').map((tab) => tab.text())).toEqual([
    '全部',
    '录播课程',
    '语文图书',
    '试卷会员',
  ]);
  expect(wrapper.findAll('.price-sort-toggle')).toHaveLength(1);

  await wrapper.findAll('.product-type-tab').at(3)!.trigger('click');
  const productRequests = hooks.request.mock.calls.filter(([path]) => path === '/products');
  expect(productRequests.at(-1)?.[1]).toEqual({
    query: expect.objectContaining({ type: 'MEMBERSHIP', page: 1, pageSize: 6 }),
  });

  await wrapper.get('.price-sort-toggle').trigger('click');
  const afterDescending = hooks.request.mock.calls.filter(([path]) => path === '/products').at(-1);
  expect(afterDescending?.[1]).toEqual({
    query: expect.objectContaining({ type: 'MEMBERSHIP', sort: 'PRICE_DESC' }),
  });
  expect(wrapper.text()).toContain('价格排序 · 0 个内容');

  await wrapper.get('.price-sort-toggle').trigger('click');
  const afterAscending = hooks.request.mock.calls.filter(([path]) => path === '/products').at(-1);
  expect(afterAscending?.[1]).toEqual({
    query: expect.objectContaining({ type: 'MEMBERSHIP', sort: 'PRICE_ASC' }),
  });
});

it.each([
  [book, '自有图书'],
  [{ ...book, id: 'course-card', type: 'COURSE' as const }, '录播课程'],
  [{ ...book, id: 'membership-card', type: 'MEMBERSHIP' as const }, '试卷会员'],
])('shows the product type in its card (%s)', (product, label) => {
  const wrapper = mount(ProductCardView, { props: { product } });
  wrappers.push(wrapper);
  expect(wrapper.get('.product-card__type').text()).toBe(label);
});

it.each([
  [book, '图书详情'],
  [
    {
      ...book,
      id: 'course-title',
      type: 'COURSE' as const,
      course: {
        courseId: 'course-title',
        validityType: 'DAYS' as const,
        validityDays: 30,
        durationMinutes: 60,
        entitlement: 'NONE' as const,
        chaptersSummary: 4,
      },
    },
    '课程详情',
  ],
])('sets the navigation title for %s', async (product, title) => {
  hooks.request.mockResolvedValue(product);
  const wrapper = mount(ProductDetail);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ id: product.id }));
  hooks.show.forEach((callback) => callback());
  await flushPromises();
  expect(uni.setNavigationBarTitle).toHaveBeenCalledWith({ title });
});

it('keeps sharing with product information and shows price beside the fixed purchase action', async () => {
  hooks.request.mockResolvedValue(book);
  const wrapper = mount(ProductDetail);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ id: book.id }));
  hooks.show.forEach((callback) => callback());
  await flushPromises();

  expect(wrapper.find('.detail-share-button').exists()).toBe(true);
  expect(wrapper.find('.bottom-action-bar .ui-price').exists()).toBe(true);
  expect(wrapper.find('.bottom-action-bar .sub-actions').exists()).toBe(false);
});

it.each(['LEARN', 'VIEW_CONTENT'] as const)(
  'offers learning in the fixed footer for an owned course with %s',
  async (action) => {
    const course = {
      ...book,
      id: 'owned-product',
      type: 'COURSE' as const,
      course: {
        courseId: 'owned-course',
        validityType: 'DAYS' as const,
        validityDays: 30,
        durationMinutes: 60,
        entitlement: 'ACTIVE' as const,
        chaptersSummary: 3,
      },
      allowedActions: [action],
    };
    Object.assign(uni, { navigateTo: vi.fn().mockResolvedValue(undefined) });
    hooks.request.mockResolvedValue(course);
    const wrapper = mount(ProductDetail);
    wrappers.push(wrapper);
    hooks.load.forEach((callback) => callback({ id: course.id }));
    hooks.show.forEach((callback) => callback());
    await flushPromises();

    const footer = wrapper.get('.bottom-action-bar');
    expect(footer.text()).not.toContain('当前暂不可购买或已下架');
    const learn = footer.findAll('button').find((button) => button.text() === '进入学习');
    expect(learn).toBeDefined();
    await learn!.trigger('click');
    expect(uni.navigateTo).toHaveBeenCalledWith({
      url: '/pages/course/learn?id=owned-course&productId=owned-product',
    });
  },
);

it('does not offer footer learning for an expired course even with a stale learning action', async () => {
  hooks.request.mockResolvedValue({
    ...book,
    id: 'expired-course',
    type: 'COURSE',
    allowedActions: ['LEARN'],
    course: {
      courseId: 'expired',
      validityType: 'DAYS',
      validityDays: 30,
      durationMinutes: 60,
      entitlement: 'EXPIRED',
      chaptersSummary: 3,
    },
  });
  const wrapper = mount(ProductDetail);
  wrappers.push(wrapper);
  hooks.load.forEach((callback) => callback({ id: 'expired-course' }));
  hooks.show.forEach((callback) => callback());
  await flushPromises();
  expect(wrapper.get('.bottom-action-bar').text()).not.toContain('进入学习');
});
