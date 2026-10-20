import type { Page, ProductType } from '@jxsc/shared';
import { contentApi, catalogApi } from './content';
import { learningApi } from './learning';

export type ContentKind =
  'PRODUCT' | 'BOOK' | 'COURSE' | 'MEMBERSHIP' | 'PAPER' | 'ARTICLE' | 'COURSE_DETAIL';
export interface ContentOption {
  id: string;
  title: string;
  kind: string;
  status: string;
  available: boolean;
  reason?: string;
  coverUrl: string | null;
}
export interface ContentSelectionSource {
  search(
    kind: ContentKind,
    query: { keyword: string; page: number; pageSize: number; type?: ProductType },
  ): Promise<Page<ContentOption>>;
  resolve(kind: ContentKind, id: string): Promise<ContentOption>;
}
async function cover(id?: string | null) {
  if (!id) return null;
  try {
    const asset = await contentApi.asset(id);
    return asset.status === 'READY' && asset.purpose === 'PUBLIC_IMAGE' ? asset.publicUrl : null;
  } catch {
    return null;
  }
}
async function productOption(
  kind: ContentKind,
  product: Awaited<ReturnType<typeof contentApi.product>>,
): Promise<ContentOption> {
  let id = product.id;
  if (kind === 'COURSE_DETAIL') {
    const detail = await catalogApi.product(product.id);
    if (detail.type !== 'COURSE' || !detail.course?.courseId) throw new Error('课程关联暂不可读');
    id = detail.course.courseId;
  }
  const matches =
    kind === 'PRODUCT' ||
    (kind === 'COURSE_DETAIL' && product.type === 'COURSE') ||
    product.type === kind;
  const available = matches && product.status === 'ON_SALE';
  return {
    id,
    title: product.title,
    kind: product.type,
    status:
      product.status === 'ON_SALE' ? '已上架' : product.status === 'DRAFT' ? '草稿' : '已下架',
    available,
    reason: !matches ? '内容类型与栏目不匹配' : available ? undefined : '商品未上架',
    coverUrl: await cover(product.coverAssetId),
  };
}
export const contentSelectionSource: ContentSelectionSource = {
  async search(kind, query) {
    if (kind === 'ARTICLE') {
      const result = await contentApi.articles({ ...query, status: 'PUBLISHED' });
      return {
        ...result,
        items: await Promise.all(
          result.items.map(async (item) => ({
            id: item.id,
            title: item.title,
            kind,
            status: '已发布',
            available: item.status === 'PUBLISHED',
            coverUrl: await cover(item.coverAssetId),
          })),
        ),
      };
    }
    if (kind === 'PAPER') {
      const result = await learningApi.papers({ ...query, status: 'PUBLISHED' });
      return {
        ...result,
        items: await Promise.all(
          result.items.map(async (item) => ({
            id: item.id,
            title: item.title,
            kind,
            status: '已发布',
            available: item.status === 'PUBLISHED',
            coverUrl: await cover(item.coverAssetId),
          })),
        ),
      };
    }
    const type = kind === 'PRODUCT' ? query.type : kind === 'COURSE_DETAIL' ? 'COURSE' : kind;
    const result = await contentApi.products({
      keyword: query.keyword,
      page: query.page,
      pageSize: query.pageSize,
      type,
      status: 'ON_SALE',
    });
    return {
      ...result,
      items: await Promise.all(
        result.items.map(async (item) => {
          try {
            return await productOption(kind, item);
          } catch {
            return {
              id: `unavailable:${item.id}`,
              title: item.title,
              kind,
              status: '不可用',
              available: false,
              reason: '真实课程关联暂不可读，请重试',
              coverUrl: null,
            };
          }
        }),
      ),
    };
  },
  async resolve(kind, id) {
    if (kind === 'ARTICLE') {
      const item = await contentApi.article(id);
      return {
        id,
        title: item.title,
        kind,
        status: item.status === 'PUBLISHED' ? '已发布' : '未发布',
        available: item.status === 'PUBLISHED',
        reason: item.status !== 'PUBLISHED' ? '图文未发布' : undefined,
        coverUrl: await cover(item.coverAssetId),
      };
    }
    if (kind === 'PAPER') {
      const item = await learningApi.paper(id);
      return {
        id,
        title: item.title,
        kind,
        status: item.status === 'PUBLISHED' ? '已发布' : '未发布',
        available: item.status === 'PUBLISHED',
        reason: item.status !== 'PUBLISHED' ? '试卷未发布' : undefined,
        coverUrl: await cover(item.coverAssetId),
      };
    }
    if (kind === 'COURSE_DETAIL') {
      const course = await learningApi.course(id);
      return productOption(kind, await contentApi.product(course.productId));
    }
    return productOption(kind, await contentApi.product(id));
  },
};
