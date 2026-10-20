import { describe, expect, it, vi } from 'vitest';
import { formatFen, parsePercent, parseYuan } from '../src/money';
import { readEnvironment } from '../src/environment';
import { createSession, type SessionStorage } from '../src/session';
import { createClient, type Transport } from '../src/http';
import { createPager } from '../src/pagination';

function memoryStorage(): SessionStorage {
  const values = new Map<string, string>();
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => {
      values.set(key, value);
    },
    remove: (key) => {
      values.delete(key);
    },
  };
}
function session() {
  return createSession(memoryStorage());
}
const login = {
  accessToken: 'test-token',
  expiresAt: '2099-01-01T00:00:00Z',
  user: { id: 'u1', nickname: 'DEV用户', distributor: false },
};

describe('exact money boundaries', () => {
  it.each([
    ['0.29', 29],
    ['39.90', 3990],
    ['1', 100],
    ['0', 0],
  ])('parses %s without floating point rounding', (input, expected) => {
    expect(parseYuan(input)).toBe(expected);
  });
  it.each(['-1', '1.001', '1e2', '', 'NaN', '900719925474099.99'])(
    'rejects invalid or unsafe %s',
    (input) => {
      expect(() => parseYuan(input)).toThrow();
    },
  );
  it('formats integer fen and refuses fractional fen', () => {
    expect(formatFen(3990)).toBe('39.90');
    expect(formatFen(-29)).toBe('-0.29');
    expect(() => formatFen(1.1)).toThrow();
  });
  it('validates percentage bounds and precision', () => {
    expect(parsePercent('12.50')).toBe(1250);
    expect(parsePercent('100')).toBe(10000);
    expect(() => parsePercent('100.01')).toThrow();
  });
});

describe('environment boundary', () => {
  it('never permits production mock even with an HTTPS API', () => {
    expect(() =>
      readEnvironment({
        mode: 'production',
        provider: 'mock',
        apiBaseUrl: 'https://api.example.com/api/v1',
      }),
    ).toThrow();
  });
  it('requires HTTPS for real production endpoints', () => {
    expect(() =>
      readEnvironment({
        mode: 'production',
        provider: 'http',
        apiBaseUrl: 'http://api.example.com/api/v1',
      }),
    ).toThrow();
  });
  it('accepts explicit development and rejects unknown modes/providers', () => {
    expect(
      readEnvironment({
        mode: 'development',
        provider: 'mock',
        apiBaseUrl: 'http://localhost:8080/api/v1',
      }).provider,
    ).toBe('mock');
    expect(() =>
      readEnvironment({
        mode: 'production',
        provider: 'typo',
        apiBaseUrl: 'https://api.example.com',
      }),
    ).toThrow();
  });
});

describe('session lifecycle', () => {
  it.each([
    { app: 'miniprogram' },
    { mode: 'test' as const },
    { provider: 'http' as const },
    { apiBaseUrl: 'https://other.example.com/api/v1' },
    { mode: 'production' as const, provider: 'http' as const },
  ])('isolates restored credentials when scope changes: %j', async (change) => {
    const storage = memoryStorage();
    const scope = {
      app: 'admin',
      mode: 'development' as const,
      provider: 'mock' as const,
      apiBaseUrl: 'https://api.example.com/api/v1',
    };
    createSession(storage, undefined, scope).set(login);
    // Unscoped legacy credentials must never be migrated into a configured app.
    createSession(storage).set(login);
    const isolated = createSession(storage, undefined, { ...scope, ...change });
    let authorization: string | undefined;
    const client = createClient({
      baseUrl: 'https://other.example.com/api/v1',
      session: isolated,
      transport: async (request) => {
        authorization = request.headers.Authorization;
        return { status: 200, body: { code: 'OK', message: '', requestId: 'scope', data: null } };
      },
    });
    await client.request('/me');
    expect(authorization).toBeUndefined();
    expect(isolated.get()).toBeNull();
    expect(createSession(storage, undefined, scope).get()?.user?.id).toBe('u1');
  });
  it('restores valid sessions but removes expired/corrupt storage', () => {
    const storage = memoryStorage();
    const first = createSession(storage);
    first.set(login);
    expect(createSession(storage).get()?.user?.id).toBe('u1');
    first.set({ ...login, expiresAt: '2000-01-01T00:00:00Z' });
    expect(first.get()).toBeNull();
    storage.set('jxsc.session', '{broken');
    expect(createSession(storage).get()).toBeNull();
  });
  it('consumes safe return route once and rejects external return targets', () => {
    const state = session();
    state.rememberReturnTo('/pages/order/list?tab=ALL');
    expect(state.takeReturnTo()).toBe('/pages/order/list?tab=ALL');
    expect(state.takeReturnTo()).toBeNull();
    state.rememberReturnTo('//evil.example');
    expect(state.takeReturnTo()).toBeNull();
  });
  it('invalidates private caches on logout', () => {
    let privateUrl: string | null = 'signed';
    const state = createSession(memoryStorage(), () => {
      privateUrl = null;
    });
    state.set(login);
    state.clear();
    expect(privateUrl).toBeNull();
    expect(state.get()).toBeNull();
  });
  it('notifies view stores when HTTP invalidation clears the session', () => {
    const state = session();
    let visibleUser: string | null = null;
    const unsubscribe = state.subscribe((value) => {
      visibleUser = value?.user?.id ?? null;
    });
    state.set(login);
    expect(visibleUser).toBe('u1');
    state.clear();
    expect(visibleUser).toBeNull();
    unsubscribe();
  });
});

