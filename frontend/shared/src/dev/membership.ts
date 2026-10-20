import type { MembershipView } from '../contracts';
import type { Transport } from '../http';
import { RuntimeURL } from '../runtime-url';
import type { DevCatalogStore } from './catalog';
import type { DevDigitalContent } from './digital-content';
import type { AdminMembershipRecord } from '../learning/admin-types';

interface Grant {
  id: string;
  actor: string;
  paidAt: string;
  months: 6 | 12;
  productId: string;
  revoked: boolean;
  paymentSequence: number;
  kind: 'PURCHASE' | 'RENEWAL';
}

/** Fixture-only calendar projection; production pages always read the server projection. */
function plusShanghaiMonths(timestamp: number, months: number): number {
  const offset = 8 * 60 * 60 * 1000;
  const local = new Date(timestamp + offset);
  const day = local.getUTCDate();
  local.setUTCDate(1);
  local.setUTCMonth(local.getUTCMonth() + months);
  const lastDay = new Date(
    Date.UTC(local.getUTCFullYear(), local.getUTCMonth() + 1, 0),
  ).getUTCDate();
  local.setUTCDate(Math.min(day, lastDay));
  return local.getTime() - offset;
}

export function createDevMembershipHandler(
  catalog: DevCatalogStore,
  options: {
    now?: () => number;
    fileUrls?: Readonly<Record<string, string>>;
    fileReady?: (fileId: string) => boolean;
  } = {},
) {
  const now = options.now ?? Date.now;
  const grants = new Map<string, Grant>();
  let paymentSequence = 0;
  function projection(actor: string) {
    const history = [...grants.values()]
      .filter((grant) => grant.actor === actor)
      .sort(
        (a, b) =>
          Date.parse(a.paidAt) - Date.parse(b.paidAt) || a.paymentSequence - b.paymentSequence,
      );
    const activeSources = history.filter((grant) => !grant.revoked);
    let end = 0;
    const intervals = activeSources.map((grant) => {
      const start = Math.max(Date.parse(grant.paidAt), end);
      end = plusShanghaiMonths(start, grant.months);
      return { id: grant.id, start, end };
    });
    const current = intervals.find((interval) => interval.start <= now() && now() < interval.end);
    return { history, intervals, end, current };
  }
  function membership(actor: string): MembershipView {
    const { history, intervals, end, current } = projection(actor);
    return {
      level: 'STANDARD',
      state: current ? 'ACTIVE' : history.length ? 'EXPIRED' : 'NONE',
      startsAt: intervals.length ? new Date(intervals[0]!.start).toISOString() : null,
      expiresAt: intervals.length ? new Date(end).toISOString() : null,
      canDownload: !!current,
      plans: catalog.products.flatMap((product) =>
        product.type === 'MEMBERSHIP' && product.status === 'ON_SALE'
          ? [
              {
                productId: product.id,
                durationMonths: product.membership.durationMonths,
                priceFen: product.priceFen,
                projectedExpiresAt: new Date(
                  plusShanghaiMonths(Math.max(now(), end), product.membership.durationMonths),
                ).toISOString(),
              },
            ]
          : [],
      ),
    };
  }
  const content: DevDigitalContent = {
    projectProduct(product, actor) {
      if (product.type !== 'MEMBERSHIP') return product;
      return {
        ...product,
        allowedActions: [
          ...(product.status === 'ON_SALE' ? ['BUY' as const] : []),
          ...(membership(actor).canDownload ? ['VIEW_CONTENT' as const] : []),
        ],
      };
    },
    projectPaper(paper, actor) {
      const allowed = membership(actor).canDownload;
      return {
        ...paper,
        attachments: paper.attachments.map((file) => ({
          ...file,
          canPreview:
            allowed && !!options.fileUrls?.[file.id] && options.fileReady?.(file.id) !== false,
          canDownload:
            allowed && !!options.fileUrls?.[file.id] && options.fileReady?.(file.id) !== false,
        })),
      };
    },
    paid(order, product, actor) {
      if (product.type !== 'MEMBERSHIP') return false;
      if (grants.has(order.id)) return true;
      const months = product.membership.durationMonths;
      if (
        !actor ||
        !order.paidAt ||
        !Number.isFinite(Date.parse(order.paidAt)) ||
        ![6, 12].includes(months)
      )
        return false;
      grants.set(order.id, {
        id: order.id,
        actor,
        paidAt: order.paidAt,
        months,
        productId: product.id,
        revoked: false,
        paymentSequence: ++paymentSequence,
        kind: [...grants.values()].some((grant) => grant.actor === actor) ? 'RENEWAL' : 'PURCHASE',
      });
      return true;
    },
    refunded(order) {
      const grant = grants.get(order.id);
      if (grant && order.paidAmountFen > 0 && order.refundedAmountFen === order.paidAmountFen)
        grant.revoked = true;
    },
  };
  const transport: Transport = async (request) => {
    const path = new RuntimeURL(request.url, 'http://localhost').pathname.split('/api/v1')[1] ?? '';
    const actor = request.headers.Authorization?.replace(/^Bearer /, '') ?? '';
    const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
      status,
      body: { code, message, data: JSON.parse(JSON.stringify(data)), requestId: 'DEV-membership' },
    });
    if (path === '/membership/me' && request.method === 'GET') return reply(membership(actor));
    const match = path.match(/^\/papers\/([^/]+)\/files\/([^/]+)\/authorize$/);
    if (!match || request.method !== 'POST')
      return reply(null, 404, 'NOT_FOUND', '此DEV会员操作未接入');
    if (!actor) return reply(null, 401, 'UNAUTHORIZED', '请登录后访问会员资料');
    const view = membership(actor);
    if (!view.canDownload)
      return reply(
        null,
        403,
        view.state === 'EXPIRED' ? 'ENTITLEMENT_EXPIRED' : 'FORBIDDEN',
        '当前没有有效试卷会员权益',
      );
    const body = request.body as { action?: unknown } | null;
    if (!body || !['PREVIEW', 'DOWNLOAD'].includes(String(body.action)))
      return reply(null, 400, 'VALIDATION_ERROR', '资料操作无效');
    let paperId: string, fileId: string;
    try {
      paperId = decodeURIComponent(match[1]!);
      fileId = decodeURIComponent(match[2]!);
    } catch {
      return reply(null, 400, 'VALIDATION_ERROR', '资料编号无效');
    }
    const paper = catalog.papers.find((value) => value.id === paperId);
    const file = paper?.attachments.find((value) => value.id === fileId);
    if (!file) return reply(null, 404, 'NOT_FOUND', '资料文件不存在');
    const url = options.fileUrls?.[file.id];
    if (!url || options.fileReady?.(file.id) === false)
      return reply(null, 409, 'FILE_NOT_READY', 'DEV测试文件尚未配置或文件不可用');
    const { id, name, extension, mime, bytes } = file;
    return reply({
      file: { id, name, extension, mime, bytes },
      url,
      expiresAt: new Date(Math.min(now() + 120_000, Date.parse(view.expiresAt!))).toISOString(),
    });
  };
  function records(): AdminMembershipRecord[] {
    return [...new Set([...grants.values()].map((grant) => grant.actor))].map((actor) => {
      const view = membership(actor);
      return {
        id: actor,
        userId: actor,
        level: view.level,
        status: view.state,
        startsAt: view.startsAt,
        expiresAt: view.expiresAt,
        version: [...grants.values()].filter((grant) => grant.actor === actor).length,
      };
    });
  }
  return { transport, content, records };
}
