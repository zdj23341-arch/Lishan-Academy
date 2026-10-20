import { ApiError, type ApiClient, type Query, type RequestOptions } from '../http';
import { type HomeView, type Page, hasPermission } from '../contracts';
import { contentBlocksForWrite } from './validation';
import type {
  AdminAsset,
  AdminCategory,
  AdminProduct,
  CategoryWrite,
  HomeDraft,
  ProductWrite,
  UploadInput,
  UploadTicket,
  AdminArticle,
  ArticleWrite,
} from './admin-types';
export function createAdminContentApi(client: ApiClient, permissions: () => string[]) {
  async function call<T>(path: string, options?: RequestOptions): Promise<T> {
    if (!hasPermission(permissions(), 'CONTENT_EDIT'))
      throw new ApiError('FORBIDDEN', '没有内容管理权限');
    return client.request<T>(path, options);
  }
  const id = (value: string) => encodeURIComponent(value);
  return {
    homeDraft: () => call<HomeDraft>('/admin/home/draft'),
    saveHome: (body: HomeDraft) => call<HomeDraft>('/admin/home/draft', { method: 'PUT', body }),
    previewHome: () => call<HomeView>('/admin/home/preview'),
    recommendHome: (sectionId: string, revision: number, contentId: string, remove = false) =>
      call<HomeDraft>(`/admin/home/sections/${id(sectionId)}/recommend`, {
        method: 'POST',
        body: { revision, contentId, remove },
      }),
    publishHome: (revision: number, reason: string) =>
      call<HomeView>('/admin/home/publish', { method: 'POST', body: { revision, reason } }),
    products: (query: Query = {}) => call<Page<AdminProduct>>('/admin/products', { query }),
    product: (value: string) => call<AdminProduct>(`/admin/products/${id(value)}`),
    saveProduct: (value: string | null, body: ProductWrite) =>
      call<AdminProduct>(value ? `/admin/products/${id(value)}` : '/admin/products', {
        method: value ? 'PATCH' : 'POST',
        body: { ...body, contentBlocks: contentBlocksForWrite(body.contentBlocks) },
      }),
    setProductPublished: (value: string, publish: boolean, version: number) =>
      call<AdminProduct>(`/admin/products/${id(value)}/${publish ? 'publish' : 'unpublish'}`, {
        method: 'POST',
        body: { version, reason: publish ? '管理员上架商品' : '管理员下架商品' },
      }),
    categories: (query: Query = {}) => call<Page<AdminCategory>>('/admin/categories', { query }),
    saveCategory: (value: string | null, body: CategoryWrite) =>
      call<AdminCategory>(value ? `/admin/categories/${id(value)}` : '/admin/categories', {
        method: value ? 'PATCH' : 'POST',
        body,
      }),
    assets: (query: Query = {}) => call<Page<AdminAsset>>('/admin/files', { query }),
    asset: (value: string) => call<AdminAsset>(`/admin/files/${id(value)}`),
    presign: (body: UploadInput) =>
      call<UploadTicket>('/admin/files/presign', { method: 'POST', body }),
    complete: (value: string) =>
      call<AdminAsset>(`/admin/files/${id(value)}/complete`, { method: 'POST', body: {} }),
    disableAsset: (value: string, version: number, reason: string) =>
      call<AdminAsset>(`/admin/files/${id(value)}`, {
        method: 'PATCH',
        body: { version, status: 'DISABLED', reason },
      }),
    article: (value: string) => call<AdminArticle>(`/admin/articles/${id(value)}`),
    articles: (query: Query = {}) => call<Page<AdminArticle>>('/admin/articles', { query }),
    saveArticle: (value: string | null, body: ArticleWrite) =>
      call<AdminArticle>(value ? `/admin/articles/${id(value)}` : '/admin/articles', {
        method: value ? 'PATCH' : 'POST',
        body: {
          version: body.version,
          title: body.title,
          coverAssetId: body.coverAssetId,
          status: body.status,
          contentBlocks: contentBlocksForWrite(body.contentBlocks),
          ...(body.authorName !== undefined ? { authorName: body.authorName } : {}),
          ...(body.categoryName !== undefined ? { categoryName: body.categoryName } : {}),
        },
      }),
  };
}
export type AdminContentApi = ReturnType<typeof createAdminContentApi>;
