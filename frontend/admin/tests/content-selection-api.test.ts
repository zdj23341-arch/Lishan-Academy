import { afterEach, expect, it, vi } from 'vitest';
import { contentSelectionSource } from '../src/api/content-selection';
const api = vi.hoisted(() => ({
  products: vi.fn(),
  product: vi.fn(),
  asset: vi.fn(),
  articles: vi.fn(),
  article: vi.fn(),
  papers: vi.fn(),
  paper: vi.fn(),
  course: vi.fn(),
  publicProduct: vi.fn(),
}));
vi.mock('../src/api/content', () => ({
  contentApi: api,
  catalogApi: { product: api.publicProduct },
}));
vi.mock('../src/api/learning', () => ({ learningApi: api }));
afterEach(() => vi.resetAllMocks());
const product = {
  id: '101',
  version: 2,
  type: 'COURSE',
  title: '阅读课程',
  status: 'ON_SALE',
  coverAssetId: null,
  priceFen: 9900,
  originalPriceFen: null,
  subtitle: null,
  sortNo: 0,
  categoryIds: [],
  contentBlocks: [],
};
it('uses the actual course identity for a course detail target and product identity for a course section', async () => {
  api.products.mockResolvedValue({ items: [product], page: 2, pageSize: 10, total: 11 });
  api.publicProduct.mockResolvedValue({ ...product, course: { courseId: '901' } });
  const target = await contentSelectionSource.search('COURSE_DETAIL', {
    keyword: '阅读',
    page: 2,
    pageSize: 10,
  });
  expect(target.items[0]?.id).toBe('901');
  expect(api.products).toHaveBeenCalledWith({
    keyword: '阅读',
    page: 2,
    pageSize: 10,
    type: 'COURSE',
    status: 'ON_SALE',
  });
  const section = await contentSelectionSource.search('COURSE', {
    keyword: '阅读',
    page: 2,
    pageSize: 10,
  });
  expect(section.items[0]?.id).toBe('101');
  expect(section.total).toBe(11);
});
it('retains an unavailable existing product with its title and a reason', async () => {
  api.product.mockResolvedValue({ ...product, type: 'BOOK', status: 'OFF_SALE' });
  const value = await contentSelectionSource.resolve('BOOK', '101');
  expect(value).toMatchObject({
    id: '101',
    title: '阅读课程',
    available: false,
    reason: '商品未上架',
  });
});
it('never converts an unreadable course relationship into a selectable product ID', async () => {
  api.products.mockResolvedValue({ items: [product], page: 1, pageSize: 10, total: 1 });
  api.publicProduct.mockRejectedValue(new Error('断网'));
  const result = await contentSelectionSource.search('COURSE_DETAIL', {
    keyword: '',
    page: 1,
    pageSize: 10,
  });
  expect(result.items[0]?.available).toBe(false);
});
