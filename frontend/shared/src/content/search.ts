import type { ProductCard, ProductType } from '../contracts';
import { createPager } from '../pagination';
import type { CatalogApi } from './api';
import type { PaperCard, ProductDetail, ProductSort } from './types';
export interface SearchFilters {
  keyword: string;
  type: ProductType | 'PAPER';
  sort: ProductSort;
}
export function createSearch(api: CatalogApi) {
  const pager = createPager<ProductCard | PaperCard, SearchFilters>((filter, page) =>
    !filter.keyword.trim()
      ? Promise.resolve({ items: [], page, pageSize: 20, total: 0 })
      : filter.type === 'PAPER'
        ? api.papers({ keyword: filter.keyword, page, pageSize: 20 })
        : api.products({ ...filter, type: filter.type, page, pageSize: 20 }),
  );
  const state = Object.assign(pager.state, { fieldError: '', submitted: false });
  return {
    state,
    clear(filter: SearchFilters) {
      state.fieldError = '';
      state.submitted = false;
      state.items = [];
      state.total = 0;
      return pager.reset({ ...filter, keyword: '' });
    },
    async submit(filter: SearchFilters) {
      state.fieldError = '';
      if (!filter.keyword.trim()) {
        state.fieldError = '请输入搜索关键词';
        state.submitted = false;
        state.items = [];
        await pager.reset({ ...filter, keyword: '' });
        return;
      }
      state.submitted = true;
      state.items = [];
      await pager.reset({ ...filter, keyword: filter.keyword.trim() });
    },
    loadMore: pager.loadMore,
    retry: pager.loadMore,
  };
}
export function purchaseError(product: ProductDetail, quantity: number): string {
  if (product.status !== 'ON_SALE' || !product.allowedActions.includes('BUY'))
    return '当前商品不可购买';
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 99) return '数量应为1至99的整数';
  if (
    product.type === 'BOOK' &&
    quantity > Math.min(product.book.availableStock, product.book.quantityLimit)
  )
    return '超过可售库存或限购数量';
  if (product.type !== 'BOOK' && quantity !== 1) return '数字商品一次购买一份';
  return '';
}
export function canLearn(product: ProductDetail): boolean {
  return (
    product.type === 'COURSE' &&
    product.course.entitlement === 'ACTIVE' &&
    (product.allowedActions.includes('LEARN') || product.allowedActions.includes('VIEW_CONTENT'))
  );
}
