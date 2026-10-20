import { describe, expect, it, vi } from 'vitest';
import { ApiError, createClient, type TransportRequest } from '../src/http';
import { createSession } from '../src/session';
import { bindOperationSession } from '../src/commerce/operation';
import {
  createOrderApi,
  createOrderActionController,
  orderStatusLabel,
  shipmentLabel,
  type CustomerOrder,
} from '../src/commerce/order';
import { createPaymentApi, createPaymentController } from '../src/commerce/payment';
import {
  createAftersaleApi,
  createAftersaleController,
  aftersaleStatusLabel,
} from '../src/commerce/aftersale';

const key = '12345678-1234-1234-1234-123456789abc';
function setup(handler: (r: TransportRequest) => unknown | Promise<unknown>) {
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
  const login = (id: string) =>
    session.set({
      accessToken: id,
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, nickname: id, distributor: false },
    });
  login('actor');
  const requests: TransportRequest[] = [];
  const client = createClient({
    baseUrl: '/api',
    session,
    transport: async (r) => {
      requests.push(r);
      return {
        status: 200,
        body: { code: 'OK', message: '', requestId: 'r', data: await handler(r) },
      };
    },
  });
  let current: CustomerOrder = {
    id: 'o1',
    orderNo: 'N1',
    orderStatus: 'PENDING_PAYMENT',
    fulfillmentStatus: 'PENDING_SHIPMENT',
    refundStatus: 'NONE',
    goodsAmountFen: 1000,
    freightAmountFen: 100,
    payableAmountFen: 1100,
    expiresAt: null,
    paidAt: null,
    completedAt: null,
    item: { productId: 'p1', type: 'BOOK', title: 'Book', quantity: 1, unitPriceFen: 1000 },
    allowedActions: ['PAY', 'CANCEL', 'APPLY_AFTERSALE'],
  };
  const orderApi = createOrderApi(client);
  return {
    client,
    session,
    storage,
    requests,
    orderApi,
    login,
    getOrder: () => current,
    onOrder: (o: CustomerOrder) => {
      current = o;
    },
    namespace: 'test',
    orderId: 'o1',
    keyGenerator: () => key,
  };
}
const prepay = {
  provider: 'WECHAT_PAY',
  paymentId: 'p1',
  state: 'PENDING',
  params: { timeStamp: '1', nonceStr: 'n', package: 'prepay_id=x', signType: 'RSA', paySign: 's' },
};

