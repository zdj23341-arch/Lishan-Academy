import { describe, expect, it, vi } from 'vitest';
import { WithdrawalController } from '../src/distribution/withdrawal';
import { ReferralManager } from '../src/distribution/referral';
import type { DistributionApi } from '../src/distribution/api';
import type { ApiClient, TransportRequest } from '../src/http';
import type { WithdrawalRecord, WithdrawalStatus } from '../src/distribution/types';
import { createDistributionApi } from '../src/distribution/api';
import { createAdminDistributionApi } from '../src/distribution/admin-api';
import { createSession } from '../src/session';
import { DevDistributionStore, createDevDistributionHandler } from '../src/dev/distribution';

function fixture() {
  const values = new Map<string, string>();
  const storage = {
    get: (key: string) => values.get(key) ?? null,
    set: (key: string, value: string) => {
      values.set(key, value);
    },
    remove: (key: string) => {
      values.delete(key);
    },
  };
  const session = createSession(storage);
  const login = (id: string) =>
    session.set({
      accessToken: id,
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, nickname: id, distributor: true },
    });
  login('A');
  return { storage, session, login, namespace: 'test-real-api' };
}
const record = (id = '200', amountFen = 2000, status: WithdrawalStatus = 'SUCCEEDED') => ({
  id,
  amountFen,
  status,
  withdrawalNo: id,
  createdAt: '2026-10-08T00:00:00Z',
});

