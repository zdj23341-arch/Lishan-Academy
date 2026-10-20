import { describe, expect, it } from 'vitest';
import { createClient, type RequestOptions } from '../src/http';
import { createSession } from '../src/session';
import { targetToRoute } from '../src/content/routing';
import { createCatalogApi } from '../src/content/api';
import { createSearch, purchaseError, canLearn } from '../src/content/search';
import { createDevTransport } from '../src/dev/transport';
import type { ProductDetail } from '../src/content/types';

function api() {
  return createCatalogApi(
    createClient({
      baseUrl: '/api/v1',
      transport: createDevTransport(),
      session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
    }),
  );
}
describe('public catalog boundary', () => {
  it('preserves encoded ID and explicit product query at the HTTP boundary', async () => {
    const urls: string[] = [];
    const catalog = createCatalogApi(
      createClient({
        baseUrl: '/api/v1',
        session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
        transport: async (request) => {
          urls.push(request.url);
          return {
            status: 200,
            body: { code: 'OK', message: '', requestId: 'DEV-query', data: null },
          };
        },
      }),
    );
    await catalog.product('book&a');
    await catalog.products({
      type: 'BOOK',
      keyword: '作文',
      categoryId: 'cat&1',
      sort: 'PRICE_DESC',
      page: 2,
      pageSize: 3,
    });
    expect(urls).toEqual([
      '/api/v1/products/book%26a',
      '/api/v1/products?type=BOOK&keyword=%E4%BD%9C%E6%96%87&categoryId=cat%261&sort=PRICE_DESC&page=2&pageSize=3',
    ]);
  });
  it('paper searches omit product type and unsupported sort parameters', async () => {
    const queries: unknown[] = [];
    const search = createSearch(
      createCatalogApi({
        request: async <T>(_path: string, options?: RequestOptions) => {
          queries.push(options?.query);
          return { items: [], page: 1, pageSize: 20, total: 0 } as T;
        },
      }),
    );
    await search.submit({ keyword: '作文', type: 'PAPER', sort: 'PRICE_DESC' });
    expect(queries).toEqual([{ keyword: '作文', page: 1, pageSize: 20 }]);
  });
  it('rejects inherited object property names as target types', () => {
    expect(() => targetToRoute({ type: 'toString' as 'ARTICLE', id: null, filters: {} })).toThrow();
  });
  it('clearing an in-flight search prevents its results from coming back', async () => {
    let finish!: (value: unknown) => void;
    const catalog = createCatalogApi({
      request: <T>() =>
        new Promise<T>((resolve) => {
          finish = resolve as (value: unknown) => void;
        }),
    });
    const search = createSearch(catalog);
    const first = search.submit({ keyword: 'old', type: 'BOOK', sort: 'DEFAULT' });
    await Promise.resolve();
    await search.submit({ keyword: '', type: 'PAPER', sort: 'DEFAULT' });
    finish({ items: [{ id: 'old' }], page: 1, pageSize: 20, total: 1 });
    await first;
    expect(search.state.items).toEqual([]);
    expect(search.state.submitted).toBe(false);
  });
  it('encodes internal IDs and rejects missing IDs/external target types', () => {
    expect(targetToRoute({ type: 'PRODUCT', id: 'book&a', filters: {} })).toBe(
      '/pages/product/detail?id=book%26a',
    );
    expect(() => targetToRoute({ type: 'ARTICLE', id: null, filters: {} })).toThrow();
    expect(() => targetToRoute({ type: 'https:' as 'ARTICLE', id: 'evil', filters: {} })).toThrow();
    expect(() =>
      targetToRoute({ type: 'PRODUCT_LIST', id: null, filters: { redirect: 'https://evil' } }),
    ).toThrow();
  });
  it('maps every approved homepage target to a registered internal page', () => {
    expect(targetToRoute({ type: 'LEARNING', id: null, filters: {} })).toBe(
      '/pages/learning/index',
    );
    expect(targetToRoute({ type: 'ORDER_LIST', id: null, filters: {} })).toBe('/pages/order/list');
    expect(targetToRoute({ type: 'LOGISTICS', id: null, filters: {} })).toBe('/pages/order/list');
    expect(targetToRoute({ type: 'MEMBERSHIP_CENTER', id: null, filters: {} })).toBe(
      '/pages/membership/index',
    );
    expect(targetToRoute({ type: 'COURSE_DETAIL', id: 'course/a', filters: {} })).toBe(
      '/pages/course/learn?id=course%2Fa',
    );
    expect(() => targetToRoute({ type: 'COURSE_DETAIL', id: null, filters: {} })).toThrow();
  });
  it('forwards backend paper classification filters without renaming them', async () => {
    const queries: unknown[] = [];
    const catalog = createCatalogApi({
      request: async <T>(_path: string, options?: RequestOptions) => {
        queries.push(options?.query);
        return { items: [], page: 1, pageSize: 20, total: 0 } as T;
      },
    });
    await catalog.papers({
      schoolStage: 'JUNIOR',
      paperType: 'FINAL_EXAM',
      stage: 'JUNIOR',
      type: 'EXAM',
      page: 2,
      pageSize: 10,
    });
    expect(queries).toEqual([
      {
        schoolStage: 'JUNIOR',
        paperType: 'FINAL_EXAM',
        stage: 'JUNIOR',
        type: 'EXAM',
        page: 2,
        pageSize: 10,
      },
    ]);
  });
  it('rejects blank search without requesting and papers use their own endpoint', async () => {
    const paths: string[] = [];
    const catalog = createCatalogApi({
      request: async <T>(path: string) => {
        paths.push(path);
        return { items: [], page: 1, pageSize: 20, total: 0 } as T;
      },
    });
    const search = createSearch(catalog);
    await search.submit({ keyword: '  ', type: 'BOOK', sort: 'DEFAULT' });
    expect(search.state.fieldError).toBeTruthy();
    expect(paths).toEqual([]);
    await search.submit({ keyword: '语文', type: 'PAPER', sort: 'PRICE_ASC' });
    expect(paths).toEqual(['/papers']);
  });
  it('replacing search filters discards stale results and clears previous visible cards', async () => {
    let finish!: (value: unknown) => void;
    const catalog = createCatalogApi({
      request: <T>(_path: string, options?: RequestOptions) =>
        options?.query?.keyword === 'old'
          ? new Promise<T>((resolve) => {
              finish = resolve as (value: unknown) => void;
            })
          : Promise.resolve({ items: [], page: 1, pageSize: 20, total: 0 } as T),
    });
    const search = createSearch(catalog);
    const old = search.submit({ keyword: 'old', type: 'BOOK', sort: 'DEFAULT' });
    await Promise.resolve();
    await search.submit({ keyword: 'new', type: 'PAPER', sort: 'DEFAULT' });
    finish({ items: [{ id: 'old' }], page: 1, pageSize: 20, total: 1 });
    await old;
    expect(search.state.items).toEqual([]);
  });
  it('serves home order, all subtypes and public attachments without private URLs', async () => {
    const catalog = api();
    const home = await catalog.home();
    expect(home.banners).toHaveLength(3);
    expect(home.shortcuts).toHaveLength(6);
    for (const entry of home.shortcuts) expect(targetToRoute(entry.target)).toMatch(/^\/pages\//);
    expect((await catalog.product('DEV-book')).type).toBe('BOOK');
    expect((await catalog.product('DEV-member-12')).type).toBe('MEMBERSHIP');
    const paper = await catalog.paper('DEV-paper');
    expect(paper.attachments).toHaveLength(5);
    expect(JSON.stringify(paper)).not.toMatch(/signed|storageKey/);
    expect((await catalog.article('DEV-guide')).contentBlocks.length).toBeGreaterThan(1);
  });
  it('guards quantities and allows off-sale entitled courses to learn independently of membership', async () => {
    const catalog = api();
    const book = await catalog.product('DEV-book');
    for (const quantity of [0, 100, 4, 1.5]) expect(purchaseError(book, quantity)).toBeTruthy();
    expect(purchaseError(book, 1)).toBe('');
    const course = await catalog.product('DEV-course-off');
    expect(purchaseError(course, 1)).toBeTruthy();
    expect(canLearn(course)).toBe(true);
    expect(canLearn(await catalog.product('DEV-course'))).toBe(false);
  });
  it('recognizes the real catalog LEARN action for active purchased courses, including off-sale courses', async () => {
    const source = await api().product('DEV-course-off');
    for (const status of ['ON_SALE', 'OFF_SALE'] as const) {
      const product: ProductDetail = { ...source, status, allowedActions: ['LEARN'] };
      expect(canLearn(product)).toBe(true);
    }
  });
  it('requires active course entitlement and an explicit learning action', async () => {
    const source = await api().product('DEV-course-off');
    if (source.type !== 'COURSE') throw new Error('Expected course fixture');
    for (const action of ['LEARN', 'VIEW_CONTENT'] as const) {
      for (const entitlement of ['NONE', 'EXPIRED'] as const) {
        expect(
          canLearn({
            ...source,
            allowedActions: [action],
            course: { ...source.course, entitlement },
          }),
        ).toBe(false);
      }
    }
    for (const allowedActions of [[], ['BUY'], ['TRIAL']] as ProductDetail['allowedActions'][]) {
      expect(canLearn({ ...source, allowedActions })).toBe(false);
    }
    for (const id of ['DEV-book', 'DEV-member-12']) {
      expect(canLearn({ ...(await api().product(id)), allowedActions: ['LEARN'] })).toBe(false);
    }
  });
  it('supports paging, filtering, no results and a failed request followed by retry', async () => {
    const catalog = api();
    expect(
      (await catalog.products({ type: 'MEMBERSHIP', pageSize: 1, page: 2 })).items[0]?.id,
    ).toBe('DEV-member-12');
    expect((await catalog.products({ keyword: '不存在' })).total).toBe(0);
    await expect(catalog.products({ keyword: 'DEV-RETRY' })).rejects.toThrow();
    await expect(catalog.products({ keyword: 'DEV-RETRY' })).resolves.toHaveProperty('items');
    await expect(catalog.product('missing')).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });
});