describe('HTTP client contract', () => {
  it('redirects once when the current session expires while a request awaits 401', async () => {
    const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-07T00:00:00Z'));
    try {
      let clears = 0;
      let redirects = 0;
      const state = createSession(memoryStorage(), () => {
        clears++;
      });
      state.set({ ...login, expiresAt: '2026-10-07T00:00:01Z' });
      let finish!: (result: { status: number; body: unknown }) => void;
      const client = createClient({
        baseUrl: 'https://api.example.com',
        session: state,
        onUnauthorized: () => {
          redirects++;
        },
        transport: () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      });
      const request = client.request('/me');
      clock.mockReturnValue(Date.parse('2026-10-07T00:00:02Z'));
      finish({
        status: 401,
        body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'expiry', data: null },
      });
      await expect(request).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
      expect(state.get()).toBeNull();
      expect(clears).toBe(1);
      expect(redirects).toBe(1);
    } finally {
      clock.mockRestore();
    }
  });
  it('clears each newly accepted session even when its token is unchanged', async () => {
    const state = session();
    let redirects = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: state,
      onUnauthorized: () => {
        redirects++;
      },
      transport: async () => ({
        status: 401,
        body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'same', data: null },
      }),
    });
    for (let attempt = 0; attempt < 2; attempt++) {
      state.set(login);
      await expect(client.request('/me')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
      expect(state.get()).toBeNull();
      expect(redirects).toBe(attempt + 1);
    }
  });
  it('invalidates a same-token re-login and suppresses repeated anonymous failures', async () => {
    let clears = 0;
    const state = createSession(memoryStorage(), () => {
      clears++;
    });
    let redirects = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: state,
      onUnauthorized: () => {
        redirects++;
      },
      transport: async () => ({
        status: 401,
        body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'same', data: null },
      }),
    });
    for (let attempt = 0; attempt < 2; attempt++) {
      state.set(login);
      await Promise.allSettled([client.request('/me'), client.request('/orders')]);
      expect(state.get()).toBeNull();
      await Promise.allSettled([client.request('/me'), client.request('/orders')]);
      expect(redirects).toBe(attempt + 1);
      expect(clears).toBe(attempt + 1);
    }
  });
  it.each(['logout', 'same-token login'] as const)(
    'ignores a delayed 401 after %s',
    async (transition) => {
      const state = session();
      state.set(login);
      let finish!: (result: { status: number; body: unknown }) => void;
      let redirects = 0;
      const client = createClient({
        baseUrl: 'https://api.example.com',
        session: state,
        onUnauthorized: () => {
          redirects++;
        },
        transport: () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      });
      const request = client.request('/me');
      if (transition === 'logout') state.clear();
      else state.set(login);
      finish({
        status: 401,
        body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'old', data: null },
      });
      await expect(request).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
      expect(state.get()).toEqual(transition === 'logout' ? null : login);
      expect(redirects).toBe(0);
    },
  );
  it('sends encoded query, bearer and idempotency headers, unwraps the envelope', async () => {
    const state = session();
    state.set(login);
    const transport: Transport = async (request) => {
      expect(request.url).toBe('https://api.example.com/api/v1/products?keyword=A%26B&page=2');
      expect(request.headers.Authorization).toBe('Bearer test-token');
      expect(request.headers['Idempotency-Key']).toBe('key1');
      return {
        status: 200,
        body: { code: 'OK', message: '', requestId: 'r1', data: { id: 'p1' } },
      };
    };
    const client = createClient({
      baseUrl: 'https://api.example.com/api/v1',
      transport,
      session: state,
    });
    expect(
      await client.request('/products', {
        query: { keyword: 'A&B', page: 2, absent: undefined },
        idempotencyKey: 'key1',
      }),
    ).toEqual({ id: 'p1' });
  });
  it('clears expired auth and triggers one redirect for concurrent 401 responses', async () => {
    const state = session();
    state.set(login);
    let redirects = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: state,
      onUnauthorized: () => {
        redirects++;
      },
      transport: async () => ({
        status: 401,
        body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'r2', data: null },
      }),
    });
    await Promise.allSettled([client.request('/me'), client.request('/orders')]);
    expect(state.get()).toBeNull();
    expect(redirects).toBe(1);
  });
  it('preserves field keys and requestId with Chinese errors; never retries financial writes', async () => {
    let calls = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: session(),
      transport: async () => {
        calls++;
        return {
          status: 422,
          body: {
            code: 'VALIDATION_ERROR',
            message: 'invalid amount',
            requestId: 'r3',
            data: { fieldErrors: { amountFen: 'too high' } },
          },
        };
      },
    });
    await expect(
      client.request('/distribution/withdrawals', { method: 'POST', body: { amountFen: 10 } }),
    ).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      requestId: 'r3',
      fieldErrors: { amountFen: '填写的信息不符合要求' },
    });
    expect(calls).toBe(1);
  });
  it('rejects malformed success envelopes and external paths without leaking tokens', async () => {
    let calls = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: session(),
      transport: async () => {
        calls++;
        return { status: 200, body: '<html>gateway</html>' };
      },
    });
    await expect(client.request('/home')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    await expect(client.request('https://elsewhere.example')).rejects.toThrow();
    expect(calls).toBe(1);
  });
  it('represents network failure as retryable error without implicit resubmit', async () => {
    let calls = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: session(),
      transport: async () => {
        calls++;
        throw new Error('offline');
      },
    });
    await expect(client.request('/orders', { method: 'POST' })).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    });
    expect(calls).toBe(1);
  });
  it('a delayed 401 from the old token cannot log out a newer session', async () => {
    const state = session();
    state.set(login);
    let finish!: (result: { status: number; body: unknown }) => void;
    let redirects = 0;
    const client = createClient({
      baseUrl: 'https://api.example.com',
      session: state,
      onUnauthorized: () => {
        redirects++;
      },
      transport: () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    });
    const request = client.request('/me');
    state.set({ ...login, accessToken: 'new-token' });
    finish({
      status: 401,
      body: { code: 'UNAUTHORIZED', message: 'expired', requestId: 'old', data: null },
    });
    await expect(request).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(state.get()?.accessToken).toBe('new-token');
    expect(redirects).toBe(0);
  });
});