describe('R4 withdrawal recovery', () => {
  it('DEV supports matching operation recovery, immutable amount and paginated history without list confirmation credentials', () => {
    const handler = createDevDistributionHandler(new DevDistributionStore());
    const params = (values: Record<string, string>) => ({
      get: (key: string) => values[key] ?? null,
    });
    const request: TransportRequest = {
      method: 'POST',
      body: { amountFen: 2000 },
      headers: { Authorization: 'Bearer A', 'Idempotency-Key': 'key' },
      url: '/api/v1/distribution/withdrawals',
    };
    const created = handler(request, '/distribution/withdrawals', params({}))!.body as {
      data: {
        id: string;
        state: string;
        resourceId: string;
        items: WithdrawalRecord[];
        page: number;
        pageSize: number;
        total: number;
      };
    };
    const lookup = handler(
      { ...request, method: 'GET' } as TransportRequest,
      '/operations/key',
      params({ scope: 'CREATE_WITHDRAWAL' }),
    )!.body as {
      data: {
        id: string;
        state: string;
        resourceId: string;
        items: WithdrawalRecord[];
        page: number;
        pageSize: number;
        total: number;
      };
    };
    expect(lookup.data).toMatchObject({ state: 'SUCCEEDED', resourceId: created.data.id });
    const conflict = handler(
      { ...request, body: { amountFen: 100 } } as TransportRequest,
      '/distribution/withdrawals',
      params({}),
    )!;
    expect(conflict.status).toBe(409);
    const history = handler(
      { ...request, method: 'GET' } as TransportRequest,
      '/distribution/withdrawals',
      params({ page: '2', pageSize: '1' }),
    )!.body as {
      data: {
        id: string;
        state: string;
        resourceId: string;
        items: WithdrawalRecord[];
        page: number;
        pageSize: number;
        total: number;
      };
    };
    expect(history.data).toMatchObject({ items: [], page: 2, pageSize: 1, total: 1 });
  });
  it('persists the immutable original amount/key across controller recreation and reads only its operation resource', async () => {
    const options = fixture();
    const api = {
      requestWithdrawal: vi.fn().mockRejectedValue(new Error('timeout')),
      queryWithdrawalOperation: vi
        .fn()
        .mockResolvedValue({ state: 'SUCCEEDED', resourceId: '200' }),
      getWithdrawal: vi.fn().mockResolvedValue(record()),
      listWithdrawals: vi.fn().mockResolvedValue({ items: [record('old', 100)] }),
    };
    const first = new WithdrawalController(api as unknown as DistributionApi, undefined, options);
    await expect(first.submitWithdrawal(2000, 'original-key')).rejects.toThrow('timeout');
    first.dispose();
    const restored = new WithdrawalController(
      api as unknown as DistributionApi,
      undefined,
      options,
    );
    expect(restored.pendingKey).toBe('original-key');
    expect(restored.pendingAmountFen).toBe(2000);
    await expect(restored.submitWithdrawal(100, 'new-key')).rejects.toThrow('原提现');
    expect((await restored.recoverPending())?.id).toBe('200');
    expect(api.queryWithdrawalOperation).toHaveBeenCalledWith('original-key');
    expect(api.listWithdrawals).not.toHaveBeenCalled();
    expect(restored.pendingKey).toBeNull();
    restored.dispose();
  });

  it('keeps UNKNOWN and NOT_FOUND original intent and never selects historical records', async () => {
    const options = fixture();
    const api = {
      requestWithdrawal: vi.fn().mockRejectedValue(new Error('timeout')),
      queryWithdrawalOperation: vi.fn().mockResolvedValue({ state: 'NOT_FOUND' }),
      listWithdrawals: vi.fn().mockResolvedValue({ items: [record('old', 100)] }),
    };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi, undefined, options);
    await expect(ctrl.submitWithdrawal(2000, 'key')).rejects.toThrow();
    expect(await ctrl.recoverPending()).toBeNull();
    expect(ctrl.pendingKey).toBe('key');
    expect(ctrl.currentRecord).toBeNull();
    expect(api.requestWithdrawal).toHaveBeenCalledTimes(1);
    expect(api.listWithdrawals).not.toHaveBeenCalled();
    ctrl.dispose();
  });

  it('explicitly retries only the durable original amount and key when the initial request never reached the server', async () => {
    const options = fixture();
    const requestWithdrawal = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(record('200', 2000, 'USER_ACTION_REQUIRED'));
    const ctrl = new WithdrawalController(
      { requestWithdrawal } as unknown as DistributionApi,
      undefined,
      options,
    );
    await expect(ctrl.submitWithdrawal(2000, 'original')).rejects.toThrow('offline');
    expect((await ctrl.retryPending())?.id).toBe('200');
    expect(requestWithdrawal.mock.calls).toEqual([
      [2000, 'original'],
      [2000, 'original'],
    ]);
    expect(ctrl.pendingKey).toBe('original');
    ctrl.dispose();
  });

  it('isolates actor/environment and ignores a response after an account switch', async () => {
    const options = fixture();
    let resolve!: (value: WithdrawalRecord) => void;
    const api = {
      requestWithdrawal: () =>
        new Promise<WithdrawalRecord>((r) => {
          resolve = r;
        }),
    };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi, undefined, options);
    const pending = ctrl.submitWithdrawal(2000, 'key');
    options.login('B');
    resolve(record());
    await expect(pending).rejects.toThrow();
    expect(ctrl.currentRecord).toBeNull();
    expect(ctrl.pendingKey).toBeNull();
    options.login('A');
    expect(ctrl.pendingKey).toBe('key');
    const otherEnv = new WithdrawalController(api as unknown as DistributionApi, undefined, {
      ...options,
      namespace: 'other-api',
    });
    expect(otherEnv.pendingKey).toBeNull();
    ctrl.dispose();
    otherEnv.dispose();
  });

  it('fetches owner detail for a list record before invoking official confirmation and refreshes authoritative state', async () => {
    const confirmation = { packageInfo: 'owner-package', appId: 'wx-app', merchantId: 'merchant' };
    const api = {
      getWithdrawal: vi
        .fn()
        .mockResolvedValue({ ...record('200', 2000, 'USER_ACTION_REQUIRED'), confirmation }),
      refreshWithdrawal: vi.fn().mockResolvedValue(record()),
    };
    const adapter = { confirmReceipt: vi.fn().mockResolvedValue(true) };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi, adapter);
    ctrl.currentRecord = record('200', 2000, 'USER_ACTION_REQUIRED');
    expect(await ctrl.invokeReceiptConfirmation()).toBe(true);
    expect(api.getWithdrawal).toHaveBeenCalledWith('200');
    expect(adapter.confirmReceipt).toHaveBeenCalledWith(confirmation);
    expect(ctrl.currentRecord?.status).toBe('SUCCEEDED');
    ctrl.dispose();
  });

  it('uses the real operation scope and explicit own receipt-profile POST', async () => {
    const request = vi.fn().mockResolvedValue({ ready: true, nameProvided: true });
    const api = createDistributionApi({ request } as unknown as ApiClient);
    await api.queryWithdrawalOperation('original');
    expect(request).toHaveBeenCalledWith('/operations/original', {
      method: 'GET',
      query: { scope: 'CREATE_WITHDRAWAL' },
    });
    await api.bindReceiptProfile(' 李明 ');
    expect(request).toHaveBeenCalledWith('/distribution/receipt-profile', {
      method: 'POST',
      body: { realName: '李明' },
    });
  });

  it('normalizes the real claim TouchView response instead of expecting a nonexistent claimed flag', async () => {
    const api = createDistributionApi({
      request: async () => ({
        productId: 'product',
        attributionToken: 'token',
        expiresAt: '2099-01-01T00:00:00Z',
      }),
    } as unknown as ApiClient);
    expect(await api.claimReferral('token')).toMatchObject({ claimed: true, productId: 'product' });
  });

  it('binds the real rules response without productId to the requested product and preserves scheduled rule fields', async () => {
    const rule = {
      distributorId: '9',
      rateBps: 1500,
      enabled: true,
      effectiveAt: '2026-10-09T00:00:00Z',
      expiresAt: '2027-01-01T00:00:00Z',
      ruleVersion: 3,
    };
    const request = vi.fn().mockResolvedValue({ version: 7, rules: [rule] });
    const api = createAdminDistributionApi({ request } as unknown as ApiClient);
    const loaded = await api.getProductRules('123');
    expect(loaded).toMatchObject({ productId: '123', version: 7, rules: [rule] });
    const saved = await api.updateProductRules('123', loaded.rules, loaded.version, '更新比例');
    expect(saved.productId).toBe('123');
    expect(request).toHaveBeenLastCalledWith(
      '/admin/products/123/distributors',
      expect.objectContaining({ body: { rules: [rule], version: 7, reason: '更新比例' } }),
    );
  });
});

