import type { HomeView, Page, ProductCard } from '../contracts';
import type { ApiClient, Query } from '../http';
import type {
  ArticleDetail,
  Category,
  PaperCard,
  PaperDetail,
  PaperFilters,
  ProductDetail,
  ProductFilters,
} from './types';
function idPath(id: string) {
  if (!id?.trim()) throw new Error('内容ID不能为空');
  return encodeURIComponent(id);
}
function query(filters: ProductFilters | PaperFilters): Query {
  if (filters.page !== undefined && (!Number.isInteger(filters.page) || filters.page < 1))
    throw new Error('页码无效');
  if (
    filters.pageSize !== undefined &&
    (!Number.isInteger(filters.pageSize) || filters.pageSize < 1 || filters.pageSize > 100)
  )
    throw new Error('每页数量应为1至100');
  return { ...filters };
}
export function createCatalogApi(client: ApiClient) {
  return {
    home: () => client.request<HomeView>('/home'),
    products: (filters: ProductFilters = {}) =>
      client.request<Page<ProductCard>>('/products', { query: query(filters) }),
    product: (id: string) => client.request<ProductDetail>(`/products/${idPath(id)}`),
    papers: (filters: PaperFilters = {}) =>
      client.request<Page<PaperCard>>('/papers', { query: query(filters) }),
    paper: (id: string) => client.request<PaperDetail>(`/papers/${idPath(id)}`),
    article: (id: string) =>
      client.request<ArticleDetail>(`/articles/${idPath(id)}`, { authentication: 'none' }),
    categories: (filters: Pick<ProductFilters, 'page' | 'pageSize' | 'type'> = {}) =>
      client.request<Page<Category>>('/categories', { query: query(filters) }),
  };
}
export type CatalogApi = ReturnType<typeof createCatalogApi>;