describe('customer order public boundaries', () => {
  it('restores an id-only PREPAY outcome and reacquires the same payment with the original key only on explicit pay', async () => {
    let posts = 0;
    const s = setup((r) => {
      if (r.method === 'POST') {
        if (++posts === 1) throw Error('lost response');
        return prepay;
      }
      return r.url.includes('/operations/')
        ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'success' as const);
    const options = {
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production' as const,
      adapter,
      delay: async () => {},
    };
    const original = createPaymentController(options);
    await expect(original.pay()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    original.dispose();
    const restored = createPaymentController(options);
    await restored.recover();
    expect(posts).toBe(1);
    expect(adapter).not.toHaveBeenCalled();
    await Promise.all([restored.pay(), restored.pay()]);
    expect(adapter).toHaveBeenCalledOnce();
    expect(restored.state.status).toBe('PENDING');
    expect(restored.state.automaticQueries).toBe(5);
    expect(
      s.requests
        .filter((r) => r.method === 'POST')
        .map((r) => [r.url, r.headers['Idempotency-Key'], r.body]),
    ).toEqual([
      ['/api/payments/wechat/prepay', key, { orderId: 'o1' }],
      ['/api/payments/wechat/prepay', key, { orderId: 'o1' }],
    ]);
  });
  it('does not reacquire prepay while original intent is processing', async () => {
    const s = setup((r) => {
      if (r.method === 'POST') throw Error('lost response');
      return { state: 'PROCESSING', resourceId: null };
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.pay()).rejects.toThrow();
    await c.pay();
    expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    expect(c.state.status).toBe('UNKNOWN');
    expect(adapter).not.toHaveBeenCalled();
  });
  it('retains the original key if same-payment parameter recovery fails and retries it explicitly', async () => {
    let posts = 0;
    const s = setup((r) => {
      if (r.method === 'POST') {
        if (++posts < 3) throw Error('network');
        return prepay;
      }
      return r.url.includes('/operations/')
        ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'cancel' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
      delay: async () => {},
    });
    await expect(c.pay()).rejects.toThrow();
    await expect(c.pay()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(c.operation.state.key).toBe(key);
    await c.pay();
    expect(
      s.requests.filter((r) => r.method === 'POST').map((r) => r.headers['Idempotency-Key']),
    ).toEqual([key, key, key]);
    expect(adapter).toHaveBeenCalledOnce();
  });
  it.each(['PAID', 'CANCELLED'] as const)(
    'honors authoritative %s before reacquiring an id-only payment',
    async (orderStatus) => {
      const s = setup((r) => {
        if (r.method === 'POST') throw Error('lost response');
        return r.url.includes('/operations/')
          ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
          : { ...s.getOrder(), orderStatus, allowedActions: [] };
      });
      const adapter = vi.fn(async () => 'success' as const);
      const c = createPaymentController({
        ...s,
        api: createPaymentApi(s.client, { mode: 'production' }),
        mode: 'production',
        adapter,
      });
      await expect(c.pay()).rejects.toThrow();
      await c.pay();
      expect(c.state.status).toBe(orderStatus);
      expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
      expect(adapter).not.toHaveBeenCalled();
    },
  );
  it('rejects a mismatched payment identity from same-key recovery without invoking the adapter', async () => {
    let posts = 0;
    const s = setup((r) => {
      if (r.method === 'POST') {
        if (++posts === 1) throw Error('lost response');
        return { ...prepay, paymentId: 'another-payment' };
      }
      return r.url.includes('/operations/')
        ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.pay()).rejects.toThrow();
    await expect(c.pay()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    expect(c.operation.state.key).toBe(key);
    expect(adapter).not.toHaveBeenCalled();
  });
  it('rejects mismatched legacy payment parameters in an operation outcome', async () => {
    const s = setup((r) => {
      if (r.method === 'POST') throw Error('lost response');
      return r.url.includes('/operations/')
        ? {
            state: 'SUCCEEDED',
            resourceId: 'p1',
            response: { ...prepay, paymentId: 'other-payment' },
          }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
      delay: async () => {},
    });
    await expect(c.pay()).rejects.toThrow();
    await expect(c.pay()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    expect(adapter).not.toHaveBeenCalled();
    expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
  });
  it('ignores a delayed same-payment recovery after an account change', async () => {
    let posts = 0;
    let finish!: (value: unknown) => void;
    const s = setup((r) => {
      if (r.method === 'POST') {
        if (++posts === 1) throw Error('lost response');
        return new Promise((resolve) => {
          finish = resolve;
        });
      }
      return r.url.includes('/operations/')
        ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.pay()).rejects.toThrow();
    const pending = c.pay();
    await vi.waitFor(() => expect(posts).toBe(2));
    s.login('another-actor');
    finish(prepay);
    await pending;
    expect(c.state.status).toBe('IDLE');
    expect(c.state.order).toBeNull();
    expect(adapter).not.toHaveBeenCalled();
  });
  it.each(['refund', 'returnShipment'] as const)(
    'allows corrected %s after authoritative rejection with a new key',
    async (action) => {
      const caseView = {
        id: 'a1',
        orderId: 'o1',
        type: 'RETURN_REFUND' as const,
        status: 'RETURN_REQUIRED',
        reason: 'reason',
        requestedGoodsFen: 100,
        requestedFreightFen: 0,
        approvedGoodsFen: 100,
        approvedFreightFen: 0,
        returnedQuantity: 0,
        receivedQuantity: 0,
        returnAddress: 'address',
        returnCarrierCode: null,
        returnTrackingNo: null,
        evidenceAssetIds: [],
        allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
      };
      let posts = 0;
      let keys = 0;
      const s = setup((r) => {
        if (r.method === 'POST') {
          if (++posts === 1)
            throw new ApiError(
              'VALIDATION_ERROR',
              'server rejected',
              null,
              { [action === 'refund' ? 'reason' : 'trackingNo']: 'correct this field' },
              422,
            );
          return caseView;
        }
        return r.url === '/api/orders/o1' ? s.getOrder() : caseView;
      });
      const c = createAftersaleController({
        ...s,
        api: createAftersaleApi(s.client),
        getAftersale: () => caseView,
        keyGenerator: () => `12345678-1234-1234-1234-${String(++keys).padStart(12, '0')}`,
      });
      const submit = (value: string) =>
        action === 'refund'
          ? c.refund({
              type: 'RETURN_REFUND',
              reason: value,
              evidenceAssetIds: [],
              requestedGoodsFen: 100,
              requestedFreightFen: 0,
            })
          : c.returnShipment('a1', { carrierCode: 'SF', trackingNo: value });
      await expect(submit('original')).rejects.toThrow('server rejected');
      if (action === 'returnShipment') expect(c.getReturnStatus('a1')).toBe('FAILED');
      expect(c.state.fieldErrors).toEqual({
        [action === 'refund' ? 'reason' : 'trackingNo']: 'correct this field',
      });
      await submit('corrected');
      const writes = s.requests.filter((r) => r.method === 'POST');
      expect(writes).toHaveLength(2);
      expect(writes[0]?.headers['Idempotency-Key']).not.toBe(writes[1]?.headers['Idempotency-Key']);
      expect(writes[1]?.body).toMatchObject({
        [action === 'refund' ? 'reason' : 'trackingNo']: 'corrected',
      });
      expect(c.state.aftersale).toEqual(caseView);
      expect(c.state.fieldErrors).toEqual({});
      if (action === 'returnShipment') expect(c.getReturnStatus('a1')).toBe('SUCCEEDED');
    },
  );
  it.each(['refund', 'returnShipment'] as const)(
    'keeps UNKNOWN %s input and key immutable when corrected input is submitted',
    async (action) => {
      const caseView = {
        id: 'a1',
        orderId: 'o1',
        type: 'RETURN_REFUND' as const,
        status: 'RETURN_REQUIRED',
        reason: 'reason',
        requestedGoodsFen: 100,
        requestedFreightFen: 0,
        approvedGoodsFen: 100,
        approvedFreightFen: 0,
        returnedQuantity: 0,
        receivedQuantity: 0,
        returnAddress: 'address',
        returnCarrierCode: null,
        returnTrackingNo: null,
        evidenceAssetIds: [],
        allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
      };
      const s = setup((r) => {
        if (r.method === 'POST') throw Error('timeout');
        return { state: 'PROCESSING', resourceId: null };
      });
      const c = createAftersaleController({
        ...s,
        api: createAftersaleApi(s.client),
        getAftersale: () => caseView,
      });
      const submit = (value: string) =>
        action === 'refund'
          ? c.refund({
              type: 'RETURN_REFUND',
              reason: value,
              evidenceAssetIds: [],
              requestedGoodsFen: 100,
              requestedFreightFen: 0,
            })
          : c.returnShipment('a1', { carrierCode: 'SF', trackingNo: value });
      await expect(submit('original')).rejects.toThrow();
      if (action === 'returnShipment') expect(c.getReturnStatus('a1')).toBe('UNKNOWN');
      await expect(submit('corrected')).rejects.toMatchObject({ code: 'IDEMPOTENCY_CONFLICT' });
      await submit('original');
      expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
      expect(s.requests.filter((r) => r.method === 'GET').map((r) => r.url)).toEqual([
        `/api/operations/${key}?scope=${action === 'refund' ? 'APPLY_REFUND' : 'RETURN_SHIPMENT'}`,
      ]);
    },
  );
  it('allows a new explicit prepay attempt after definitive failure with a new key', async () => {
    let writes = 0;
    let keys = 0;
    const s = setup((r) => {
      if (r.method === 'POST') {
        writes++;
        if (writes === 1)
          throw new ApiError('PREPAY_REJECTED', 'definitively rejected', null, {}, 422);
        return prepay;
      }
      return { ...s.getOrder(), orderStatus: 'PAID' };
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      keyGenerator: () => `12345678-1234-1234-1234-${String(++keys).padStart(12, '0')}`,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.pay()).rejects.toThrow('definitively rejected');
    expect(c.operation.state.status).toBe('FAILED');
    await c.pay();
    const requests = s.requests.filter((r) => r.method === 'POST');
    expect(requests).toHaveLength(2);
    expect(requests[0]?.headers['Idempotency-Key']).not.toBe(
      requests[1]?.headers['Idempotency-Key'],
    );
    expect(adapter).toHaveBeenCalledOnce();
    expect(c.state.status).toBe('PAID');
  });
  it.each(['cancel', 'failure'] as const)(
    'explicit pay retries %s callback with the same known prepay and deduplicates concurrent retry',
    async (callback) => {
      let attempts = 0;
      const s = setup((r) =>
        r.method === 'POST'
          ? prepay
          : { ...s.getOrder(), orderStatus: attempts > 1 ? 'PAID' : 'PENDING_PAYMENT' },
      );
      const adapter = vi.fn(async () => {
        attempts++;
        return attempts === 1 ? callback : ('success' as const);
      });
      const c = createPaymentController({
        ...s,
        api: createPaymentApi(s.client, { mode: 'production' }),
        mode: 'production',
        adapter,
        delay: async () => {},
      });
      await c.pay();
      await Promise.all([c.pay(), c.pay()]);
      expect(adapter).toHaveBeenCalledTimes(2);
      expect(adapter.mock.calls[0]).toEqual(adapter.mock.calls[1]);
      expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
      expect(c.operation.state.key).toBe(key);
      expect(c.state.status).toBe('PAID');
    },
  );
  it.each(['SUCCEEDED', 'UNKNOWN'] as const)(
    'restored %s prepay recovery is read-only until explicit pay uses stored parameters',
    async (status) => {
      const s = setup((r) =>
        r.method === 'POST'
          ? (() => {
              if (status === 'UNKNOWN') throw Error('timeout');
              return prepay;
            })()
          : r.url.includes('/operations/')
            ? { state: 'SUCCEEDED', resourceId: 'p1', response: prepay }
            : s.getOrder(),
      );
      const adapter = vi.fn(async () => 'cancel' as const);
      const options = {
        ...s,
        api: createPaymentApi(s.client, { mode: 'production' }),
        mode: 'production' as const,
        adapter,
        delay: async () => {},
      };
      const original = createPaymentController(options);
      if (status === 'UNKNOWN') await expect(original.pay()).rejects.toThrow();
      else await original.pay();
      original.dispose();
      const restored = createPaymentController(options);
      const previousCalls = adapter.mock.calls.length;
      const previousReads = s.requests.filter((r) => r.url === '/api/orders/o1').length;
      await restored.recover();
      expect(s.requests.filter((r) => r.url.includes('/operations/')).map((r) => r.url)).toEqual([
        `/api/operations/${key}?scope=PREPAY`,
      ]);
      expect(adapter).toHaveBeenCalledTimes(previousCalls);
      expect(s.requests.filter((r) => r.url === '/api/orders/o1')).toHaveLength(previousReads + 1);
      await Promise.all([restored.pay(), restored.pay()]);
      expect(adapter).toHaveBeenCalledTimes(previousCalls + 1);
      expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
      expect(restored.state.status).toBe('PENDING');
    },
  );
  it('dispatches the documented WECHAT_PAY production prepay DTO', async () => {
    const s = setup((r) =>
      r.method === 'POST' ? prepay : { ...s.getOrder(), orderStatus: 'PAID' },
    );
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await c.pay();
    expect(adapter).toHaveBeenCalledWith(prepay);
    expect(c.state.status).toBe('PAID');
  });
  it.each(['prepay', 'recovery'] as const)(
    'rechecks PAY immediately before adapter dispatch after deferred %s',
    async (path) => {
      let release!: (value: unknown) => void;
      const s = setup((r) => {
        if (r.method === 'POST' && path === 'recovery') throw Error('timeout');
        if (r.url === '/api/orders/o1') return s.getOrder();
        return new Promise((resolve) => {
          release = resolve;
        });
      });
      const adapter = vi.fn(async () => 'success' as const);
      const c = createPaymentController({
        ...s,
        api: createPaymentApi(s.client, { mode: 'test' }),
        mode: 'test',
        adapter,
      });
      if (path === 'recovery') await expect(c.pay()).rejects.toThrow();
      const task = path === 'prepay' ? c.pay() : c.recover();
      const completed =
        path === 'prepay'
          ? expect(task).rejects.toMatchObject({ code: 'ACTION_NOT_ALLOWED' })
          : expect(task).resolves.toBeUndefined();
      await vi.waitFor(() => expect(release).toBeTypeOf('function'));
      s.onOrder({ ...s.getOrder(), orderStatus: 'CANCELLED', allowedActions: [] });
      release(
        path === 'prepay' ? prepay : { state: 'SUCCEEDED', resourceId: 'p1', response: prepay },
      );
      await completed;
      if (path === 'recovery') {
        expect(c.state.status).toBe('CANCELLED');
        await expect(c.pay()).rejects.toMatchObject({ code: 'ACTION_NOT_ALLOWED' });
      }
      expect(adapter).not.toHaveBeenCalled();
      expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    },
  );
  it.each(['prepay', 'recovery'] as const)(
    'blocks explicitly simulated allowed-provider DTO from production %s',
    async (path) => {
      const simulated = { ...prepay, simulated: true };
      const s = setup((r) => {
        if (r.method === 'POST') {
          if (path === 'recovery') throw Error('timeout');
          return simulated;
        }
        if (r.url === '/api/orders/o1') return { ...s.getOrder(), orderStatus: 'PAID' };
        return { state: 'SUCCEEDED', resourceId: 'p1', response: simulated };
      });
      const adapter = vi.fn(async () => 'success' as const);
      const c = createPaymentController({
        ...s,
        api: createPaymentApi(s.client, { mode: 'production' }),
        mode: 'production',
        adapter,
      });
      if (path === 'recovery') await expect(c.pay()).rejects.toThrow();
      await expect(path === 'prepay' ? c.pay() : c.recover()).rejects.toMatchObject({
        code: 'PROVIDER_DISABLED',
      });
      expect(adapter).not.toHaveBeenCalled();
      expect(c.state.simulated).toBe(false);
    },
  );
  it('uses one-based order list and safely labels unknown values and missing logistics', async () => {
    const s = setup(() => ({ items: [], page: 1, pageSize: 20, total: 0 }));
    await s.orderApi.orders('ALL', 1, 20);
    expect(s.requests[0]?.url).toBe('/api/orders?tab=ALL&page=1&pageSize=20');
    expect(() => s.orderApi.orders('ALL', 0, 20)).toThrow();
    expect(orderStatusLabel('FUTURE')).toContain('FUTURE');
    expect(orderStatusLabel('toString')).toContain('toString');
    expect(aftersaleStatusLabel('constructor')).toContain('constructor');
    expect(shipmentLabel({ trackingNo: null, events: [] })).toBe('等待发货');
    expect(shipmentLabel({ trackingNo: 'T1', events: [] })).toBe('物流同步中');
  });
  it('startup base namespace binding clears disposed page journals after restart and logout', async () => {
    const s = setup(() => {
      throw Error('timeout');
    });
    const c = createOrderActionController({ ...s, api: s.orderApi, action: 'CANCEL' });
    await expect(c.submit()).rejects.toThrow();
    c.dispose();
    const restarted = createSession(s.storage);
    bindOperationSession({ session: restarted, storage: s.storage, namespace: s.namespace });
    restarted.clear();
    // Re-login as the original actor cannot resurrect a journal from the previous login.
    restarted.set({
      accessToken: 'new',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'actor', nickname: 'actor', distributor: false },
    });
    const fresh = createOrderActionController({
      ...s,
      session: restarted,
      api: s.orderApi,
      action: 'CANCEL',
    });
    expect(fresh.operation.state.status).toBe('IDLE');
  });
  it('checks current allowed actions and rereads authoritative order after cancellation', async () => {
    const s = setup(() => ({ ...s.getOrder(), orderStatus: 'CANCELLED', allowedActions: [] }));
    const c = createOrderActionController({ ...s, api: s.orderApi, action: 'CANCEL' });
    s.getOrder().allowedActions = [];
    await expect(c.submit()).rejects.toThrow();
    expect(s.requests).toHaveLength(0);
    s.getOrder().allowedActions = ['CANCEL'];
    await c.submit();
    expect(s.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
    expect(s.requests[0]?.headers['Idempotency-Key']).toBe(key);
    expect(s.getOrder().orderStatus).toBe('CANCELLED');
  });
  it.each(['success', 'cancel', 'failure'] as const)(
    'treats %s provider callback as pending until server confirms, with exactly five queries',
    async (callback) => {
      const s = setup((r) => (r.method === 'POST' ? prepay : s.getOrder()));
      const adapter = vi.fn(async () => callback);
      const c = createPaymentController({
        ...s,
        api: createPaymentApi(s.client, { mode: 'production' }),
        mode: 'production',
        adapter,
        delay: async () => {},
      });
      await c.pay();
      expect(c.state.status).toBe('PENDING');
      expect(c.state.callback).toBe(callback);
      expect(s.requests.filter((r) => r.method === 'GET')).toHaveLength(5);
      await c.refresh();
      expect(s.requests.filter((r) => r.method === 'GET')).toHaveLength(6);
      expect(adapter).toHaveBeenCalledOnce();
    },
  );
  it('server paid wins over cancelled callback', async () => {
    const s = setup((r) =>
      r.method === 'POST' ? prepay : { ...s.getOrder(), orderStatus: 'PAID' },
    );
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter: async () => 'cancel',
      delay: async () => {},
    });
    await c.pay();
    expect(c.state.status).toBe('PAID');
    expect(s.requests.filter((r) => r.method === 'GET')).toHaveLength(1);
  });
  it('recovers unknown prepay using original key and never fabricates adapter parameters', async () => {
    const s = setup((r) => {
      if (r.method === 'POST') throw Error('timeout');
      return r.url.includes('/operations/')
        ? { state: 'SUCCEEDED', resourceId: 'p1', response: { id: 'p1' } }
        : s.getOrder();
    });
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.pay()).rejects.toThrow();
    expect(c.state.status).toBe('UNKNOWN');
    await c.recover();
    expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    expect(s.requests[1]?.url).toBe(`/api/operations/${key}?scope=PREPAY`);
    expect(c.state.status).toBe('PARAMS_UNAVAILABLE');
    expect(adapter).not.toHaveBeenCalled();
  });
  it('actor change ignores late prepay and never dispatches adapter', async () => {
    let release!: (v: unknown) => void;
    const s = setup(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    const task = c.pay();
    await vi.waitFor(() => expect(s.requests).toHaveLength(1));
    s.login('other');
    release(prepay);
    await task;
    expect(adapter).not.toHaveBeenCalled();
    expect(c.state.order).toBeNull();
  });
  it('rejects DEV simulator and DEV provider dispatch in production', async () => {
    const s = setup(() => ({ ...prepay, provider: 'DEV' }));
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
    });
    await expect(c.simulate('SUCCESS')).rejects.toThrow();
    await expect(c.pay()).rejects.toThrow();
    expect(adapter).not.toHaveBeenCalled();
    expect(s.requests).toHaveLength(1);
  });
  it('validates refund fields without writes and preserves return-required server data', async () => {
    const caseView = {
      id: 'a1',
      orderId: 'o1',
      type: 'RETURN_REFUND' as const,
      status: 'RETURN_REQUIRED',
      reason: 'reason',
      requestedGoodsFen: 100,
      requestedFreightFen: 0,
      approvedGoodsFen: 100,
      approvedFreightFen: 0,
      returnedQuantity: 0,
      receivedQuantity: 0,
      returnAddress: 'server address',
      returnCarrierCode: null,
      returnTrackingNo: null,
      evidenceAssetIds: [],
      allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
      rejectionReason: null,
    };
    const s = setup((r) =>
      r.url.includes('/orders/o1') && r.method === 'GET' ? s.getOrder() : caseView,
    );
    const c = createAftersaleController({
      ...s,
      api: createAftersaleApi(s.client),
      getAftersale: () => caseView,
    });
    const body = {
      type: 'RETURN_REFUND' as const,
      reason: ' reason ',
      evidenceAssetIds: ['asset1'],
      requestedGoodsFen: 100,
      requestedFreightFen: 0,
    };
    for (const invalid of [
      { reason: ' ' },
      { requestedGoodsFen: -1 },
      { requestedGoodsFen: 1001 },
      { evidenceAssetIds: ['x', 'x'] },
      { evidenceAssetIds: ['data:image/png;base64,abc'] },
    ])
      await expect(c.refund({ ...body, ...invalid })).rejects.toThrow();
    expect(s.requests).toHaveLength(0);
    await c.refund(body);
    expect(s.requests[0]?.body).toEqual({ ...body, reason: 'reason' });
    expect(c.state.aftersale).toEqual(caseView);
    expect(aftersaleStatusLabel('FUTURE')).toContain('FUTURE');
    await expect(c.returnShipment('a1', { carrierCode: '', trackingNo: '' })).rejects.toThrow();
    await c.returnShipment('a1', { carrierCode: 'SF', trackingNo: 'SF-123' });
    expect(s.requests.find((r) => r.url.endsWith('/return-shipment'))?.body).toEqual({
      carrierCode: 'SF',
      trackingNo: 'SF-123',
    });
    expect(c.state.aftersale?.allowedActions).toEqual(['SUBMIT_RETURN_SHIPMENT']);
  });
  it('repeated payment after provider dispatch performs only one manual authoritative refresh', async () => {
    const s = setup((r) => (r.method === 'POST' ? prepay : s.getOrder()));
    const adapter = vi.fn(async () => 'success' as const);
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter,
      delay: async () => {},
    });
    await c.pay();
    await c.pay();
    expect(c.state.status).toBe('PENDING');
    expect(s.requests.filter((r) => r.method === 'GET')).toHaveLength(6);
    expect(adapter).toHaveBeenCalledOnce();
  });
  it('stops polling when disposed while awaiting poll delay', async () => {
    let resume!: () => void;
    const s = setup((r) => (r.method === 'POST' ? prepay : s.getOrder()));
    const c = createPaymentController({
      ...s,
      api: createPaymentApi(s.client, { mode: 'production' }),
      mode: 'production',
      adapter: async () => 'success',
      delay: () =>
        new Promise<void>((resolve) => {
          resume = resolve;
        }),
    });
    const task = c.pay();
    await vi.waitFor(() => expect(resume).toBeTypeOf('function'));
    c.dispose();
    resume();
    await task;
    expect(s.requests.filter((r) => r.method === 'GET')).toHaveLength(1);
  });
  it('retains server field errors and original refund input after failure', async () => {
    const s = setup(() => {
      throw new ApiError('REFUND_DENIED', 'server refused', 'r1', { reason: 'server reason' }, 422);
    });
    const c = createAftersaleController({ ...s, api: createAftersaleApi(s.client) });
    const body = {
      type: 'REFUND_ONLY' as const,
      reason: ' original ',
      evidenceAssetIds: [],
      requestedGoodsFen: 100,
      requestedFreightFen: 0,
    };
    await expect(c.refund(body)).rejects.toThrow('server refused');
    expect(c.state.fieldErrors).toEqual({ reason: 'server reason' });
    expect(c.state.refundInput).toEqual(body);
    expect(c.refundOperation.state.requestSnapshot?.reason).toBe('original');
  });
  it('rejects refund totals beyond known payable budget and all input boundary violations', async () => {
    const s = setup(() => null);
    s.getOrder().payableAmountFen = 900;
    const c = createAftersaleController({ ...s, api: createAftersaleApi(s.client) });
    const body = {
      type: 'REFUND_ONLY' as const,
      reason: 'valid',
      evidenceAssetIds: [],
      requestedGoodsFen: 1000,
      requestedFreightFen: 0,
    };
    await expect(c.refund(body)).rejects.toThrow();
    for (const bad of [
      { reason: 'x'.repeat(501) },
      { requestedGoodsFen: 0 },
      { requestedGoodsFen: 1.5 },
      { requestedGoodsFen: Number.MAX_SAFE_INTEGER + 1 },
      { evidenceAssetIds: Array.from({ length: 11 }, (_, i) => `asset${i}`) },
    ])
      await expect(c.refund({ ...body, requestedGoodsFen: 1, ...bad })).rejects.toThrow();
    expect(s.requests).toHaveLength(0);
  });
  it('never grants return action for unknown case states or changed server actions', async () => {
    const s = setup(() => null);
    let caseView = {
      id: 'a1',
      orderId: 'o1',
      type: 'RETURN_REFUND' as const,
      status: 'FUTURE',
      reason: 'r',
      requestedGoodsFen: 1,
      requestedFreightFen: 0,
      approvedGoodsFen: null,
      approvedFreightFen: null,
      returnedQuantity: null,
      receivedQuantity: null,
      returnAddress: null,
      returnCarrierCode: null,
      returnTrackingNo: null,
      evidenceAssetIds: [],
      allowedActions: ['SUBMIT_RETURN_SHIPMENT'],
    };
    const c = createAftersaleController({
      ...s,
      api: createAftersaleApi(s.client),
      getAftersale: () => caseView,
    });
    await expect(
      c.returnShipment('a1', { carrierCode: 'SF', trackingNo: 'T-1' }),
    ).rejects.toThrow();
    caseView = { ...caseView, status: 'RETURN_REQUIRED', allowedActions: [] };
    await expect(
      c.returnShipment('a1', { carrierCode: 'SF', trackingNo: 'T-1' }),
    ).rejects.toThrow();
    expect(s.requests).toHaveLength(0);
  });
  it('keeps unknown simulator scenario immutable until recovered, then permits another scenario', async () => {
    let resolved = false;
    const s = setup((r) => {
      if (r.method === 'POST') {
        if (!resolved) throw Error('timeout');
        return { simulated: true };
      }
      if (r.url.includes('/operations/'))
        return { state: resolved ? 'SUCCEEDED' : 'PROCESSING', resourceId: resolved ? 'o1' : null };
      return s.getOrder();
    });
    let keyIndex = 0;
    const c = createPaymentController({
      ...s,
      keyGenerator: () => `12345678-1234-1234-1234-${String(++keyIndex).padStart(12, '0')}`,
      api: createPaymentApi(s.client, { mode: 'test' }),
      mode: 'test',
      adapter: async () => 'success',
      delay: async () => {},
    });
    await expect(c.simulate('PENDING')).rejects.toThrow();
    await expect(c.simulate('SUCCESS')).rejects.toThrow();
    await c.simulate('PENDING');
    expect(s.requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    resolved = true;
    await c.simulate('PENDING');
    await c.simulate('SUCCESS');
    const writes = s.requests.filter((r) => r.method === 'POST');
    expect(writes).toHaveLength(2);
    expect(writes[0]?.headers['Idempotency-Key']).not.toBe(writes[1]?.headers['Idempotency-Key']);
    expect(c.state.simulated).toBe(true);
  });
});
