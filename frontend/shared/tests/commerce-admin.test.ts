import { describe, expect, it } from 'vitest';
import { ApiError, createClient, type TransportRequest } from '../src/http';
import { createSession } from '../src/session';
import {
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

const shipment = { carrierCode: 'SF', trackingNo: 'SF-123', version: 1 };
const decision = { goodsRefundFen: 100, freightRefundFen: 0, reason: 'approved' };
const preview = {
  remainingGoodsFen: 100,
  remainingFreightFen: 20,
  entitlementImpact: 'revoke',
  commissionImpact: 'reverse',
};
const order = {
  id: 'o1',
  version: 1,
  allowedActions: ['SHIP', 'UPDATE_SHIPMENT'],
  shipment: null,
} as AdminOrder;
const aftersale = {
  id: 'a1',
  orderId: 'o1',
  allowedActions: ['APPROVE', 'REJECT', 'RECEIVE_RETURN'],
  permittedReturnQuantity: 2,
  status: 'REQUESTED',
} as AdminAftersale;
const product = {
  id: 'p1',
  type: 'BOOK',
  book: { availableStock: 10, lockedStock: 0, physicalStock: 10 },
} as AdminInventoryProduct;
function setup(
  handler: (r: TransportRequest) => unknown | Promise<unknown> = (r) =>
    r.url.includes('preview-decision')
      ? preview
      : r.url.includes('/aftersales?')
        ? { items: [aftersale], total: 1, page: 1, pageSize: 20 }
        : r.url.includes('/products/')
          ? product
          : r.url.includes('/aftersales/')
            ? aftersale
            : order,
) {
  const values = new Map<string, string>();
  const storage = {
    get: (k: string) => values.get(k) ?? null,
    set: (k: string, v: string) => {
      values.set(k, v);
    },
    remove: (k: string) => {
      values.delete(k);
    },
  };
  const session = createSession(storage);
  const login = (permissions = ['ORDER_OPERATOR', 'FINANCE_OPERATOR'], id = 'admin') =>
    session.set({
      accessToken: id,
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, nickname: id, distributor: false },
      permissions,
    });
  login();
  const requests: TransportRequest[] = [];
  const api = createAdminCommerceApi(
    createClient({
      baseUrl: '/api/v1',
      session,
      transport: async (r) => {
        requests.push(r);
        return {
          status: 200,
          body: { code: 'OK', message: '', requestId: 'r', data: await handler(r) },
        };
      },
    }),
  );
  let serial = 0;
  return {
    api,
    session,
    storage,
    requests,
    login,
    namespace: 'admin-test',
    keyGenerator: () => `00000000-0000-4000-8000-${String(++serial).padStart(12, '0')}`,
    orderId: 'o1',
    getOrder: () => order,
    productId: 'p1',
    getProduct: () => product,
    aftersaleId: 'a1',
    getAftersale: () => aftersale,
  };
}
describe('admin commerce boundaries', () => {
  it('accepts the real productId inventory success response and rereads without an unnecessary recovery query', async () => {
    const x = setup((r) =>
      r.method === 'POST'
        ? { productId: 'p1', onHand: 11, reserved: 0, availableStock: 11, version: 4 }
        : {
            ...product,
            book: { ...product.book, availableStock: 11, physicalStock: 11, inventoryVersion: 4 },
          },
    );
    const controller = createAdminInventoryController(x);
    await controller.submit({ delta: 1, reason: '盘点', version: 3 });
    expect(controller.operation.state.status).toBe('SUCCEEDED');
    expect(controller.state.value?.book?.availableStock).toBe(11);
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
    expect(x.requests.some((r) => r.url.includes('/operations/'))).toBe(false);
  });
  it.each([
    { body: { delta: 0, reason: 'edit' }, code: 'VALIDATION_ERROR' },
    { body: { delta: 2, reason: 'changed' }, code: 'IDEMPOTENCY_CONFLICT' },
  ])('preserves a pending successful reread after rejected $code input', async ({ body, code }) => {
    let finish!: (value: unknown) => void;
    const x = setup((r) =>
      r.method === 'POST'
        ? { id: 'p1' }
        : new Promise((resolve) => {
            finish = resolve;
          }),
    );
    const published: number[] = [];
    const c = createAdminInventoryController({
      ...x,
      onProduct: (value) => published.push(value.book!.availableStock!),
    });
    const first = c.submit({ delta: 1, reason: 'one' });
    for (let i = 0; i < 30 && !finish; i++) await Promise.resolve();
    await expect(c.submit(body)).rejects.toMatchObject({ code });
    finish({ ...product, book: { ...product.book, availableStock: 11 } });
    await first;
    expect(c.state.value?.book?.availableStock).toBe(11);
    expect(published).toEqual([11]);
    expect(c.state.error).toMatchObject({ code });
    expect(x.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
  });
  it('preserves a successful case reread when a different action is locally invalid', async () => {
    let finish!: (value: unknown) => void;
    const x = setup((r) =>
      r.method === 'POST'
        ? aftersale
        : new Promise((resolve) => {
            finish = resolve;
          }),
    );
    const published: string[] = [];
    const c = createAdminAftersaleController({
      ...x,
      onAftersale: (value) => published.push(value.status),
    });
    const first = c.receiveReturn({ returnedQuantity: 1 });
    for (let i = 0; i < 30 && !finish; i++) await Promise.resolve();
    await expect(c.reject({ reason: ' ' })).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    finish({
      items: [{ ...aftersale, status: 'RETURN_RECEIVED' }],
      page: 1,
      pageSize: 20,
      total: 1,
    });
    await first;
    expect(c.state.aftersale?.status).toBe('RETURN_RECEIVED');
    expect(published).toEqual(['RETURN_RECEIVED']);
    expect(c.state.error).toMatchObject({ code: 'VALIDATION_ERROR' });
  });
  it('keeps the newest inventory result when deliberate-action reads finish in reverse order', async () => {
    const reads: ((value: unknown) => void)[] = [];
    const x = setup((r) =>
      r.method === 'POST' ? { id: 'p1' } : new Promise((resolve) => reads.push(resolve)),
    );
    const published: number[] = [];
    const c = createAdminInventoryController({
      ...x,
      onProduct: (value) => published.push(value.book!.availableStock!),
    });
    const first = c.submit({ delta: 1, reason: 'one' });
    for (let i = 0; i < 30 && reads.length < 1; i++) await Promise.resolve();
    c.beginNew();
    const second = c.submit({ delta: 1, reason: 'two' });
    for (let i = 0; i < 30 && reads.length < 2; i++) await Promise.resolve();
    reads[1]!({ ...product, book: { ...product.book, availableStock: 12 } });
    await second;
    reads[0]!({ ...product, book: { ...product.book, availableStock: 11 } });
    await first;
    expect(c.state.value?.book?.availableStock).toBe(12);
    expect(published).toEqual([12]);
  });
  it('coordinates case publication across different overlapping actions', async () => {
    const reads: ((value: unknown) => void)[] = [];
    const x = setup((r) =>
      r.method === 'POST' ? aftersale : new Promise((resolve) => reads.push(resolve)),
    );
    const published: string[] = [];
    const c = createAdminAftersaleController({
      ...x,
      onAftersale: (value) => published.push(value.status),
    });
    const first = c.receiveReturn({ returnedQuantity: 1 });
    for (let i = 0; i < 30 && reads.length < 1; i++) await Promise.resolve();
    const second = c.reject({ reason: 'rejected' });
    for (let i = 0; i < 30 && reads.length < 2; i++) await Promise.resolve();
    reads[1]!({ items: [{ ...aftersale, status: 'REJECTED' }], total: 1, page: 1, pageSize: 20 });
    await second;
    reads[0]!({
      items: [{ ...aftersale, status: 'RETURN_RECEIVED' }],
      total: 1,
      page: 1,
      pageSize: 20,
    });
    await first;
    expect(c.state.aftersale?.status).toBe('REJECTED');
    expect(published).toEqual(['REJECTED']);
  });
  it('does not dispatch a scheduled recovery under replacement same-actor credentials', async () => {
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      return { state: 'SUCCEEDED', resourceId: 'o1' };
    });
    const c = createAdminShipmentController(x);
    await expect(c.submit(shipment)).rejects.toThrow();
    const recovery = c.recover();
    x.login();
    await recovery.catch(() => {});
    expect(x.requests).toHaveLength(1);
    expect(c.operation.state.status).toBe('UNKNOWN');
    expect(createAdminShipmentController(x).operation.state.requestSnapshot).toEqual(shipment);
  });
  it('keeps the journal unresolved when a recovery completes after same-actor session replacement', async () => {
    let finish!: (value: unknown) => void;
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    const c = createAdminShipmentController(x);
    await expect(c.submit(shipment)).rejects.toThrow();
    const key = c.operation.state.key;
    const recovery = c.recover();
    for (let i = 0; i < 30 && !finish; i++) await Promise.resolve();
    x.login();
    finish({ state: 'SUCCEEDED', resourceId: 'o1' });
    await recovery.catch(() => {});
    const restored = createAdminShipmentController(x);
    expect(restored.operation.state.status).toBe('UNKNOWN');
    expect(restored.operation.state.key).toBe(key);
    expect(restored.operation.state.requestSnapshot).toEqual(shipment);
    expect(c.state.value).toBeNull();
  });
  it('keeps distinct local actions when their configured backend wire scopes match', async () => {
    const x = setup((r) => {
      if (r.url.endsWith('/preview-decision')) return preview;
      if (r.method === 'POST') throw new Error('timeout');
      return { state: 'PROCESSING' };
    });
    const c = createAdminAftersaleController({
      ...x,
      scopes: { APPROVE: 'AFTERSALE_DECISION', REJECT: 'AFTERSALE_DECISION' },
    });
    await c.preview(decision);
    await expect(c.approve(decision)).rejects.toThrow();
    await expect(c.reject({ reason: 'reject' })).rejects.toThrow();
    expect(x.requests.filter((r) => /\/(approve|reject)$/.test(r.url)).map((r) => r.url)).toEqual([
      '/api/v1/admin/aftersales/a1/approve',
      '/api/v1/admin/aftersales/a1/reject',
    ]);
    expect(c.approveOperation.state.key).not.toBe(c.rejectOperation.state.key);
    await c.recoverApprove();
    await c.recoverReject();
    expect(
      x.requests
        .filter((r) => r.url.includes('/operations/'))
        .map((r) => new URL(r.url, 'https://example.test').search),
    ).toEqual(['?scope=AFTERSALE_DECISION', '?scope=AFTERSALE_DECISION']);
  });
  it('normalizes shipment text before writing while preserving editable input', async () => {
    const x = setup();
    const c = createAdminShipmentController(x);
    await c.submit({ ...shipment, carrierCode: ' SF ', trackingNo: ' SF-123 ' });
    expect(x.requests[0]?.body).toEqual(shipment);
    expect(c.state.input?.trackingNo).toBe(' SF-123 ');
  });
  it('checks latest entity actions at dispatch and current permission during recovery', async () => {
    const x = setup();
    let current = order;
    const c = createAdminShipmentController({ ...x, getOrder: () => current });
    const pending = c.submit(shipment);
    current = { ...order, allowedActions: [] };
    await expect(pending).rejects.toMatchObject({ code: 'ACTION_NOT_ALLOWED' });
    expect(x.requests).toHaveLength(0);
    x.login(['FINANCE_OPERATOR']);
    await expect(c.recover()).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(x.requests).toHaveLength(0);
  });
  it('uses existing product reads and exact aftersale/inventory mutation routes', async () => {
    const x = setup();
    await x.api.product('p1');
    await x.api.inventory('p1', { delta: -2, reason: 'count' }, 'k1');
    await x.api.aftersales({ id: 'a1', page: 1, pageSize: 20 });
    await x.api.approve('a1', decision, 'k2');
    await x.api.reject('a1', { reason: 'no' }, 'k3');
    await x.api.receiveReturn('a1', { returnedQuantity: 1 }, 'k4');
    expect(x.requests.map((r) => [r.method, r.url, r.body, r.headers['Idempotency-Key']])).toEqual([
      ['GET', '/api/v1/admin/products/p1', undefined, undefined],
      [
        'POST',
        '/api/v1/admin/products/p1/inventory-adjustments',
        { delta: -2, reason: 'count' },
        'k1',
      ],
      ['GET', '/api/v1/admin/aftersales?id=a1&page=1&pageSize=20', undefined, undefined],
      ['POST', '/api/v1/admin/aftersales/a1/approve', decision, 'k2'],
      ['POST', '/api/v1/admin/aftersales/a1/reject', { reason: 'no' }, 'k3'],
      ['POST', '/api/v1/admin/aftersales/a1/receive-return', { returnedQuantity: 1 }, 'k4'],
    ]);
  });
  it('cannot rebind an already scheduled write to a replacement session', async () => {
    const x = setup();
    const c = createAdminShipmentController(x);
    const first = c.submit(shipment);
    x.login();
    const second = c.submit(shipment);
    await Promise.allSettled([first, second]);
    expect(x.requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });
  it('ignores a superseded preview failure without overwriting current errors', async () => {
    let rejectOld!: (error: Error) => void;
    let count = 0;
    const x = setup(() =>
      ++count === 1
        ? new Promise((_resolve, reject) => {
            rejectOld = reject;
          })
        : preview,
    );
    const c = createAdminAftersaleController(x);
    const old = c.preview(decision);
    const settled = old.catch(() => {});
    await c.preview({ ...decision, goodsRefundFen: 90 });
    rejectOld(new Error('old failure'));
    await settled;
    expect(c.state.error).toBeNull();
    expect(c.state.preview).toEqual(preview);
  });
  it('sends explicit filters and exact mutation bodies with encoded IDs and wire scope', async () => {
    const x = setup();
    await x.api.orders({
      orderNo: 'N1',
      createdFrom: '2026-01-01',
      createdTo: '2026-02-01',
      orderStatus: 'PAID',
      fulfillmentStatus: 'SHIPPED',
      refundStatus: 'NONE',
      page: 2,
      pageSize: 10,
    });
    await x.api.ship('o/1', shipment, 'key');
    await x.api.previewDecision('a1', decision);
    await x.api.operation('key', 'SHIP_ORDER');
    expect(x.requests.map((r) => [r.method, r.url, r.body, r.headers['Idempotency-Key']])).toEqual([
      [
        'GET',
        '/api/v1/admin/orders?orderNo=N1&orderStatus=PAID&fulfillmentStatus=SHIPPED&refundStatus=NONE&page=2&pageSize=10&from=2026-01-01&to=2026-02-01',
        undefined,
        undefined,
      ],
      ['POST', '/api/v1/admin/orders/o%2F1/ship', shipment, 'key'],
      ['POST', '/api/v1/admin/aftersales/a1/preview-decision', decision, undefined],
      ['GET', '/api/v1/operations/key?scope=SHIP_ORDER', undefined, undefined],
    ]);
  });
  it('deduplicates writes, preserves UNKNOWN body and uses read-only recovery', async () => {
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      return r.url.includes('/operations/') ? { state: 'SUCCEEDED', resourceId: 'o1' } : order;
    });
    const c = createAdminShipmentController(x);
    await expect(c.submit(shipment)).rejects.toThrow();
    await expect(c.submit({ ...shipment, trackingNo: 'changed' })).rejects.toMatchObject({
      code: 'IDEMPOTENCY_CONFLICT',
    });
    expect(() => c.beginNew()).toThrow();
    await c.recover();
    expect(c.state.value).toEqual(order);
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET', 'GET']);
    expect(c.operation.state.requestSnapshot).toEqual(shipment);
  });
  it('requires deliberate new action after success and deduplicates double submit', async () => {
    const x = setup();
    const c = createAdminInventoryController(x);
    await Promise.all([
      c.submit({ delta: 1, reason: 'count' }),
      c.submit({ delta: 1, reason: 'count' }),
    ]);
    await c.submit({ delta: 1, reason: 'count' });
    expect(x.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    c.beginNew();
    await c.submit({ delta: -1, reason: 'count' });
    expect(x.requests.filter((r) => r.method === 'POST')).toHaveLength(2);
    expect(c.state.value?.book?.availableStock).toBe(10);
  });
  it('retains version-conflicted input and requires explicit validated correction', async () => {
    let fail = true;
    const x = setup((r) => {
      if (r.method === 'POST' && fail)
        throw new ApiError('VERSION_CONFLICT', 'stale', null, {}, 409);
      return order;
    });
    const c = createAdminShipmentController(x);
    await expect(c.submit(shipment)).rejects.toThrow('stale');
    expect(c.state.input).toEqual(shipment);
    expect(c.operation.state.status).toBe('FAILED');
    await expect(c.retry({ ...shipment, version: -1 })).rejects.toThrow();
    expect(c.operation.state.status).toBe('FAILED');
    fail = false;
    await c.retry({ ...shipment, version: 2 });
    expect(c.state.value).toEqual(order);
  });
  it('validates edit reason, inventory delta and returned quantities before dispatch', async () => {
    const x = setup();
    const c = createAdminShipmentController({
      ...x,
      getOrder: () => ({ ...order, shipment: { trackingNo: 'old' } as AdminOrder['shipment'] }),
    });
    await expect(c.submit(shipment)).rejects.toThrow();
    await expect(
      c.submit({ ...shipment, reason: 'corrected', trackingNo: 'bad space' }),
    ).rejects.toThrow();
    const inv = createAdminInventoryController(x);
    for (const delta of [0, 1.5, Number.MAX_SAFE_INTEGER + 1])
      await expect(inv.submit({ delta, reason: 'count' })).rejects.toThrow();
    const a = createAdminAftersaleController(x);
    for (const returnedQuantity of [-1, 1.5, 3])
      await expect(a.receiveReturn({ returnedQuantity })).rejects.toThrow();
    expect(x.requests).toHaveLength(0);
    await a.receiveReturn({ returnedQuantity: 0 });
    expect(a.receiveOperation.state.status).toBe('SUCCEEDED');
    expect(a.state.aftersale?.status).toBe('REQUESTED');
  });
  it('blocks revoked permission at scheduled dispatch and enforces role separation', async () => {
    const x = setup();
    const c = createAdminShipmentController(x);
    const pending = c.submit(shipment);
    x.login(['FINANCE_OPERATOR']);
    await expect(pending).rejects.toThrow();
    expect(x.requests).toHaveLength(0);
    await expect(
      createAdminInventoryController(x).submit({ delta: 1, reason: 'x' }),
    ).rejects.toThrow();
    x.login(['ORDER_OPERATOR']);
    const a = createAdminAftersaleController(x);
    await expect(a.preview(decision)).rejects.toThrow();
    x.login(['AUDIT_READ']);
    await expect(c.submit(shipment)).rejects.toThrow();
  });
  it('requires fresh preview after amount or reason edits and approves exact reviewed values', async () => {
    const x = setup();
    const c = createAdminAftersaleController(x);
    await c.preview(decision);
    c.setDecisionInput({ ...decision, reason: 'changed' });
    await expect(c.approve({ ...decision, reason: 'changed' })).rejects.toThrow();
    await c.preview(decision);
    await expect(c.approve({ ...decision, goodsRefundFen: 99 })).rejects.toThrow();
    await c.preview(decision);
    await c.approve(decision);
    expect(x.requests.find((r) => r.url.endsWith('/approve'))?.body).toEqual(decision);
    expect(c.state.aftersale?.status).toBe('REQUESTED');
  });
  it('enforces component budgets, safe totals, positive approval and rejection reason', async () => {
    const x = setup();
    const c = createAdminAftersaleController(x);
    for (const body of [
      { ...decision, goodsRefundFen: 0 },
      { ...decision, goodsRefundFen: -1 },
      { ...decision, goodsRefundFen: Number.MAX_SAFE_INTEGER, freightRefundFen: 1 },
    ])
      await expect(c.preview(body)).rejects.toThrow();
    await c.preview({ ...decision, goodsRefundFen: 101 });
    await expect(c.approve({ ...decision, goodsRefundFen: 101 })).rejects.toThrow();
    await expect(c.reject({ reason: ' ' })).rejects.toThrow();
    expect(x.requests.filter((r) => /\/(approve|reject)$/.test(r.url))).toHaveLength(0);
  });
  it('ignores out-of-order previews and late results after account changes', async () => {
    const resolves: ((v: unknown) => void)[] = [];
    const x = setup(() => new Promise((resolve) => resolves.push(resolve)));
    const c = createAdminAftersaleController(x);
    const first = c.preview(decision);
    const second = c.preview({ ...decision, goodsRefundFen: 90 });
    resolves[1]!(preview);
    await second;
    resolves[0]!({ ...preview, remainingGoodsFen: 1 });
    await first;
    expect(c.state.preview?.remainingGoodsFen).toBe(100);
    const third = c.preview(decision);
    x.login(['FINANCE_OPERATOR'], 'other');
    resolves[2]!(preview);
    await third;
    expect(c.state.preview).toBeNull();
  });
  it('suppresses authoritative reads from a previous account or disposed controller', async () => {
    let finish!: (v: unknown) => void;
    const x = setup((r) =>
      r.method === 'POST'
        ? order
        : new Promise((resolve) => {
            finish = resolve;
          }),
    );
    const c = createAdminShipmentController(x);
    const pending = c.submit(shipment);
    for (let i = 0; i < 15 && !finish; i++) await Promise.resolve();
    x.login(['ORDER_OPERATOR'], 'other');
    finish(order);
    await pending;
    expect(c.state.value).toBeNull();
  });
});
