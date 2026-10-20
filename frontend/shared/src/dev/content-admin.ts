import { ApiError, type Transport } from '../http';
import { RuntimeURL } from '../runtime-url';
import type { DevCatalogStore } from './catalog';
import type {
  AdminArticle,
  AdminCategory,
  AdminProduct,
  ProductWrite,
  UploadInput,
  UploadTicket,
  HomeDraft,
  ContentBlockInput,
} from '../content/admin-types';
import type { ContentBlock, HomeView, Target } from '../contracts';
import type { CourseInfo, ProductDetail } from '../content/types';
import type { AdminLearningAsset } from '../learning/admin-types';
import { normalizeContentBlocks, validateHomePublish } from '../content/validation';
import { validateUpload } from '../content/upload';
import { targetToRoute } from '../content/routing';

function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
  status,
  body: { code, message, data: copy(data), requestId: 'DEV-admin' },
});

/** Isolated frontend fixtures only; this does not implement server authorization. */
export function createDevAdminHandler(store: DevCatalogStore) {
  const assets: AdminLearningAsset[] = [
    {
      id: 'DEV-public-image',
      version: 1,
      name: '开发占位图片.png',
      extension: 'PNG',
      mime: 'image/png',
      bytes: 1024,
      purpose: 'PUBLIC_IMAGE',
      status: 'READY',
      scanStatus: 'DEV_FIXTURE_ONLY',
      publicUrl: '/static/catalog.svg',
    },
  ];
  const categories: AdminCategory[] = ['BOOK', 'COURSE', 'MEMBERSHIP'].map((type, index) => ({
    id: index === 0 ? 'DEV-chinese' : `DEV-category-${type}`,
    version: 1,
    name: type === 'BOOK' ? '语文' : type === 'COURSE' ? '课程' : '会员',
    type: type as AdminCategory['type'],
    parentId: null,
    sortNo: index,
    enabled: true,
  }));
  store.categories = categories.map(({ id, name, type }) => ({ id, name, type }));
  const products: AdminProduct[] = store.products.map((p, index) => ({
    id: p.id,
    version: p.version,
    type: p.type,
    title: p.title,
    subtitle: p.subtitle,
    priceFen: p.priceFen,
    originalPriceFen: p.originalPriceFen,
    coverAssetId: 'DEV-public-image',
    sortNo: index,
    categoryIds: [categories.find((c) => c.type === p.type)!.id],
    contentBlocks: normalizeContentBlocks(p.contentBlocks),
    status: p.status,
    typeEditable: false,
    ...(p.type === 'BOOK'
      ? { book: { quantityLimit: p.book.quantityLimit, shippingSummary: p.book.shippingSummary } }
      : {}),
    ...(p.type === 'MEMBERSHIP' ? { membership: copy(p.membership) } : {}),
  }));
  const articles: AdminArticle[] = store.articles.map((p) => ({
    id: p.id,
    version: 1,
    title: p.title,
    coverAssetId: null,
    contentBlocks: normalizeContentBlocks(p.contentBlocks),
    status: 'PUBLISHED',
  }));
  const tickets = new Map<string, UploadTicket>();
  let sequence = 1;
  const nextId = (kind: string) => `DEV-${kind}-new-${sequence++}`;
  const conflict = (version: number, current: number) => {
    if (!Number.isInteger(version) || version !== current)
      throw new ApiError('VERSION_CONFLICT', '内容已变化，请保留编辑后刷新', null, {}, 409);
  };
  const invalid = (message: string) => {
    throw new ApiError('VALIDATION_ERROR', message, null, {}, 422);
  };
  const find = <T extends { id: string }>(items: T[], id: string): T => {
    const item = items.find((v) => v.id === id);
    if (!item) throw new ApiError('NOT_FOUND', '内容不存在', null, {}, 404);
    return item;
  };
  const blocks = (input: ContentBlockInput[]): ContentBlock[] =>
    normalizeContentBlocks(input).map((b) =>
      b.type === 'TEXT' ? b : { ...b, url: image(b.assetId) },
    );
  let learningHooks: {
    courseInfo(productId: string): CourseInfo | null;
    validatePublish(productId: string): void;
    productChanged(product: AdminProduct): void;
  } | null = null;
  function syncProduct(p: AdminProduct) {
    if (
      p.type === 'MEMBERSHIP' &&
      p.status === 'ON_SALE' &&
      products.some(
        (other) =>
          other.id !== p.id &&
          other.type === 'MEMBERSHIP' &&
          other.status === 'ON_SALE' &&
          other.membership?.durationMonths === p.membership?.durationMonths,
      )
    )
      invalid('每个会员周期最多启用一个方案');
    const index = store.products.findIndex((item) => item.id === p.id);
    const previous = store.products[index];
    if (p.status === 'DRAFT') {
      if (index >= 0) store.products.splice(index, 1);
      learningHooks?.productChanged(p);
      return;
    }
    const base = {
      id: p.id,
      version: p.version,
      title: p.title,
      subtitle: p.subtitle,
      priceFen: p.priceFen,
      originalPriceFen: p.originalPriceFen,
      coverUrl: p.coverAssetId ? image(p.coverAssetId) : null,
      status: p.status,
      contentBlocks: blocks(p.contentBlocks),
      allowedActions: (p.status === 'ON_SALE'
        ? ['BUY']
        : (previous?.allowedActions.filter((a) => a !== 'BUY') ??
          [])) as ProductDetail['allowedActions'],
    };
    const value: ProductDetail =
      p.type === 'BOOK'
        ? {
            ...base,
            type: 'BOOK',
            book: {
              ...p.book!,
              availableStock: previous?.type === 'BOOK' ? previous.book.availableStock : 0,
            },
          }
        : p.type === 'MEMBERSHIP'
          ? { ...base, type: 'MEMBERSHIP', membership: p.membership! }
          : {
              ...base,
              type: 'COURSE',
              course:
                learningHooks?.courseInfo(p.id) ??
                (previous?.type === 'COURSE'
                  ? previous.course
                  : {
                      courseId: p.id,
                      validityType: 'PERMANENT',
                      validityDays: null,
                      entitlement: 'NONE',
                      chaptersSummary: 0,
                    }),
            };
    if (index < 0) store.products.push(value);
    else store.products[index] = value;
    const mapped = store as DevCatalogStore & { productCategoryIds?: Record<string, string[]> };
    if (mapped.productCategoryIds) mapped.productCategoryIds[p.id] = [...p.categoryIds];
    learningHooks?.productChanged(p);
  }
  for (const p of products) {
    const mapped = store as DevCatalogStore & { productCategoryIds?: Record<string, string[]> };
    if (mapped.productCategoryIds) mapped.productCategoryIds[p.id] = [...p.categoryIds];
  }
  function productView(product: AdminProduct) {
    const catalogProduct = store.products.find((value) => value.id === product.id);
    return {
      ...product,
      ...(product.type === 'BOOK' && product.book && catalogProduct?.type === 'BOOK'
        ? {
            book: { ...product.book, availableStock: catalogProduct.book.availableStock },
            allowedActions: ['ADJUST_INVENTORY'],
          }
        : { allowedActions: [] }),
    };
  }
  function normalizedProduct(input: ProductWrite): ProductWrite {
    if (
      !['BOOK', 'COURSE', 'MEMBERSHIP'].includes(input.type) ||
      !input.title?.trim() ||
      input.title.trim().length > 100
    )
      invalid('商品类型或标题无效');
    if (!Number.isInteger(input.priceFen) || input.priceFen < 1 || input.priceFen > 100000000)
      invalid('价格无效');
    if (
      input.originalPriceFen !== null &&
      (!Number.isInteger(input.originalPriceFen) ||
        input.originalPriceFen < input.priceFen ||
        input.originalPriceFen > 100000000)
    )
      throw new ApiError(
        'VALIDATION_ERROR',
        '原价不能低于售价，请先在商品编辑中调整原价',
        null,
        { originalPriceFen: '原价须为不低于售价且不超过1000000元的整数分，或留空' },
        422,
      );
    if (!Number.isInteger(input.sortNo) || !Array.isArray(input.categoryIds))
      invalid('分类或排序无效');
    for (const id of input.categoryIds) {
      const c = find(categories, id);
      if (!c.enabled || c.type !== input.type) invalid('分类不可用或类型不匹配');
    }
    if (input.coverAssetId) image(input.coverAssetId);
    const contentBlocks = normalizeContentBlocks(input.contentBlocks);
    blocks(contentBlocks);
    const value: ProductWrite = {
      version: input.version,
      type: input.type,
      title: input.title.trim(),
      subtitle: input.subtitle?.trim() || null,
      priceFen: input.priceFen,
      originalPriceFen: input.originalPriceFen,
      coverAssetId: input.coverAssetId || null,
      categoryIds: [...new Set(input.categoryIds)],
      sortNo: input.sortNo,
      contentBlocks,
    };
    if (input.type === 'BOOK') {
      if (
        !input.book ||
        !Number.isInteger(input.book.quantityLimit) ||
        input.book.quantityLimit < 1 ||
        input.book.quantityLimit > 99 ||
        !input.book.shippingSummary?.trim()
      )
        invalid('图书限购或配送信息无效');
      value.book = copy(input.book!);
    }
    if (input.type === 'MEMBERSHIP') {
      if (!input.membership || ![6, 12].includes(input.membership.durationMonths))
        invalid('会员期限无效');
      value.membership = copy(input.membership!);
    }
    return value;
  }
  let draft: HomeDraft = {
    revision: store.home.revision,
    banners: store.home.banners.map((item, sortNo) => ({
      id: item.id,
      title: item.title,
      target: copy(item.target),
      assetId: 'DEV-public-image',
      sortNo,
      enabled: true,
    })),
    shortcuts: store.home.shortcuts.map((item, sortNo) => ({
      id: item.id,
      label: item.label,
      target: copy(item.target),
      assetId: 'DEV-public-image',
      sortNo,
      enabled: true,
    })),
    sections: store.home.sections.map((item, sortNo) => ({
      id: item.id,
      type: item.type,
      title: item.title,
      moreTarget: copy(item.moreTarget),
      productIds: item.products.map((product) => product.id),
      articleId: item.article?.id ?? null,
      paperIds: item.papers?.map((paper) => paper.id) ?? [],
      sortNo,
      enabled: true,
    })),
  };
  function currentRevision(revision: number) {
    if (revision !== draft.revision)
      throw new ApiError(
        'VERSION_CONFLICT',
        '已被其他编辑修改，请先保留本地内容再刷新',
        null,
        {},
        409,
      );
  }
  function image(id: string) {
    const asset = assets.find(
      (item) => item.id === id && item.purpose === 'PUBLIC_IMAGE' && item.status === 'READY',
    );
    if (!asset?.publicUrl)
      throw new ApiError('FILE_NOT_READY', '引用的公开图片尚未就绪', null, {}, 409);
    return asset.publicUrl;
  }
  function target(value: Target): Target {
    targetToRoute(value);
    if (
      (value.type === 'PRODUCT' &&
        !store.products.some((item) => item.id === value.id && item.status === 'ON_SALE')) ||
      (value.type === 'ARTICLE' && !store.articles.some((item) => item.id === value.id))
    )
      throw new ApiError('NOT_FOUND', '引用的内容不存在或已下架', null, {}, 404);
    return copy(value);
  }
  function active<T extends { enabled: boolean; sortNo: number }>(items: T[]) {
    return items.filter((item) => item.enabled).sort((a, b) => a.sortNo - b.sortNo);
  }
  function render(): HomeView {
    return {
      revision: draft.revision,
      banners: active(draft.banners).map((item) => ({
        id: item.id,
        title: item.title,
        imageUrl: image(item.assetId),
        target: target(item.target),
      })),
      shortcuts: active(draft.shortcuts).map((item) => ({
        id: item.id,
        label: item.label,
        iconUrl: image(item.assetId),
        target: target(item.target),
      })),
      sections: active(draft.sections).map((item) => {
        const productIds = ['ARTICLE_CARD', 'PAPER_GRID'].includes(item.type)
          ? []
          : item.productIds;
        const products = productIds.map((id) => {
          const product = store.products.find(
            (value) => value.id === id && value.status === 'ON_SALE',
          );
          if (!product) throw new ApiError('NOT_FOUND', '模块商品不存在或已下架', null, {}, 404);
          return copy(product);
        });
        const paperIds = item.type === 'PAPER_GRID' ? (item.paperIds ?? []) : [];
        const papers = paperIds.map((id) => {
          const paper = store.papers.find((value) => value.id === id);
          if (!paper) throw new ApiError('NOT_FOUND', '模块试卷不存在', null, {}, 404);
          return {
            id: paper.id,
            title: paper.title,
            coverUrl: paper.coverUrl ?? null,
            stage: paper.stage,
            schoolStage: paper.schoolStage,
            grade: paper.grade,
            year: paper.year,
            subject: paper.subject,
            paperType: paper.paperType,
            summary: paper.summary,
            attachmentFormats: paper.attachmentFormats,
            accessCondition: paper.accessCondition,
            resourceStatus: paper.resourceStatus,
          };
        });
        const article =
          item.type === 'ARTICLE_CARD'
            ? store.articles.find((value) => value.id === item.articleId)
            : null;
        if (item.type === 'ARTICLE_CARD' && !article)
          throw new ApiError('NOT_FOUND', '模块图文不存在', null, {}, 404);
        return {
          id: item.id,
          title: item.title,
          type: item.type,
          products,
          ...(papers.length ? { papers } : {}),
          moreTarget: item.moreTarget ? target(item.moreTarget) : null,
          article: article
            ? { id: article.id, title: article.title, coverUrl: article.coverUrl }
            : null,
        };
      }),
    };
  }
  const transport: Transport = async (request) => {
    const url = new RuntimeURL(request.url);
    const path = url.pathname.replace(/^\/api\/v1/, '');
    try {
      const page = <
        T extends {
          title?: string;
          name?: string;
          type?: string;
          status?: string;
          purpose?: string;
          enabled?: boolean;
          sortNo?: number;
        },
      >(
        items: T[],
      ) => {
        const page = Number(url.searchParams.get('page') || 1),
          pageSize = Number(url.searchParams.get('pageSize') || 20);
        if (
          !Number.isInteger(page) ||
          page < 1 ||
          !Number.isInteger(pageSize) ||
          pageSize < 1 ||
          pageSize > 100
        )
          invalid('分页无效');
        const keyword = (url.searchParams.get('keyword') || '').trim().toLowerCase();
        const filtered = items
          .filter(
            (v) =>
              (!keyword || (v.title ?? v.name ?? '').toLowerCase().includes(keyword)) &&
              (['type', 'status', 'purpose', 'enabled'] as const).every(
                (key) => !url.searchParams.has(key) || String(v[key]) === url.searchParams.get(key),
              ),
          )
          .sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0));
        return {
          items: filtered.slice((page - 1) * pageSize, page * pageSize),
          page,
          pageSize,
          total: filtered.length,
        };
      };
      const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
      if (parts[0] === 'dev' && parts[1] === 'uploads' && request.method === 'PUT') {
        const asset = find(assets, parts[2]!);
        const ticket = tickets.get(asset.id);
        if (asset.status !== 'PENDING')
          throw new ApiError('FILE_ALREADY_UPLOADED', '上传凭证已使用', null, {}, 409);
        if (!ticket || Date.parse(ticket.expiresAt) <= Date.now())
          throw new ApiError('UPLOAD_EXPIRED', '上传凭证已过期', null, {}, 409);
        const token = Object.entries(request.headers).find(
          ([key]) => key.toLowerCase() === 'x-upload-token',
        )?.[1];
        if (token !== ticket.uploadToken)
          throw new ApiError('FORBIDDEN', '开发上传凭证不匹配', null, {}, 403);
        const body = request.body;
        const size =
          typeof Blob !== 'undefined' && body instanceof Blob
            ? body.size
            : body instanceof ArrayBuffer
              ? body.byteLength
              : ArrayBuffer.isView(body)
                ? body.byteLength
                : -1;
        if (size !== asset.bytes) invalid('传输字节数与声明不一致');
        asset.status = 'UPLOADED';
        asset.version++;
        return reply(null);
      }
      if (path === '/admin/categories' && request.method === 'GET') return reply(page(categories));
      if (parts[1] === 'categories' && parts[2] && request.method === 'GET')
        return reply(find(categories, parts[2]));
      if (parts[1] === 'categories' && (request.method === 'POST' || request.method === 'PATCH')) {
        const input = request.body as AdminCategory;
        const previous = parts[2] ? find(categories, parts[2]) : undefined;
        conflict(input.version, previous?.version ?? 0);
        if (
          !input.name?.trim() ||
          input.name.trim().length > 100 ||
          !['BOOK', 'COURSE', 'MEMBERSHIP'].includes(input.type) ||
          !Number.isInteger(input.sortNo) ||
          typeof input.enabled !== 'boolean'
        )
          invalid('分类字段无效');
        if (
          previous &&
          previous.type !== input.type &&
          (products.some((p) => p.categoryIds.includes(previous.id)) ||
            categories.some((c) => c.parentId === previous.id))
        )
          invalid('被引用分类不能更改类型');
        let parentId = input.parentId;
        const visited = new Set<string>(previous ? [previous.id] : []);
        while (parentId) {
          if (visited.has(parentId)) invalid('分类不能循环引用');
          visited.add(parentId);
          const parent = find(categories, parentId);
          if (parent.type !== input.type) invalid('父分类类型不匹配');
          parentId = parent.parentId;
        }
        const value: AdminCategory = {
          id: previous?.id ?? nextId('category'),
          version: (previous?.version ?? 0) + 1,
          name: input.name.trim(),
          type: input.type,
          parentId: input.parentId || null,
          sortNo: input.sortNo,
          enabled: input.enabled,
        };
        if (previous) categories.splice(categories.indexOf(previous), 1, value);
        else categories.push(value);
        store.categories = categories
          .filter((c) => c.enabled)
          .sort((a, b) => a.sortNo - b.sortNo)
          .map(({ id, name, type }) => ({ id, name, type }));
        return reply(value);
      }
      if (path === '/admin/products' && request.method === 'GET')
        return reply(
          page(
            products
              .filter(
                (p) =>
                  !url.searchParams.get('categoryId') ||
                  p.categoryIds.includes(url.searchParams.get('categoryId')!),
              )
              .map(productView),
          ),
        );
      if (parts[1] === 'products' && parts[2] && request.method === 'GET')
        return reply(productView(find(products, parts[2])));
      if (parts[1] === 'products' && (request.method === 'PATCH' || request.method === 'POST')) {
        const previous = parts[2] ? find(products, parts[2]) : undefined;
        const input = request.body as ProductWrite;
        conflict(input.version, previous?.version ?? 0);
        if (parts[3] === 'publish' || parts[3] === 'unpublish') {
          if (!previous) invalid('商品不存在');
          if (
            parts[3] === 'publish' &&
            previous!.type === 'COURSE' &&
            !learningHooks &&
            !store.products.some((p) => p.id === previous!.id && p.type === 'COURSE')
          )
            throw new ApiError(
              'INVALID_STATE',
              '请先通过课程管理配置课程元数据；当前开发场景尚未接入新课程配置',
              null,
              {},
              409,
            );
          if (parts[3] === 'publish' && previous!.type === 'COURSE')
            learningHooks?.validatePublish(previous!.id);
          if (parts[3] === 'publish') normalizedProduct(previous!);
          if (parts[3] === 'unpublish' && previous!.status === 'DRAFT')
            throw new ApiError('INVALID_STATE', '草稿不能下架', null, {}, 409);
          const value: AdminProduct = {
            ...previous!,
            version: previous!.version + 1,
            status: parts[3] === 'publish' ? 'ON_SALE' : 'OFF_SALE',
          };
          syncProduct(value);
          products.splice(products.indexOf(previous!), 1, value);
          return reply(value);
        }
        if (previous && previous.type !== input.type) invalid('已有商品类型不可修改');
        if (
          previous?.type === 'MEMBERSHIP' &&
          previous.membership?.durationMonths !== input.membership?.durationMonths
        )
          throw new ApiError('VERSION_CONFLICT', '会员期限需通过独立方案编辑', null, {}, 409);
        const value: AdminProduct = {
          ...normalizedProduct(input),
          id: previous?.id ?? nextId('product'),
          version: (previous?.version ?? 0) + 1,
          status: previous?.status ?? 'DRAFT',
          typeEditable: false,
        };
        syncProduct(value);
        if (previous) products.splice(products.indexOf(previous), 1, value);
        else products.push(value);
        return reply(value);
      }
      if (path === '/admin/articles' && request.method === 'GET') return reply(page(articles));
      if (parts[1] === 'articles' && parts[2] && request.method === 'GET')
        return reply(find(articles, parts[2]));
      if (parts[1] === 'articles' && (request.method === 'POST' || request.method === 'PATCH')) {
        const input = request.body as AdminArticle;
        const previous = parts[2] ? find(articles, parts[2]) : undefined;
        conflict(input.version, previous?.version ?? 0);
        if (
          !input.title?.trim() ||
          input.title.trim().length > 100 ||
          !['DRAFT', 'PUBLISHED', 'UNPUBLISHED'].includes(input.status)
        )
          invalid('图文标题或状态无效');
        const contentBlocks = normalizeContentBlocks(input.contentBlocks);
        const publicBlocks = blocks(contentBlocks);
        const coverUrl = input.coverAssetId ? image(input.coverAssetId) : null;
        if (input.status === 'PUBLISHED' && !contentBlocks.length) invalid('发布图文需要正文');
        const value: AdminArticle = {
          id: previous?.id ?? nextId('article'),
          version: (previous?.version ?? 0) + 1,
          title: input.title.trim(),
          coverAssetId: input.coverAssetId || null,
          contentBlocks,
          status: input.status,
        };
        if (previous) articles.splice(articles.indexOf(previous), 1, value);
        else articles.push(value);
        store.articles = store.articles.filter((a) => a.id !== value.id);
        if (value.status === 'PUBLISHED')
          store.articles.push({
            id: value.id,
            title: value.title,
            coverUrl,
            contentBlocks: publicBlocks,
          });
        return reply(value);
      }
      if (path === '/admin/files/presign' && request.method === 'POST') {
        const input = request.body as UploadInput;
        validateUpload(input);
        if (!['PUBLIC_IMAGE', 'PAPER_ATTACHMENT', 'COURSE_VIDEO'].includes(input.purpose))
          invalid('后台上传用途无效');
        const id = nextId('asset');
        const ticket = {
          id,
          uploadUrl: `https://dev.local/dev/uploads/${id}`,
          uploadToken: `DEV-upload-${id}-${sequence++}`,
          expiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
        };
        assets.push({
          ...input,
          id,
          version: 1,
          extension: input.name.split('.').at(-1)!.toUpperCase(),
          status: 'PENDING',
          scanStatus: 'DEV_FIXTURE_ONLY',
          publicUrl: null,
        });
        tickets.set(id, ticket);
        return reply(ticket);
      }
      if (parts[1] === 'files' && parts[2]) {
        const asset = find(assets, parts[2]);
        if (request.method === 'GET') return reply(asset);
        if (parts[3] === 'complete' && request.method === 'POST') {
          if (request.body && Object.keys(request.body as object).length)
            invalid('完成上传不接受元数据');
          if (asset.status === 'READY') return reply(asset);
          if (asset.status !== 'UPLOADED')
            throw new ApiError('FILE_NOT_READY', '请先完成文件传输', null, {}, 409);
          asset.status = 'READY';
          asset.version++;
          // Explicit DEV media metadata: uploaded fixture videos stand in for the 60s sample.
          if (asset.purpose === 'COURSE_VIDEO') asset.durationSeconds = 60;
          asset.publicUrl = asset.purpose === 'PUBLIC_IMAGE' ? '/static/catalog.svg' : null;
          return reply(asset);
        }
        if (request.method === 'PATCH') {
          const input = request.body as { version: number; status: string; reason: string };
          conflict(input.version, asset.version);
          if (input.status !== 'DISABLED' || !input.reason?.trim()) invalid('禁用状态或原因无效');
          asset.status = 'DISABLED';
          asset.version++;
          asset.publicUrl = null;
          return reply(asset);
        }
      }
      if (path === '/admin/home/draft' && request.method === 'GET') return reply(draft);
      if (path === '/admin/home/draft' && request.method === 'PUT') {
        const input = copy(request.body as HomeDraft);
        currentRevision(input.revision);
        if (
          !Array.isArray(input.banners) ||
          !Array.isArray(input.shortcuts) ||
          !Array.isArray(input.sections)
        )
          throw new ApiError('VALIDATION_ERROR', '草稿结构不完整', null, {}, 422);
        draft = { ...input, revision: draft.revision + 1 };
        return reply(draft);
      }
      if (path === '/admin/home/preview' && request.method === 'GET') return reply(render());
      if (path === '/admin/home/publish' && request.method === 'POST') {
        const input = request.body as { revision: number; reason: string };
        currentRevision(input.revision);
        const fieldErrors = validateHomePublish(draft);
        if (!input.reason?.trim()) fieldErrors.reason = '请填写发布原因';
        if (Object.keys(fieldErrors).length)
          throw new ApiError('VALIDATION_ERROR', '请补全发布内容', null, fieldErrors, 422);
        const published = render();
        store.home = copy(published);
        return reply(published);
      }
      if (path === '/admin/files' && request.method === 'GET') return reply(page(assets));
      const assetDetail = path.match(/^\/admin\/files\/([^/]+)$/);
      if (assetDetail && request.method === 'GET') {
        const asset = assets.find((item) => item.id === decodeURIComponent(assetDetail[1]!));
        return asset ? reply(copy(asset)) : reply(null, 404, 'NOT_FOUND', '素材不存在');
      }
      return reply(null, 404, 'NOT_FOUND', '此开发场景尚未接入');
    } catch (error) {
      if (error instanceof ApiError)
        return reply(
          { fieldErrors: error.fieldErrors },
          error.status || 422,
          error.code,
          error.message,
        );
      return reply(null, 422, 'VALIDATION_ERROR', '提交内容格式不正确');
    }
  };
  return Object.assign(transport, {
    assets,
    products,
    syncProduct,
    validateProduct: normalizedProduct,
    blocks,
    setLearningHooks(hooks: NonNullable<typeof learningHooks>) {
      learningHooks = hooks;
    },
  });
}
