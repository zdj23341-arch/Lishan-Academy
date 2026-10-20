import type { LoginView } from '../contracts';
import type { Transport } from '../http';
import { createDevCatalogStore, type DevCatalogStore } from './catalog';
import { createDevAdminHandler } from './content-admin';
import { createDevAdminLearningHandler } from './learning-admin';
import { createDevCommerceHandler } from './commerce';
import { createDevLearningHandler } from './learning';
import { createDevMembershipHandler } from './membership';
import { combineDevDigitalContent, type DevDigitalContent } from './digital-content';
import { devPaperSamples } from './paper-assets';
export { createDevCatalogStore } from './catalog';
export function createDevTransport(
  store: DevCatalogStore = createDevCatalogStore(),
  extension?: Transport,
  options: { content?: DevDigitalContent } = {},
): Transport {
  const failed = new Set<string>();
  const login: LoginView = {
    accessToken: 'DEV-front-end-only',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'DEV-user', nickname: '开发示例 · 测试用户', distributor: false },
    permissions: [],
  };
  const adminLogin: LoginView = {
    ...login,
    accessToken: 'DEV-admin-front-end-only',
    user: { id: 'DEV-admin', nickname: '开发示例 · 测试管理员', distributor: false },
    permissions: [
      'CONTENT_EDIT',
      'ORDER_OPERATE',
      'FINANCE_OPERATE',
      'DISTRIBUTION_OPERATE',
      'ANALYTICS_READ',
      'AUDIT_READ',
      'SETTINGS_MANAGE',
      'EXPORT_READ',
    ],
  };
  return async (request) => {
    const actor = request.headers.Authorization?.replace(/^Bearer /, '') ?? '';
    const raw = request.url.split('/api/v1')[1] ?? '';
    const [path, search] = raw.split('?');
    const values = Object.fromEntries(
      (search || '')
        .split('&')
        .filter(Boolean)
        .map((pair) => {
          const [key, value] = pair.split('=');
          return [decodeURIComponent(key!), decodeURIComponent(value || '')];
        }),
    );
    const params = { get: (key: string): string | null => values[key] ?? null };
    const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
      status,
      body: { code, message, data: JSON.parse(JSON.stringify(data)), requestId: 'DEV-request' },
    });
    if (['/auth/dev-login', '/admin/auth/login'].includes(path ?? '') && request.method === 'POST')
      return reply(path === '/admin/auth/login' ? adminLogin : login);
    if (['/auth/logout', '/admin/auth/logout'].includes(path ?? '') && request.method === 'POST')
      return reply(null);
    if (path === '/admin/me')
      return reply({
        id: 'DEV-admin',
        loginName: 'DEV管理员',
        roles: ['SUPER_ADMIN'],
        permissions: adminLogin.permissions,
        passwordChangeRequired: false,
      });
    if (path === '/me') return reply(login.user);
    if (request.method !== 'GET')
      return extension ? extension(request) : reply(null, 404, 'NOT_FOUND', '此开发场景尚未接入');
    if (path === '/home') return reply(store.home);
    if (path === '/categories') {
      const items = store.categories.filter(
        (item) => !params.get('type') || item.type === params.get('type'),
      );
      const page = Number(params.get('page') ?? 1),
        pageSize = Number(params.get('pageSize') ?? 20);
      return reply({
        items: items.slice((page - 1) * pageSize, page * pageSize),
        page,
        pageSize,
        total: items.length,
      });
    }
    if (path === '/products' || path === '/papers') {
      const keyword = params.get('keyword') ?? '';
      if (keyword === 'DEV-ERROR' || (keyword === 'DEV-RETRY' && !failed.has(keyword))) {
        failed.add(keyword);
        return reply(
          null,
          503,
          'EXTERNAL_SERVICE_UNAVAILABLE',
          'DEV可重试错误；DEV-RETRY仅首次失败',
        );
      }
      const list = path === '/products' ? store.products : store.papers;
      let items = list.filter(
        (item) =>
          (!('status' in item) || item.status === 'ON_SALE') &&
          (keyword === 'DEV-RETRY' || item.title.includes(keyword)) &&
          ['type', 'stage', 'grade', 'schoolStage', 'year', 'subject', 'paperType'].every(
            (key) =>
              !params.get(key) ||
              (item as unknown as Record<string, unknown>)[key] === params.get(key),
          ),
      );
      if (params.get('categoryId'))
        items = items.filter((item) =>
          store.productCategoryIds[item.id]?.includes(params.get('categoryId')!),
        );
      const sort = params.get('sort');
      if (sort === 'PRICE_ASC' || sort === 'PRICE_DESC')
        items.sort(
          (a, b) => ('priceFen' in a ? a.priceFen : 0) - ('priceFen' in b ? b.priceFen : 0),
        );
      if (sort === 'PRICE_DESC' || sort === 'NEWEST') items.reverse();
      items = items.map((item) =>
        'attachments' in item ? (options.content?.projectPaper?.(item, actor) ?? item) : item,
      );
      const page = Number(params.get('page') ?? 1),
        pageSize = Number(params.get('pageSize') ?? 20);
      return reply({
        items: items.slice((page - 1) * pageSize, page * pageSize),
        page,
        pageSize,
        total: items.length,
      });
    }
    const match = path?.match(/^\/(products|papers|articles)\/([^/]+)$/);
    if (match) {
      if (match[1] === 'products') {
        const product = store.products.find((value) => value.id === decodeURIComponent(match[2]!));
        return product
          ? reply(options.content?.projectProduct(product, actor) ?? product)
          : reply(null, 404, 'NOT_FOUND', '内容不存在或已下架');
      }
      const list = match[1] === 'papers' ? store.papers : store.articles;
      const item = list.find((value) => value.id === decodeURIComponent(match[2]!));
      return item
        ? reply(
            'attachments' in item ? (options.content?.projectPaper?.(item, actor) ?? item) : item,
          )
        : reply(null, 404, 'NOT_FOUND', '内容不存在或已下架');
    }
    return extension ? extension(request) : reply(null, 404, 'NOT_FOUND', '此开发场景尚未接入');
  };
}
export const devCatalogStore = createDevCatalogStore();
const adminHandler = createDevAdminHandler(devCatalogStore);
const membershipHandler = createDevMembershipHandler(devCatalogStore, {
  fileReady: (id) =>
    adminHandler.assets.some((asset) => asset.id === id && asset.status === 'READY'),
  fileUrls: Object.fromEntries(
    devCatalogStore.papers.flatMap((paper) =>
      paper.attachments.flatMap((file) => {
        const sample = devPaperSamples.find((value) => value.extension === file.extension);
        return sample ? [[file.id, sample.path]] : [];
      }),
    ),
  ),
});
const adminLearningHandler = createDevAdminLearningHandler(devCatalogStore, adminHandler, {
  membershipRecords: membershipHandler.records,
});
const learningHandler = createDevLearningHandler(devCatalogStore, {
  mediaUrl: 'http://127.0.0.1:5173/course-sample.mp4',
  chapters: adminLearningHandler.chapters,
});
learningHandler.grantCourse('DEV-front-end-only', 'DEV-course-off', null);
const digitalContent = combineDevDigitalContent(learningHandler.content, membershipHandler.content);
const commerceHandler = createDevCommerceHandler(devCatalogStore, {
  content: digitalContent,
});
import { createDevDistributionHandler } from './distribution';
import { createDevOperationsHandler } from './operations';

const distributionHandler = createDevDistributionHandler();
const operationsHandler = createDevOperationsHandler();

export const devTransport = createDevTransport(
  devCatalogStore,
  async (request) => {
    const raw = request.url.split('/api/v1')[1] ?? '';
    const [path, search] = raw.split('?');
    const values = Object.fromEntries(
      (search || '')
        .split('&')
        .filter(Boolean)
        .map((pair) => {
          const [key, value] = pair.split('=');
          return [decodeURIComponent(key!), decodeURIComponent(value || '')];
        }),
    );
    const params = { get: (key: string): string | null => values[key] ?? null };

    const distRes = distributionHandler(request, path ?? '', params);
    if (distRes) return distRes;

    const opsRes = operationsHandler(request, path ?? '', params);
    if (opsRes) return opsRes;

    const membership = await membershipHandler.transport(request);
    if (membership.status !== 404) return membership;
    const learning = await learningHandler.transport(request);
    if (learning.status !== 404) return learning;
    const adminLearning = await adminLearningHandler.transport(request);
    if (adminLearning.status !== 404) return adminLearning;
    const result = await commerceHandler(request);
    return result.status === 404 ? adminHandler(request) : result;
  },
  { content: digitalContent },
);