describe('R4 referral ownership', () => {
  it('claims a guest token before purchase and discards old-account tokens on logout', async () => {
    const options = fixture();
    options.session.clear();
    const manager = new ReferralManager();
    manager.bind(options);
    manager.saveAttribution('product', 'guest-token', '2099-01-01T00:00:00Z');
    options.login('A');
    expect(manager.getValidTokenForProduct('product')).toBeNull();
    await manager.claimPending(async () => ({ claimed: true, productId: 'product' }));
    expect(manager.getValidTokenForProduct('product')).toBe('guest-token');
    options.session.clear();
    options.login('B');
    expect(manager.getPendingAttribution()).toBeNull();
    manager.dispose();
  });

  it('keeps failed claims out of checkout with explicit retry or discard', async () => {
    const options = fixture();
    options.session.clear();
    const manager = new ReferralManager();
    manager.bind(options);
    manager.saveAttribution('product', 'token', '2099-01-01T00:00:00Z');
    options.login('A');
    await expect(
      manager.claimPending(async () => {
        throw new Error('network');
      }),
    ).rejects.toThrow('network');
    expect(manager.getValidTokenForProduct('product')).toBeNull();
    expect(manager.getPendingAttribution()?.attributionToken).toBe('token');
    manager.clear();
    expect(manager.getPendingAttribution()).toBeNull();
    manager.dispose();
  });
});