describe('pagination race and recovery', () => {
  it('loads the initial filter through the public load API then loads the next page', async () => {
    const pager = createPager<string, { keyword: string }>(async (filter, page) => ({
      items: [`${filter.keyword}-${page}`],
      page,
      pageSize: 1,
      total: 2,
    }));
    await pager.load({ keyword: 'initial' });
    expect(pager.state.items).toEqual(['initial-1']);
    expect(pager.state.page).toBe(1);
    await pager.loadMore();
    expect(pager.state.items).toEqual(['initial-1', 'initial-2']);
    expect(pager.state.hasMore).toBe(false);
  });
  it('old search completion cannot overwrite the latest filter', async () => {
    let finishOld!: (value: {
      items: string[];
      page: number;
      pageSize: number;
      total: number;
    }) => void;
    const pager = createPager<string, { keyword: string }>(async (filter) =>
      filter.keyword === 'old'
        ? new Promise((resolve) => {
            finishOld = resolve;
          })
        : { items: ['new'], page: 1, pageSize: 20, total: 1 },
    );
    const old = pager.reset({ keyword: 'old' });
    await pager.reset({ keyword: 'new' });
    finishOld({ items: ['old'], page: 1, pageSize: 20, total: 1 });
    await old;
    expect(pager.state.items).toEqual(['new']);
    expect(pager.state.loading).toBe(false);
  });
  it('failed next page preserves data, and retry appends once', async () => {
    let fails = true;
    const pager = createPager<string, Record<string, never>>(async (_, page) => {
      if (page === 2 && fails) throw new Error('offline');
      return { items: page === 1 ? ['a'] : ['b'], page, pageSize: 1, total: 2 };
    });
    await pager.reset({});
    await pager.loadMore();
    expect(pager.state.items).toEqual(['a']);
    expect(pager.state.error).toBeTruthy();
    fails = false;
    await Promise.all([pager.loadMore(), pager.loadMore()]);
    expect(pager.state.items).toEqual(['a', 'b']);
    expect(pager.state.hasMore).toBe(false);
  });
  it('can recover when the loader throws before returning a promise', async () => {
    let fails = true;
    const pager = createPager<string, Record<string, never>>(() => {
      if (fails) throw new Error('invalid filter');
      return Promise.resolve({ items: ['recovered'], page: 1, pageSize: 20, total: 1 });
    });
    await pager.reset({});
    fails = false;
    await pager.loadMore();
    expect(pager.state.items).toEqual(['recovered']);
  });
});
