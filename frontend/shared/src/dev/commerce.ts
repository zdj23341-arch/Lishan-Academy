import type { OrderView } from '../contracts';
import { ApiError, type Transport } from '../http';
import { validateAddress } from '../commerce/address';
import type { AddressView, AddressWrite, QuoteView } from '../commerce/types';
import type { DevCatalogStore } from './catalog';
import type { AftersaleView } from '../commerce/aftersale';
import type { PrepayView } from '../commerce/payment';
import type { AdminAsset, UploadInput, UploadTicket } from '../content/admin-types';
import { validateUpload } from '../content/upload';
import type { ShipmentView } from '../commerce/order';
import { createDevAftersaleAdmin, type DevRefundOutcome } from './aftersale-admin';
import type { DevDigitalContent } from './digital-content';
import type { ProductDetail } from '../content/types';

// Frontend-only shipping fixture: 600 fen per order for the listed mainland provinces.
// Unsupported codes intentionally have no fallback; this is not a production tariff.
const shipping: Readonly<Record<string, number>> = Object.freeze(
  Object.fromEntries(
    [
      '11',
      '12',
      '13',
      '14',
      '15',
      '21',
      '22',
      '23',
      '31',
      '32',
      '33',
      '34',
      '35',
      '36',
      '37',
      '41',
      '42',
      '43',
      '44',
      '45',
      '46',
      '50',
      '51',
      '52',
      '53',
      '54',
      '61',
      '62',
      '63',
      '64',
      '65',
    ].map((code) => [`${code}0000`, 600]),
  ),
);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function object(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
function fail(code: string, message: string, status = 400): never {
  throw new ApiError(code, message, null, {}, status);
}
export function createDevCommerceHandler(
  catalog: DevCatalogStore,
  options: {
    now?: () => number;
    refundOutcome?: DevRefundOutcome;
    content?: DevDigitalContent;
  } = {},
): Transport {
  const now = options.now ?? Date.now;
  const addresses = new Map<string, AddressView>();
  type DevOrder = OrderView & {
    version: number;
    address: AddressView | null;
    createdAt: string;
    refundedGoodsFen: number;
    refundedFreightFen: number;
    aftersaleId?: string;
  };
  const orders = new Map<string, DevOrder>();
  const purchaseSnapshots = new Map<string, { product: ProductDetail; actor: string }>();
  const shipments = new Map<string, ShipmentView>();
  const payments = new Map<string, PrepayView>();
  const aftersales = new Map<string, AftersaleView>();
  const evidence = new Map<string, { asset: AdminAsset; ticket: UploadTicket }>();
  const effects = new Map<
    string,
    { scope: string; fingerprint: string; resourceId: string; response: unknown }
  >();
  const quotes = new Map<
    string,
    {
      view: QuoteView;
      product: string;
      addressId?: string;
      address?: string;
      freight: number;
      orderId?: string;
    }
  >();
  const operations = new Map<string, { body: string; orderId: string }>();
  let sequence = 0;
  const id = (kind: string) => `DEV-${kind}-${++sequence}`;
  const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
    status,
    body: {
      code,
      message,
      data: JSON.parse(JSON.stringify(data)),
      requestId: 'DEV-commerce-request',
    },
  });
  return async (request) => {
    try {
      const raw = request.url.split('/api/v1')[1] ?? request.url;
      const [path, query = ''] = raw.split('?');
      const values = Object.fromEntries(
        query
          .split('&')
          .filter(Boolean)
          .map((pair) => {
            const [key, value = ''] = pair.split('=');
            return [decodeURIComponent(key!), decodeURIComponent(value)];
          }),
      );
      const params = { get: (key: string) => values[key] ?? null };
      const body = object(request.body);
      const actor = request.headers.Authorization?.replace(/^Bearer /, '') ?? '';
      const productView = (product: ProductDetail | undefined) =>
        product && options.content ? options.content.projectProduct(product, actor) : product;
      if (path === '/files/presign' && request.method === 'POST') {
        const input = body as unknown as UploadInput;
        if (input.purpose !== 'AFTERSALE_EVIDENCE')
          fail('VALIDATION_ERROR', '顾客只能上传售后图片');
        validateUpload(input);
        const assetId = id('evidence');
        const ticket: UploadTicket = {
          id: assetId,
          uploadUrl: `https://dev.local/api/v1/dev/evidence-uploads/${assetId}`,
          uploadToken: id('upload-token'),
          expiresAt: new Date(now() + 900000).toISOString(),
        };
        evidence.set(assetId, {
          ticket,
          asset: {
            ...input,
            id: assetId,
            version: 1,
            extension: input.name.split('.').at(-1)!.toUpperCase(),
            status: 'PENDING',
            scanStatus: 'DEV_FIXTURE_ONLY',
            publicUrl: null,
          },
        });
        return reply(ticket);
      }
      const evidenceUpload = path?.match(/^\/dev\/evidence-uploads\/([^/]+)$/);
      if (evidenceUpload && request.method === 'PUT') {
        const value = evidence.get(decodeURIComponent(evidenceUpload[1]!));
        if (!value) fail('NOT_FOUND', '上传凭证不存在', 404);
        const token = Object.entries(request.headers).find(
          ([name]) => name.toLowerCase() === 'x-upload-token',
        )?.[1];
        if (token !== value.ticket.uploadToken || Date.parse(value.ticket.expiresAt) <= now())
          fail('UPLOAD_EXPIRED', '上传凭证无效或过期', 403);
        if (value.asset.status !== 'PENDING') fail('FILE_ALREADY_UPLOADED', '此凭证已经使用', 409);
        const bytes =
          request.body instanceof ArrayBuffer
            ? request.body.byteLength
            : typeof Blob !== 'undefined' && request.body instanceof Blob
              ? request.body.size
              : -1;
        if (bytes !== value.asset.bytes) fail('VALIDATION_ERROR', '上传文件大小不匹配');
        value.asset.status = 'UPLOADED';
        value.asset.version++;
        return reply(null);
      }
      const evidenceComplete = path?.match(/^\/files\/([^/]+)\/complete$/);
      if (evidenceComplete && request.method === 'POST') {
        const value = evidence.get(decodeURIComponent(evidenceComplete[1]!));
        if (!value) fail('NOT_FOUND', '文件不存在', 404);
        if (Object.keys(body).length) fail('VALIDATION_ERROR', '客户端不能声明文件检查结果');
        if (!['UPLOADED', 'READY'].includes(value.asset.status))
          fail('FILE_NOT_READY', '请先完成文件传输', 409);
        value.asset.status = 'READY';
        value.asset.version++;
        return reply(value.asset);
      }
      const findOrder = (orderId: string) => {
        const order = orders.get(orderId);
        if (!order) fail('NOT_FOUND', '订单不存在', 404);
        return order;
      };
      const requireAction = (order: DevOrder, action: OrderView['allowedActions'][number]) => {
        if (!order.allowedActions.includes(action))
          fail('ACTION_NOT_ALLOWED', '当前订单状态不允许此操作', 409);
      };
      const actions = (order: DevOrder) => {
        const result: OrderView['allowedActions'] = [];
        if (order.orderStatus === 'PENDING_PAYMENT') {
          result.push('PAY');
          if (payments.get(order.id)?.state !== 'UNKNOWN') result.push('CANCEL');
        }
        if (order.orderStatus === 'PAID' || order.orderStatus === 'COMPLETED') {
          if (
            !['REQUESTED', 'RETURN_REQUIRED', 'RETURNING', 'PROCESSING', 'FULL'].includes(
              order.refundStatus,
            )
          ) {
            result.push('APPLY_AFTERSALE');
            if (order.fulfillmentStatus === 'SHIPPED') result.push('CONFIRM_RECEIPT');
          }
          if (order.item.type === 'BOOK') result.push('VIEW_LOGISTICS');
          const purchase = purchaseSnapshots.get(order.id);
          if (
            purchase &&
            order.item.type !== 'BOOK' &&
            options.content
              ?.projectProduct(purchase.product, purchase.actor)
              .allowedActions.includes('VIEW_CONTENT')
          )
            result.push('VIEW_CONTENT');
        }
        order.allowedActions = result;
      };
      const effect = (
        scope: string,
        resource: string,
        execute: () => { id: string; response: unknown },
      ) => {
        const key = Object.entries(request.headers)
          .find(([name]) => name.toLowerCase() === 'idempotency-key')?.[1]
          ?.toLowerCase();
        if (!key || !uuid.test(key)) fail('VALIDATION_ERROR', '需要UUID幂等键');
        const fingerprint = JSON.stringify([
          resource,
          Object.fromEntries(Object.entries(body).sort(([a], [b]) => a.localeCompare(b))),
        ]);
        const existing = effects.get(key);
        if (existing) {
          if (existing.scope !== scope || existing.fingerprint !== fingerprint)
            fail('IDEMPOTENCY_CONFLICT', '幂等键已用于不同请求', 409);
          return reply(existing.response);
        }
        if (operations.has(key)) fail('IDEMPOTENCY_CONFLICT', '幂等键已用于创建订单', 409);
        const result = execute();
        const response = JSON.parse(JSON.stringify(result.response));
        effects.set(key, { scope, fingerprint, resourceId: result.id, response });
        return reply(response);
      };
      const page = <T>(items: T[]) => {
        const page = Number(params.get('page') ?? 1),
          pageSize = Number(params.get('pageSize') ?? 20);
        if (
          !Number.isSafeInteger(page) ||
          page < 1 ||
          !Number.isSafeInteger(pageSize) ||
          pageSize < 1 ||
          pageSize > 100
        )
          fail('VALIDATION_ERROR', '分页参数无效');
        return reply({
          items: items.slice((page - 1) * pageSize, page * pageSize),
          page,
          pageSize,
          total: items.length,
        });
      };
      const adminAftersale = createDevAftersaleAdmin({
        cases: aftersales,
        order: findOrder,
        catalog,
        outcome: options.refundOutcome ?? 'PENDING',
        changed: (orderId) => {
          const order = findOrder(orderId);
          const purchase = purchaseSnapshots.get(orderId);
          if (purchase) options.content?.refunded(order, purchase.product, purchase.actor);
          actions(order);
        },
      });
      if (path === '/admin/aftersales' && request.method === 'GET')
        return page(
          [...aftersales.values()]
            .reverse()
            .filter(
              (value) =>
                (!params.get('id') || value.id === params.get('id')) &&
                (!params.get('status') || value.status === params.get('status')) &&
                (!params.get('orderNo') ||
                  findOrder(value.orderId).orderNo.includes(params.get('orderNo')!)),
            )
            .map(adminAftersale.view),
        );
      const adminSingleCaseMatch = path?.match(/^\/admin\/aftersales\/([^/]+)$/);
      if (adminSingleCaseMatch && request.method === 'GET') {
        const value = aftersales.get(decodeURIComponent(adminSingleCaseMatch[1]!));
        if (!value) fail('NOT_FOUND', '售后单不存在', 404);
        return reply(adminAftersale.view(value));
      }
      const adminCaseMatch = path?.match(
        /^\/admin\/aftersales\/([^/]+)\/(preview-decision|approve|reject|receive-return)$/,
      );
      if (adminCaseMatch && request.method === 'POST') {
        const value = aftersales.get(decodeURIComponent(adminCaseMatch[1]!));
        if (!value) fail('NOT_FOUND', '售后单不存在', 404);
        const action = adminCaseMatch[2];
        if (action === 'preview-decision') return reply(adminAftersale.preview(value, body));
        const scope =
          action === 'approve'
            ? 'APPROVE_REFUND'
            : action === 'reject'
              ? 'REJECT_REFUND'
              : 'RECEIVE_RETURN';
        return effect(scope, value.id, () => ({
          id: value.id,
          response:
            action === 'approve'
              ? adminAftersale.approve(value, body)
              : action === 'reject'
                ? adminAftersale.reject(value, body)
                : adminAftersale.receive(value, body),
        }));
      }
      const adminOrder = (order: DevOrder) => {
        const allowedActions: string[] = [];
        if (
          order.item.type === 'BOOK' &&
          order.orderStatus === 'PAID' &&
          !['REQUESTED', 'RETURN_REQUIRED', 'RETURNING', 'PROCESSING', 'FULL'].includes(
            order.refundStatus,
          )
        ) {
          if (order.fulfillmentStatus === 'PENDING_SHIPMENT') allowedActions.push('SHIP');
          if (order.fulfillmentStatus === 'SHIPPED') allowedActions.push('UPDATE_SHIPMENT');
        }
        return { ...order, allowedActions, shipment: shipments.get(order.id) ?? null };
      };
      if (path === '/admin/orders' && request.method === 'GET') {
        const dateFrom = params.get('from') || params.get('createdFrom'),
          dateTo = params.get('to') || params.get('createdTo');
        for (const date of [dateFrom, dateTo])
          if (date && !Number.isFinite(Date.parse(date))) fail('VALIDATION_ERROR', '日期筛选无效');
        return page(
          [...orders.values()]
            .reverse()
            .filter(
              (order) =>
                (!params.get('orderNo') || order.orderNo.includes(params.get('orderNo')!)) &&
                ['orderStatus', 'fulfillmentStatus', 'refundStatus'].every(
                  (field) =>
                    !params.get(field) || order[field as 'orderStatus'] === params.get(field),
                ) &&
                (!dateFrom || Date.parse(order.createdAt) >= Date.parse(dateFrom)) &&
                (!dateTo || Date.parse(order.createdAt) <= Date.parse(dateTo)),
            )
            .map(adminOrder),
        );
      }
      const adminOrderMatch = path?.match(/^\/admin\/orders\/([^/]+)(?:\/(ship))?$/);
      if (adminOrderMatch) {
        const order = findOrder(decodeURIComponent(adminOrderMatch[1]!));
        if (!adminOrderMatch[2] && request.method === 'GET') return reply(adminOrder(order));
        if (adminOrderMatch[2] && request.method === 'POST')
          return effect('SHIP_ORDER', order.id, () => {
            const current = adminOrder(order);
            const existing = shipments.get(order.id);
            if (!current.allowedActions.includes(existing ? 'UPDATE_SHIPMENT' : 'SHIP'))
              fail('ACTION_NOT_ALLOWED', '当前订单不可发货或修改物流', 409);
            if (!Number.isSafeInteger(body.version) || body.version !== order.version)
              fail('VERSION_CONFLICT', '订单已变化，请保留输入并刷新', 409);
            const carrierCode = typeof body.carrierCode === 'string' ? body.carrierCode.trim() : '';
            const trackingNo = typeof body.trackingNo === 'string' ? body.trackingNo.trim() : '';
            const reason = typeof body.reason === 'string' ? body.reason.trim() : '';
            if (
              !carrierCode ||
              carrierCode.length > 40 ||
              !/^[A-Za-z0-9-]{1,100}$/.test(trackingNo) ||
              reason.length > 500 ||
              (existing && !reason)
            )
              fail('VALIDATION_ERROR', '请填写物流信息；修改运单必须说明原因', 422);
            shipments.set(order.id, {
              carrierCode,
              trackingNo,
              shippedQuantity: order.item.quantity,
              status: 'SHIPPED',
              simulated: true,
              shippedAt: existing?.shippedAt ?? new Date(now()).toISOString(),
              receivedAt: null,
              events: [],
            });
            order.fulfillmentStatus = 'SHIPPED';
            order.version++;
            actions(order);
            return { id: order.id, response: adminOrder(order) };
          });
      }
      const inventoryMatch = path?.match(/^\/admin\/products\/([^/]+)\/inventory-adjustments$/);
      if (inventoryMatch && request.method === 'POST') {
        const productId = decodeURIComponent(inventoryMatch[1]!);
        return effect('INVENTORY_ADJUST', productId, () => {
          const product = catalog.products.find((value) => value.id === productId);
          if (!product) fail('NOT_FOUND', '商品不存在', 404);
          if (product.type !== 'BOOK' || !product.book)
            fail('ACTION_NOT_ALLOWED', '仅图书可调整库存', 409);
          const delta = body.delta;
          const reason = typeof body.reason === 'string' ? body.reason.trim() : '';
          if (
            typeof delta !== 'number' ||
            !Number.isSafeInteger(delta) ||
            delta === 0 ||
            !reason ||
            reason.length > 500
          )
            fail('VALIDATION_ERROR', '请输入非零整数调整量及原因', 422);
          const available = product.book.availableStock + delta;
          if (!Number.isSafeInteger(available) || available < 0)
            fail('INSUFFICIENT_STOCK', '库存调整不能侵占订单锁定数量', 409);
          product.book.availableStock = available;
          return { id: product.id, response: { id: product.id } };
        });
      }
      const addressMatch = path?.match(/^\/addresses\/([^/]+)$/);
      if (path === '/addresses' && request.method === 'GET') return page([...addresses.values()]);
      if (
        (path === '/addresses' && request.method === 'POST') ||
        (addressMatch && request.method === 'PATCH')
      ) {
        const fields = [
          'name',
          'phone',
          'provinceCode',
          'cityCode',
          'districtCode',
          'detail',
        ] as const;
        const input = { ...body };
        for (const field of fields) if (typeof input[field] !== 'string') input[field] = '';
        const value = validateAddress(input as unknown as AddressWrite);
        const existing = addressMatch
          ? addresses.get(decodeURIComponent(addressMatch[1]!))
          : undefined;
        if (addressMatch && !existing) fail('NOT_FOUND', '地址不存在', 404);
        if (value.version !== (existing?.version ?? 0))
          fail('VERSION_CONFLICT', '地址已变更，请刷新', 409);
        if (value.isDefault)
          for (const current of addresses.values())
            if (current.id !== existing?.id && current.isDefault) {
              current.isDefault = false;
              current.version++;
            }
        const saved = { ...value, id: existing?.id ?? id('address'), version: value.version + 1 };
        addresses.set(saved.id, saved);
        return reply(saved);
      }
      if (addressMatch && request.method === 'DELETE') {
        if (!addresses.delete(decodeURIComponent(addressMatch[1]!)))
          fail('NOT_FOUND', '地址不存在', 404);
        return reply(null);
      }
      if (path === '/orders/preview' && request.method === 'POST') {
        const product = productView(catalog.products.find((p) => p.id === body.productId));
        if (!product) fail('NOT_FOUND', '商品不存在', 404);
        if (product.status !== 'ON_SALE' || !product.allowedActions.includes('BUY'))
          fail('PRODUCT_UNAVAILABLE', '商品不可购买');
        const quantity = body.quantity;
        if (
          typeof quantity !== 'number' ||
          !Number.isInteger(quantity) ||
          quantity < 1 ||
          quantity > 99
        )
          fail('VALIDATION_ERROR', '数量无效');
        let address: AddressView | undefined;
        let freight = 0;
        if (product.type === 'BOOK') {
          if (
            !product.book ||
            quantity > product.book.quantityLimit ||
            quantity > product.book.availableStock
          )
            fail('VALIDATION_ERROR', '超出库存或限购数量');
          address = typeof body.addressId === 'string' ? addresses.get(body.addressId) : undefined;
          if (!address) fail('VALIDATION_ERROR', '请选择有效地址');
          const rate = shipping[address.provinceCode];
          if (rate === undefined) fail('REGION_NOT_SUPPORTED', 'DEV运费场景不支持此地区');
          freight = rate;
        } else if (quantity !== 1 || body.addressId != null)
          fail('VALIDATION_ERROR', '数字商品数量应为1且不使用收货地址');
        const goods = product.priceFen * quantity;
        if (!Number.isSafeInteger(goods) || goods < 0 || !Number.isSafeInteger(goods + freight))
          fail('VALIDATION_ERROR', '商品金额无效');
        const view: QuoteView = {
          quoteId: id('quote'),
          expiresAt: new Date(now() + 300000).toISOString(),
          item: {
            productId: product.id,
            type: product.type,
            title: product.title,
            coverUrl: product.coverUrl,
            quantity,
            unitPriceFen: product.priceFen,
          },
          availableStock: product.book?.availableStock ?? null,
          goodsAmountFen: goods,
          freightAmountFen: freight,
          payableAmountFen: goods + freight,
          currency: 'CNY',
          warnings: [],
        };
        quotes.set(view.quoteId, {
          view,
          product: JSON.stringify(product),
          addressId: address?.id,
          address: address ? JSON.stringify(address) : undefined,
          freight,
        });
        return reply(view);
      }
      if (path === '/orders' && request.method === 'POST') {
        const key = Object.entries(request.headers)
          .find(([name]) => name.toLowerCase() === 'idempotency-key')?.[1]
          ?.toLowerCase();
        if (!key || !uuid.test(key)) fail('VALIDATION_ERROR', '需要UUID幂等键');
        if (
          typeof body.quoteId !== 'string' ||
          (body.attributionToken != null && typeof body.attributionToken !== 'string') ||
          Object.keys(body).some((field) => !['quoteId', 'attributionToken'].includes(field))
        )
          fail('VALIDATION_ERROR', '订单请求无效');
        if (effects.has(key)) fail('IDEMPOTENCY_CONFLICT', '幂等键已用于其他操作', 409);
        const snapshot = JSON.stringify({
          quoteId: body.quoteId,
          attributionToken: body.attributionToken,
        });
        const previous = operations.get(key);
        if (previous) {
          if (previous.body !== snapshot) fail('IDEMPOTENCY_CONFLICT', '幂等键已用于不同请求', 409);
          return reply(orders.get(previous.orderId));
        }
        const quote = quotes.get(body.quoteId);
        if (!quote) fail('NOT_FOUND', '报价不存在', 404);
        if (quote.orderId) fail('ORDER_STALE', '报价已用于创建订单', 409);
        if (now() >= Date.parse(quote.view.expiresAt)) fail('QUOTE_EXPIRED', '报价已过期', 409);
        const product = productView(
          catalog.products.find((p) => p.id === quote.view.item.productId),
        );
        const address = quote.addressId ? addresses.get(quote.addressId) : undefined;
        if (
          !product ||
          JSON.stringify(product) !== quote.product ||
          (quote.addressId &&
            (!address ||
              JSON.stringify(address) !== quote.address ||
              shipping[address.provinceCode] !== quote.freight))
        )
          fail('ORDER_STALE', '商品、地址或运费已变化，请重新报价', 409);
        const view = quote.view;
        const order: DevOrder = {
          version: 1,
          address: address ? JSON.parse(JSON.stringify(address)) : null,
          createdAt: new Date(now()).toISOString(),
          refundedGoodsFen: 0,
          refundedFreightFen: 0,
          id: id('order'),
          orderNo: id('order-no'),
          orderStatus: 'PENDING_PAYMENT',
          fulfillmentStatus: product.type === 'BOOK' ? 'PENDING_SHIPMENT' : 'NOT_REQUIRED',
          refundStatus: 'NONE',
          goodsAmountFen: view.goodsAmountFen,
          freightAmountFen: view.freightAmountFen,
          payableAmountFen: view.payableAmountFen,
          paidAmountFen: 0,
          refundedAmountFen: 0,
          expiresAt: new Date(now() + 1800000).toISOString(),
          paidAt: null,
          completedAt: null,
          item: { ...view.item, coverUrl: view.item.coverUrl ?? null },
          allowedActions: ['PAY', 'CANCEL'],
        };
        if (product.type === 'BOOK' && product.book)
          product.book.availableStock -= view.item.quantity;
        orders.set(order.id, order);
        purchaseSnapshots.set(order.id, { product: JSON.parse(JSON.stringify(product)), actor });
        quote.orderId = order.id;
        operations.set(key, { body: snapshot, orderId: order.id });
        return reply(order);
      }
      const operationMatch = path?.match(/^\/operations\/([^/]+)$/);
      if (operationMatch && request.method === 'GET') {
        const result = effects.get(decodeURIComponent(operationMatch[1]!).toLowerCase());
        const reqScope = params.get('scope');
        const matchScope = (actual?: string, requested?: string | null) => {
          if (!actual || !requested) return false;
          if (actual === requested) return true;
          const aliases: Record<string, string> = {
            WECHAT_PREPAY: 'PREPAY',
            PREPAY: 'PREPAY',
            REQUEST_REFUND: 'APPLY_REFUND',
            APPLY_REFUND: 'APPLY_REFUND',
            INVENTORY_ADJUSTMENT: 'INVENTORY_ADJUST',
            INVENTORY_ADJUST: 'INVENTORY_ADJUST',
            APPROVE_AFTERSALE: 'APPROVE_REFUND',
            APPROVE_REFUND: 'APPROVE_REFUND',
            REJECT_AFTERSALE: 'REJECT_REFUND',
            REJECT_REFUND: 'REJECT_REFUND',
          };
          return (aliases[actual] || actual) === (aliases[requested] || requested);
        };
        if (result && matchScope(result.scope, reqScope))
          return reply({
            state: 'SUCCEEDED',
            resourceId: result.resourceId,
            response: result.response,
          });
        const operation =
          params.get('scope') === 'CREATE_ORDER'
            ? operations.get(decodeURIComponent(operationMatch[1]!).toLowerCase())
            : undefined;
        if (!operation) fail('NOT_FOUND', '操作不存在', 404);
        return reply({ state: 'SUCCEEDED', resourceId: operation.orderId });
      }
      if (path === '/orders' && request.method === 'GET') {
        const tab = params.get('tab') ?? 'ALL';
        if (!['ALL', 'UNPAID', 'UNSHIPPED', 'SHIPPING', 'COMPLETED', 'AFTERSALE'].includes(tab))
          fail('VALIDATION_ERROR', '订单分类无效');
        for (const order of orders.values()) actions(order);
        return page(
          [...orders.values()]
            .reverse()
            .filter(
              (order) =>
                tab === 'ALL' ||
                (tab === 'UNPAID' && order.orderStatus === 'PENDING_PAYMENT') ||
                (tab === 'UNSHIPPED' &&
                  order.orderStatus === 'PAID' &&
                  order.fulfillmentStatus === 'PENDING_SHIPMENT') ||
                (tab === 'SHIPPING' && order.fulfillmentStatus === 'SHIPPED') ||
                (tab === 'COMPLETED' && order.orderStatus === 'COMPLETED') ||
                (tab === 'AFTERSALE' && order.refundStatus !== 'NONE'),
            ),
        );
      }
      if (path === '/payments/wechat/prepay' && request.method === 'POST') {
        const orderId = typeof body.orderId === 'string' ? body.orderId : '';
        return effect('PREPAY', orderId, () => {
          const order = findOrder(orderId);
          requireAction(order, 'PAY');
          let payment = payments.get(orderId);
          if (!payment || ['CLOSED', 'FAILED'].includes(payment.state)) {
            payment = {
              provider: 'DEV',
              paymentId: id('payment'),
              state: 'PENDING',
              simulated: true,
              params: { simulated: true },
            };
            payments.set(orderId, payment);
          }
          return { id: payment.paymentId, response: payment };
        });
      }
      const devPay = path?.match(/^\/dev\/orders\/([^/]+)\/pay$/);
      if (devPay && request.method === 'POST') {
        const orderId = decodeURIComponent(devPay[1]!);
        return effect('DEV_PAY', orderId, () => {
          const order = findOrder(orderId);
          if (!['SUCCESS', 'TIMEOUT', 'FAILURE', 'CLOSED'].includes(String(body.scenario)))
            fail('VALIDATION_ERROR', '未知开发支付场景');
          if (order.orderStatus === 'CANCELLED') fail('ACTION_NOT_ALLOWED', '订单已取消', 409);
          let payment = payments.get(orderId);
          if (!payment) {
            payment = {
              provider: 'DEV',
              paymentId: id('payment'),
              state: 'PENDING',
              simulated: true,
              params: { simulated: true },
            };
            payments.set(orderId, payment);
          }
          // Late simulator failures cannot overwrite an already confirmed payment.
          if (!['PAID', 'COMPLETED'].includes(order.orderStatus)) {
            payment.state =
              body.scenario === 'SUCCESS'
                ? 'SUCCEEDED'
                : body.scenario === 'TIMEOUT'
                  ? 'UNKNOWN'
                  : body.scenario === 'FAILURE'
                    ? 'FAILED'
                    : 'CLOSED';
            if (body.scenario === 'SUCCESS') {
              order.orderStatus = 'PAID';
              order.paidAmountFen = order.payableAmountFen;
              order.paidAt = new Date(now()).toISOString();
              const purchase = purchaseSnapshots.get(order.id);
              if (purchase && options.content?.paid(order, purchase.product, purchase.actor)) {
                order.orderStatus = 'COMPLETED';
                order.completedAt = order.paidAt;
              }
              order.version++;
            }
          }
          actions(order);
          return { id: order.id, response: { id: order.id, simulated: true } };
        });
      }
      const actionMatch = path?.match(/^\/orders\/([^/]+)\/(cancel|confirm-receipt|refund)$/);
      if (actionMatch && request.method === 'POST') {
        const orderId = decodeURIComponent(actionMatch[1]!),
          action = actionMatch[2];
        const scope =
          action === 'cancel'
            ? 'CANCEL_ORDER'
            : action === 'confirm-receipt'
              ? 'CONFIRM_RECEIPT'
              : 'APPLY_REFUND';
        return effect(scope, orderId, () => {
          const order = findOrder(orderId);
          if (action === 'cancel') {
            if (payments.get(orderId)?.state === 'UNKNOWN')
              fail('PAYMENT_UNKNOWN', '支付结果未知，请先查询原支付', 409);
            requireAction(order, 'CANCEL');
            const product = catalog.products.find((p) => p.id === order.item.productId);
            if (product?.type === 'BOOK' && product.book)
              product.book.availableStock += order.item.quantity;
            order.orderStatus = 'CANCELLED';
            const payment = payments.get(orderId);
            if (payment) payment.state = 'CLOSED';
          } else if (action === 'confirm-receipt') {
            requireAction(order, 'CONFIRM_RECEIPT');
            order.fulfillmentStatus = 'RECEIVED';
            order.orderStatus = 'COMPLETED';
            order.completedAt = new Date(now()).toISOString();
            const shipment = shipments.get(order.id);
            if (shipment) {
              shipment.status = 'RECEIVED';
              shipment.receivedAt = order.completedAt;
            }
          } else {
            requireAction(order, 'APPLY_AFTERSALE');
            const reason = typeof body.reason === 'string' ? body.reason.trim() : '';
            if (
              !reason ||
              reason.length > 500 ||
              !['REFUND_ONLY', 'RETURN_REFUND'].includes(String(body.type))
            )
              fail('VALIDATION_ERROR', '请填写售后类型和原因');
            if (
              !Array.isArray(body.evidenceAssetIds) ||
              body.evidenceAssetIds.length > 10 ||
              body.evidenceAssetIds.some((asset) => typeof asset !== 'string' || !asset)
            )
              fail('VALIDATION_ERROR', '凭证格式无效');
            if (
              body.evidenceAssetIds.some(
                (assetId) => evidence.get(String(assetId))?.asset.status !== 'READY',
              )
            )
              fail('FILE_NOT_READY', '请先完成凭证上传', 409);
            const goods = body.requestedGoodsFen,
              freight = body.requestedFreightFen;
            if (
              typeof goods !== 'number' ||
              typeof freight !== 'number' ||
              !Number.isSafeInteger(goods) ||
              !Number.isSafeInteger(freight) ||
              goods < 0 ||
              freight < 0 ||
              goods + freight <= 0 ||
              goods > order.goodsAmountFen - order.refundedGoodsFen ||
              freight > order.freightAmountFen - order.refundedFreightFen
            )
              fail('VALIDATION_ERROR', '申请退款金额无效');
            if (
              body.type === 'RETURN_REFUND' &&
              (order.item.type !== 'BOOK' ||
                !['SHIPPED', 'RECEIVED'].includes(order.fulfillmentStatus))
            )
              fail('ACTION_NOT_ALLOWED', '当前订单无需寄回', 409);
            const caseView: AftersaleView = {
              id: id('aftersale'),
              orderId,
              type: body.type as AftersaleView['type'],
              reason,
              evidenceAssetIds: [...new Set(body.evidenceAssetIds as string[])],
              requestedGoodsFen: goods,
              requestedFreightFen: freight,
              status: 'REQUESTED',
              approvedGoodsFen: null,
              approvedFreightFen: null,
              returnedQuantity: null,
              receivedQuantity: null,
              returnAddress: null,
              returnCarrierCode: null,
              returnTrackingNo: null,
              allowedActions: [],
            };
            aftersales.set(caseView.id, caseView);
            order.refundStatus = 'REQUESTED';
            order.aftersaleId = caseView.id;
            order.version++;
            actions(order);
            return { id: caseView.id, response: caseView };
          }
          order.version++;
          actions(order);
          return { id: order.id, response: order };
        });
      }
      const caseMatch = path?.match(/^\/aftersales\/([^/]+)(?:\/(return-shipment))?$/);
      if (caseMatch) {
        const caseView = aftersales.get(decodeURIComponent(caseMatch[1]!));
        if (!caseView) fail('NOT_FOUND', '售后单不存在', 404);
        if (!caseMatch[2] && request.method === 'GET') return reply(caseView);
        if (caseMatch[2] && request.method === 'POST')
          return effect('RETURN_SHIPMENT', caseView.id, () => {
            if (!caseView.allowedActions.includes('SUBMIT_RETURN_SHIPMENT'))
              fail('ACTION_NOT_ALLOWED', '尚未获准寄回', 409);
            const carrier = typeof body.carrierCode === 'string' ? body.carrierCode.trim() : '',
              tracking = typeof body.trackingNo === 'string' ? body.trackingNo.trim() : '';
            if (!carrier || carrier.length > 40 || !/^[A-Za-z0-9-]{1,100}$/.test(tracking))
              fail('VALIDATION_ERROR', '退货物流信息无效');
            caseView.returnCarrierCode = carrier;
            caseView.returnTrackingNo = tracking;
            caseView.status = 'RETURNING';
            caseView.allowedActions = [];
            const order = findOrder(caseView.orderId);
            order.refundStatus = 'RETURNING';
            order.version++;
            actions(order);
            return { id: caseView.id, response: caseView };
          });
      }
      const shipmentMatch = path?.match(/^\/shipments\/([^/]+)$/);
      if (shipmentMatch && request.method === 'GET') {
        const order = findOrder(decodeURIComponent(shipmentMatch[1]!));
        if (shipments.has(order.id)) return reply(shipments.get(order.id));
        return reply({
          shippedQuantity: 0,
          carrierCode: null,
          trackingNo: null,
          status: order.fulfillmentStatus,
          simulated: true,
          shippedAt: null,
          receivedAt: null,
          events: [],
        });
      }
      const orderMatch = path?.match(/^\/orders\/([^/]+)$/);
      if (orderMatch && request.method === 'GET') {
        const order = orders.get(decodeURIComponent(orderMatch[1]!));
        if (!order) fail('NOT_FOUND', '订单不存在', 404);
        actions(order);
        return reply(order);
      }
      return reply(null, 404, 'NOT_FOUND', '此DEV场景尚未接入');
    } catch (error) {
      if (error instanceof ApiError)
        return reply(
          { fieldErrors: error.fieldErrors },
          error.status || 400,
          error.code,
          error.message,
        );
      throw error;
    }
  };
}
