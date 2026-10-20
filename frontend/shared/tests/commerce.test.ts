import { describe, expect, it } from 'vitest';
import { ApiError, createClient, type TransportRequest } from '../src/http';
import { createSession } from '../src/session';
import { createCommerceApi } from '../src/commerce/api';
import { createAddressController } from '../src/commerce/address';
import { createCheckoutController } from '../src/commerce/checkout';
import { bindOperationSession, createOperationController } from '../src/commerce/operation';

const key = '00000000-0000-4000-8000-000000000001';
const address = {
  version: 0,
  name: ' Buyer ',
  phone: '13800000000',
  provinceCode: '310000',
  cityCode: '310100',
  districtCode: '310101',
  detail: ' Street ',
  isDefault: true,
};
const book = { id: 'book1', type: 'BOOK' as const, availableStock: 8, quantityLimit: 5 };
const quote = {
  quoteId: 'q1',
  expiresAt: '2099-01-01T00:00:00Z',
  item: { productId: 'book1', type: 'BOOK', title: 'Book', quantity: 2, unitPriceFen: 100 },
  availableStock: 8,
  goodsAmountFen: 200,
  freightAmountFen: 60,
  payableAmountFen: 260,
  currency: 'CNY',
  warnings: [],
};
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
  session.set({
    accessToken: 'token',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'buyer', nickname: 'B', distributor: false },
  });
  const requests: TransportRequest[] = [];
  const api = createCommerceApi(
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
  return { api, session, storage, values, requests, namespace: 'test', keyGenerator: () => key };
}
describe('commerce boundaries', () => {
  it.each(['recover', 'submit'] as const)(
    'keeps repeated peer %s queries UNKNOWN while the original write is pending',
    async (repeat) => {
      let finishWrite!: (value: unknown) => void;
      let rejectQuery!: (reason: Error) => void;
      const x = setup((r) =>
        r.method === 'POST'
          ? new Promise((resolve) => {
              finishWrite = resolve;
            })
          : new Promise((_resolve, reject) => {
              rejectQuery = reject;
            }),
      );
      const opts = {
        ...x,
        scope: 'CREATE_ORDER',
        execute: (body: { quoteId: string }, k: string) => x.api.createOrder(body, k),
        query: (k: string) => x.api.operation(k),
      };
      const writer = createOperationController(opts);
      const peer = createOperationController({
        ...opts,
        keyGenerator: () => '00000000-0000-4000-8000-000000000002',
      });
      const write = writer.submit({ quoteId: 'q1' });
      await Promise.resolve();
      const query = peer.recover();
      await Promise.resolve();
      const repeated = repeat === 'recover' ? peer.recover() : peer.submit({ quoteId: 'q1' });
      expect(repeated).toBe(query);
      expect(peer.state.status).toBe('UNKNOWN');
      const settled = Promise.allSettled([query, repeated]);
      rejectQuery(new ApiError('NOT_FOUND', 'Operation not visible yet', 'r', {}, 404));
      await settled;
      expect(peer.state.status).toBe('UNKNOWN');
      expect(peer.state.key).toBe(key);
      expect(() => peer.reset()).toThrow();
      expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
      finishWrite({ id: 'o1' });
      await write;
      expect(writer.state.status).toBe('SUCCEEDED');
      await peer.recover();
      expect(peer.state.resourceId).toBe('o1');
      expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
      expect(createOperationController(opts).state.resourceId).toBe('o1');
    },
  );
  it('rejects invalid address without a write and trims the saved address', async () => {
    const x = setup((r) =>
      r.method === 'GET'
        ? { items: [], page: 1, pageSize: 20, total: 0 }
        : { ...(r.body as object), id: 'a1' },
    );
    const c = createAddressController(x.api);
    await expect(c.save({ ...address, phone: '123' })).rejects.toThrow();
    expect(x.requests).toHaveLength(0);
    expect(c.state.fieldErrors.phone).toBeTruthy();
    await c.save(address);
    expect(x.requests[0]?.body).toEqual({ ...address, name: 'Buyer', detail: 'Street' });
  });
  it('enforces book bounds and omits digital address at the transport boundary', async () => {
    const x = setup(() => ({ ...quote, item: { ...quote.item, type: 'COURSE', quantity: 1 } }));
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 6, addressId: 'a1' }, book);
    await expect(c.refresh()).rejects.toThrow();
    expect(x.requests).toHaveLength(0);
    c.setDraft(
      { productId: 'book1', quantity: 1, addressId: 'a1' },
      { id: 'book1', type: 'COURSE' },
    );
    await c.refresh();
    expect(x.requests[0]?.body).toEqual({ productId: 'book1', quantity: 1 });
  });
  it('requires confirmation of refreshed changed amounts and submits only the quote', async () => {
    let previews = 0;
    const x = setup((r) =>
      r.url.endsWith('/preview')
        ? { ...quote, quoteId: `q${++previews}`, payableAmountFen: previews === 1 ? 260 : 280 }
        : { id: 'o1' },
    );
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    await c.refresh();
    await expect(c.submit()).rejects.toThrow();
    c.confirmQuote();
    const a = c.submit();
    const b = c.submit();
    await Promise.all([a, b]);
    expect(x.requests.filter((r) => r.url.endsWith('/orders'))).toHaveLength(1);
    expect(x.requests.at(-1)?.body).toEqual({ quoteId: 'q3', attributionToken: null });
    expect(x.requests.at(-1)?.headers['Idempotency-Key']).toBe(key);
  });
  it('does not submit an expired quote', async () => {
    const x = setup(() => quote);
    let now = 0;
    const c = createCheckoutController({ ...x, now: () => now });
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    now = Date.parse(quote.expiresAt);
    await expect(c.submit()).rejects.toThrow();
    expect(x.requests).toHaveLength(1);
  });
  it('persists immutable unknown operations, only queries on retry and rejects different bodies', async () => {
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      return { state: 'SUCCEEDED', resourceId: 'o1' };
    });
    const opts = {
      ...x,
      scope: 'CREATE_ORDER',
      execute: (body: { quoteId: string }, k: string) => x.api.createOrder(body, k),
      query: (k: string) => x.api.operation(k),
    };
    const op = createOperationController(opts);
    const body = { quoteId: 'q1' };
    await expect(op.submit(body)).rejects.toThrow();
    body.quoteId = 'changed';
    expect(op.state.requestSnapshot).toEqual({ quoteId: 'q1' });
    expect(Object.isFrozen(op.state.requestSnapshot)).toBe(true);
    await expect(op.submit(body)).rejects.toThrow();
    op.dispose();
    const restored = createOperationController(opts);
    expect(restored.state.status).toBe('UNKNOWN');
    await restored.submit({ quoteId: 'q1' });
    expect(restored.state.resourceId).toBe('o1');
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
    x.session.clear();
    expect(restored.state.status).toBe('IDLE');
    expect([...x.values.keys()].filter((k) => k.includes('operation'))).toEqual([]);
  });
  it('never writes after a session changes while an operation is in flight', async () => {
    let finish!: (v: unknown) => void;
    const x = setup(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    });
    const pending = op.submit({ quoteId: 'q1' });
    await Promise.resolve();
    x.session.clear();
    finish({ id: 'o1' });
    await pending;
    expect(op.state.status).toBe('IDLE');
    expect([...x.values.keys()].filter((k) => k.includes('operation'))).toEqual([]);
  });
  it('cancels queued dispatch on immediate logout', async () => {
    const x = setup(() => ({ id: 'o1' }));
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    });
    const pending = op.submit({ quoteId: 'q1' });
    x.session.clear();
    await pending;
    expect(x.requests).toHaveLength(0);
  });
  it('retains server field errors and caller input after a version conflict', async () => {
    const x = setup(() => {
      throw new ApiError(
        'VERSION_CONFLICT',
        'Address changed',
        'r',
        { version: 'Refresh address' },
        409,
      );
    });
    const c = createAddressController(x.api);
    await expect(c.save(address, 'a1')).rejects.toThrow('Address changed');
    expect(c.state.fieldErrors).toEqual({ version: 'Refresh address' });
    expect(address.name).toBe(' Buyer ');
    expect(x.requests[0]?.method).toBe('PATCH');
    expect(x.requests[0]?.url).toBe('/api/v1/addresses/a1');
  });
  it('does not mint a new key when a known failed creation is clicked again', async () => {
    const x = setup((r) => {
      if (r.url.endsWith('/preview')) return quote;
      throw new ApiError('ORDER_STALE', 'Refresh preview', 'r', {}, 409);
    });
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    await expect(c.submit()).rejects.toThrow();
    await expect(c.submit()).rejects.toThrow();
    expect(x.requests.filter((r) => r.url.endsWith('/orders'))).toHaveLength(1);
  });
  it('keeps processing and missing recovery results UNKNOWN without rewriting', async () => {
    let queries = 0;
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      if (++queries === 1) return { state: 'PROCESSING', resourceId: null };
      throw new ApiError('NOT_FOUND', 'No result yet', 'r', {}, 404);
    });
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    });
    await expect(op.submit({ quoteId: 'q1' })).rejects.toThrow();
    await op.recover();
    expect(op.state.status).toBe('UNKNOWN');
    await expect(op.recover()).rejects.toThrow();
    expect(op.state.status).toBe('UNKNOWN');
    expect(() => op.reset()).toThrow();
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET', 'GET']);
  });
  it('clears persisted recovery on logout after its page has disposed', async () => {
    const x = setup(() => {
      throw new Error('timeout');
    });
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    });
    await expect(op.submit({ quoteId: 'q1' })).rejects.toThrow();
    op.dispose();
    x.session.clear();
    expect([...x.values.keys()].filter((k) => k.includes('operation'))).toEqual([]);
  });
  it('refreshes immediately before creation and pauses for a newly changed price', async () => {
    let previews = 0;
    const x = setup(() => ({
      ...quote,
      quoteId: `q${++previews}`,
      payableAmountFen: previews === 1 ? 260 : 270,
    }));
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    await expect(c.submit()).rejects.toThrow();
    expect(c.state.needsConfirmation).toBe(true);
    expect(x.requests.map((r) => r.url)).toEqual([
      '/api/v1/orders/preview',
      '/api/v1/orders/preview',
    ]);
  });
  it.each([0, -1, 1.5, 6, 100])(
    'rejects invalid book quantity %s before preview',
    async (quantity) => {
      const x = setup(() => quote);
      const c = createCheckoutController(x);
      c.setDraft({ productId: 'book1', quantity, addressId: 'a1' }, book);
      await expect(c.refresh()).rejects.toThrow();
      expect(x.requests).toHaveLength(0);
    },
  );
  it('requires confirmation after address changes even when the amount is unchanged', async () => {
    const x = setup(() => quote);
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a2' }, book);
    await c.refresh();
    expect(c.state.needsConfirmation).toBe(true);
    await expect(c.submit()).rejects.toThrow();
    expect(x.requests.every((r) => r.url.endsWith('/preview'))).toBe(true);
  });
  it('cleans a prior-session journal at app startup without reopening checkout', async () => {
    const x = setup(() => {
      throw new Error('timeout');
    });
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    });
    await expect(op.submit({ quoteId: 'q1' })).rejects.toThrow();
    op.dispose();
    const restarted = createSession(x.storage);
    bindOperationSession({ session: restarted, storage: x.storage, namespace: x.namespace });
    restarted.clear();
    expect([...x.values.keys()].filter((k) => k.includes('operation'))).toEqual([]);
  });
  it('refreshes the authoritative address page after delete', async () => {
    const x = setup((r) =>
      r.method === 'GET'
        ? { items: [{ ...address, id: 'a2', isDefault: false }], page: 1, pageSize: 20, total: 1 }
        : null,
    );
    const c = createAddressController(x.api);
    await c.remove('a1');
    expect(x.requests.map((r) => r.method)).toEqual(['DELETE', 'GET']);
    expect(c.pager.state.items[0]?.isDefault).toBe(false);
  });
  it('recovers the occupied slot through a second preexisting controller without another write', async () => {
    const x = setup((r) => {
      if (r.method === 'POST') throw new Error('timeout');
      return { state: 'SUCCEEDED', resourceId: 'o1' };
    });
    const opts = {
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    };
    const first = createOperationController(opts);
    const second = createOperationController({
      ...opts,
      keyGenerator: () => '00000000-0000-4000-8000-000000000002',
    });
    await expect(first.submit({ quoteId: 'q1' })).rejects.toThrow();
    await expect(second.submit({ quoteId: 'changed' })).rejects.toMatchObject({
      code: 'IDEMPOTENCY_CONFLICT',
    });
    await second.submit({ quoteId: 'q1' });
    expect(second.state.key).toBe(key);
    expect(second.state.resourceId).toBe('o1');
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET']);
    expect(x.requests[1]?.url).toBe(`/api/v1/operations/${key}?scope=CREATE_ORDER`);
  });
  it('does not let an idle controller reset another controller unresolved slot', async () => {
    const x = setup(() => {
      throw new Error('timeout');
    });
    const opts = {
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    };
    const first = createOperationController(opts);
    const second = createOperationController(opts);
    await expect(first.submit({ quoteId: 'q1' })).rejects.toThrow();
    expect(() => second.reset()).toThrow();
    const restored = createOperationController(opts);
    expect(restored.state.key).toBe(key);
    expect(restored.state.status).toBe('UNKNOWN');
  });
  it('does not let an old completed controller reset a newer occupied slot', async () => {
    const x = setup((r) => {
      if ((r.body as { quoteId?: string })?.quoteId === 'q2') throw new Error('timeout');
      return { id: 'o1' };
    });
    const opts = {
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    };
    const first = createOperationController(opts);
    const second = createOperationController({
      ...opts,
      keyGenerator: () => '00000000-0000-4000-8000-000000000002',
    });
    await first.submit({ quoteId: 'q1' });
    await second.submit({ quoteId: 'q1' });
    second.reset();
    await expect(second.submit({ quoteId: 'q2' })).rejects.toThrow();
    expect(() => first.reset()).toThrow();
    const restored = createOperationController(opts);
    expect(restored.state.key).toBe('00000000-0000-4000-8000-000000000002');
    expect(restored.state.requestSnapshot).toEqual({ quoteId: 'q2' });
  });
  it('allows a confirmed adjusted quantity through an unchanged pre-submit preview', async () => {
    let previews = 0;
    const x = setup((r) =>
      r.url.endsWith('/preview')
        ? { ...quote, quoteId: `q${++previews}`, item: { ...quote.item, quantity: 1 } }
        : { id: 'o1' },
    );
    const c = createCheckoutController(x);
    c.setDraft({ productId: 'book1', quantity: 2, addressId: 'a1' }, book);
    await c.refresh();
    expect(c.state.needsConfirmation).toBe(true);
    c.confirmQuote();
    await c.submit();
    expect(c.operation.state.resourceId).toBe('o1');
    expect(x.requests.filter((r) => r.url.endsWith('/orders'))).toHaveLength(1);
    expect(x.requests.at(-1)?.body).toEqual({ quoteId: 'q2', attributionToken: null });
  });
  it('keeps a malformed 409 write response UNKNOWN and queries the original key', async () => {
    const x = setup(() => null);
    const requests: TransportRequest[] = [];
    const api = createCommerceApi(
      createClient({
        baseUrl: '/api/v1',
        session: x.session,
        transport: async (r) => {
          requests.push(r);
          return r.method === 'POST'
            ? { status: 409, body: { message: 'malformed' } }
            : {
                status: 200,
                body: {
                  code: 'OK',
                  message: '',
                  requestId: 'r',
                  data: { state: 'SUCCEEDED', resourceId: 'o1' },
                },
              };
        },
      }),
    );
    const op = createOperationController({
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => api.createOrder(b, k),
      query: (k: string) => api.operation(k),
    });
    await expect(op.submit({ quoteId: 'q1' })).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    expect(op.state.status).toBe('UNKNOWN');
    expect(() => op.reset()).toThrow();
    await op.recover();
    expect(op.state.resourceId).toBe('o1');
    expect(requests.map((r) => r.method)).toEqual(['POST', 'GET']);
    expect(requests[1]?.url).toBe(`/api/v1/operations/${key}?scope=CREATE_ORDER`);
  });
  it('does not let a late result overwrite a newer action after peer recovery and reset', async () => {
    let finish!: (value: unknown) => void;
    const x = setup((r) => {
      if (r.method === 'GET') return { state: 'SUCCEEDED', resourceId: 'o1' };
      if ((r.body as { quoteId: string }).quoteId === 'q1')
        return new Promise((resolve) => {
          finish = resolve;
        });
      throw new Error('timeout');
    });
    const opts = {
      ...x,
      scope: 'CREATE_ORDER',
      execute: (b: { quoteId: string }, k: string) => x.api.createOrder(b, k),
      query: (k: string) => x.api.operation(k),
    };
    const first = createOperationController(opts);
    const second = createOperationController({
      ...opts,
      keyGenerator: () => '00000000-0000-4000-8000-000000000002',
    });
    const original = first.submit({ quoteId: 'q1' });
    await Promise.resolve();
    await second.submit({ quoteId: 'q1' });
    second.reset();
    await expect(second.submit({ quoteId: 'q2' })).rejects.toThrow();
    finish({ id: 'o1' });
    await original;
    const restored = createOperationController(opts);
    expect(restored.state.key).toBe('00000000-0000-4000-8000-000000000002');
    expect(restored.state.requestSnapshot).toEqual({ quoteId: 'q2' });
    expect(restored.state.status).toBe('UNKNOWN');
    expect(x.requests.map((r) => r.method)).toEqual(['POST', 'GET', 'POST']);
  });
});
