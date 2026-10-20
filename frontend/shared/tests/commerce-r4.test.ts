import { describe, expect, it } from 'vitest';
import { hasPermission } from '../src/contracts';
import { createSession } from '../src/session';
import { createClient, type TransportRequest } from '../src/http';
import {
  ADMIN_OPERATION_SCOPES,
  createAdminCommerceApi,
  type AdminOrder,
  type AdminAftersale,
  type AdminInventoryProduct,
} from '../src/commerce/admin-api';
import {
  createAdminShipmentController,
  createAdminInventoryController,
  createAdminAftersaleController,
} from '../src/commerce/admin';

const key = '12345678-1234-1234-1234-123456789abc';
const order = { id: '9', version: 1, allowedActions: ['SHIP'], shipment: null } as AdminOrder;
const product = {
  id: '8',
  type: 'BOOK',
  book: { availableStock: 3, lockedStock: 1, physicalStock: 4 },
} as AdminInventoryProduct;
const aftersale = {
  id: '7',
  allowedActions: ['APPROVE', 'REJECT', 'RECEIVE_RETURN'],
  permittedReturnQuantity: 1,
} as AdminAftersale;
function setup(permissions: string[], handler?: (r: TransportRequest) => unknown) {
  const data = new Map<string, string>();
  const storage = {
    get: (k: string) => data.get(k) ?? null,
    set: (k: string, v: string) => {
      data.set(k, v);
    },
    remove: (k: string) => {
      data.delete(k);
    },
  };
  const session = createSession(storage);
  session.set({
    accessToken: 'token',
    expiresAt: '2099-01-01T00:00:00Z',
    admin: { id: '1', loginName: 'operator', permissions },
    permissions,
  });
  const requests: TransportRequest[] = [];
  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: async (r) => {
      requests.push(r);
      const value = handler
        ? handler(r)
        : r.url.includes('/operations/')
          ? { state: 'SUCCEEDED', resourceId: '7' }
          : r.url.includes('preview-decision')
            ? {
                remainingGoodsFen: 100,
                remainingFreightFen: 0,
                entitlementImpact: 'preserve',
                commissionImpact: 'reverse',
              }
            : r.url.includes('/aftersales?')
              ? { items: [aftersale] }
              : r.url.includes('/aftersales/')
                ? aftersale
                : r.url.includes('/products/')
                  ? product
                  : order;
      return { status: 200, body: { code: 'OK', message: '', requestId: 'r', data: value } };
    },
  });
  return {
    session,
    storage,
    requests,
    api: createAdminCommerceApi(client),
    namespace: 'r4',
    keyGenerator: () => key,
    orderId: '9',
    getOrder: () => order,
    productId: '8',
    getProduct: () => product,
    aftersaleId: '7',
    getAftersale: () => aftersale,
  };
}
describe('R4 exact permissions', () => {
  it.each([
    'CONTENT_EDIT',
    'ORDER_OPERATE',
    'FINANCE_OPERATE',
    'DISTRIBUTION_OPERATE',
    'ANALYTICS_READ',
    'AUDIT_READ',
    'SETTINGS_MANAGE',
    'EXPORT_READ',
  ])('does not promote %s to super admin', (permission) => {
    expect(hasPermission([permission], 'SUPER_ADMIN')).toBe(false);
    expect(hasPermission([permission], permission)).toBe(true);
  });
  it('keeps legacy roles narrow and grants exports only explicitly', () => {
    expect(hasPermission(['ANALYST'], 'ANALYTICS_READ')).toBe(true);
    expect(hasPermission(['ANALYST'], 'AUDIT_READ')).toBe(false);
    expect(hasPermission(['FINANCE_OPERATOR'], 'EXPORT_READ')).toBe(false);
    expect(hasPermission(['ORDER_OPERATOR'], 'ORDER_OPERATE')).toBe(true);
    expect(hasPermission(['ORDER_OPERATE'], 'FINANCE_OPERATE')).toBe(false);
    expect(hasPermission(['SUPER_ADMIN'], 'EXPORT_READ')).toBe(true);
    expect(hasPermission(['SUPER_ADMIN'], 'UNKNOWN_PERMISSION')).toBe(false);
  });
  it.each(['SHIP', 'INVENTORY', 'APPROVE', 'REJECT', 'RECEIVE_RETURN'] as const)(
    'dispatches %s under actual permission codes',
    async (action) => {
      const permission = ['APPROVE', 'REJECT'].includes(action)
        ? 'FINANCE_OPERATE'
        : 'ORDER_OPERATE';
      const x = setup([permission]);
      if (action === 'SHIP')
        await createAdminShipmentController(x).submit({
          carrierCode: 'SF',
          trackingNo: 'SF123',
          version: 1,
        });
      else if (action === 'INVENTORY')
        await createAdminInventoryController(x).submit({ delta: 1, reason: 'count' });
      else {
        const c = createAdminAftersaleController(x);
        if (action === 'APPROVE') {
          const input = { goodsRefundFen: 100, freightRefundFen: 0, reason: 'approve' };
          await c.preview(input);
          await c.approve(input);
        } else if (action === 'REJECT') await c.reject({ reason: 'reject' });
        else await c.receiveReturn({ returnedQuantity: 1 });
      }
      expect(x.requests.some((r) => r.method === 'POST')).toBe(true);
    },
  );
  it('does not authorize writes using an unrelated real permission', async () => {
    const x = setup(['CONTENT_EDIT']);
    await expect(
      createAdminInventoryController(x).submit({ delta: 1, reason: 'count' }),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(x.requests).toHaveLength(0);
  });
  it.each(['SHIP', 'INVENTORY', 'APPROVE', 'REJECT', 'RECEIVE_RETURN'] as const)(
    'recovers UNKNOWN %s read-only with actual permissions and the original wire scope',
    async (action) => {
      const permissions =
        action === 'INVENTORY'
          ? ['ORDER_OPERATE', 'CONTENT_EDIT']
          : action === 'RECEIVE_RETURN'
            ? ['ORDER_OPERATE', 'FINANCE_OPERATE']
            : ['APPROVE', 'REJECT'].includes(action)
              ? ['FINANCE_OPERATE']
              : ['ORDER_OPERATE'];
      const resourceId =
        action === 'SHIP' ? order.id : action === 'INVENTORY' ? product.id : aftersale.id;
      const x = setup(permissions, (r) => {
        if (r.method === 'POST' && !r.url.includes('preview-decision'))
          throw Error('lost response');
        if (r.url.includes('/operations/'))
          return { state: 'SUCCEEDED', resourceId, response: { id: resourceId } };
        if (r.url.includes('preview-decision'))
          return {
            remainingGoodsFen: 100,
            remainingFreightFen: 0,
            entitlementImpact: 'preserve',
            commissionImpact: 'reverse',
          };
        if (r.url.includes('/aftersales?')) return { items: [aftersale] };
        return action === 'INVENTORY' ? product : order;
      });
      let submit!: () => Promise<void>;
      let recover!: () => Promise<void>;
      if (action === 'SHIP') {
        const c = createAdminShipmentController(x);
        submit = () => c.submit({ carrierCode: 'SF', trackingNo: 'SF123', version: 1 });
        recover = c.recover;
      } else if (action === 'INVENTORY') {
        const c = createAdminInventoryController(x);
        submit = () => c.submit({ delta: 1, reason: 'count' });
        recover = c.recover;
      } else {
        const c = createAdminAftersaleController(x);
        if (action === 'APPROVE') {
          const body = { goodsRefundFen: 100, freightRefundFen: 0, reason: 'approve' };
          await c.preview(body);
          submit = () => c.approve(body);
          recover = c.recoverApprove;
        } else if (action === 'REJECT') {
          submit = () => c.reject({ reason: 'reject' });
          recover = c.recoverReject;
        } else {
          submit = () => c.receiveReturn({ returnedQuantity: 1 });
          recover = c.recoverReceiveReturn;
        }
      }
      await expect(submit()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
      const writes = x.requests.filter((r) => r.method === 'POST').length;
      await recover();
      expect(x.requests.filter((r) => r.method === 'POST')).toHaveLength(writes);
      expect(
        x.requests.some(
          (r) => r.url === `/api/v1/operations/${key}?scope=${ADMIN_OPERATION_SCOPES[action]}`,
        ),
      ).toBe(true);
    },
  );
  it('sends only authoritative order date names, including legacy caller conversion', async () => {
    const x = setup(['ORDER_OPERATE']);
    await x.api.orders({
      createdFrom: '2026-10-01T00:00:00Z',
      createdTo: '2026-10-02T00:00:00Z',
      page: 2,
    });
    const query = new URL(x.requests[0]!.url, 'https://api.test').searchParams;
    expect(query.get('from')).toBe('2026-10-01T00:00:00Z');
    expect(query.get('to')).toBe('2026-10-02T00:00:00Z');
    expect(query.has('createdFrom')).toBe(false);
    expect(query.has('createdTo')).toBe(false);
    await x.api.orders({
      from: 'authoritative-from',
      to: 'authoritative-to',
      createdFrom: 'legacy-from',
      createdTo: 'legacy-to',
    });
    expect(new URL(x.requests[1]!.url, 'https://api.test').searchParams.get('from')).toBe(
      'authoritative-from',
    );
  });
});
