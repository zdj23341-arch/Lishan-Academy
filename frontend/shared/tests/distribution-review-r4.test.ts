import { describe, expect, it, vi } from 'vitest';
import { WithdrawalController } from '../src/distribution/withdrawal';
import type { DistributionApi } from '../src/distribution/api';
import type { WithdrawalRecord, WithdrawalStatus } from '../src/distribution/types';
import { ApiError, type TransportRequest } from '../src/http';
import { createSession } from '../src/session';
import { DevDistributionStore, createDevDistributionHandler } from '../src/dev/distribution';

const record = (
  id: string,
  status: WithdrawalStatus = 'USER_ACTION_REQUIRED',
): WithdrawalRecord => ({
  id,
  status,
  amountFen: 2000,
  withdrawalNo: id,
  createdAt: '2026-10-08T00:00:00Z',
  confirmation: { packageInfo: `package-${id}` },
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
function persistence() {
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
  session.set({
    accessToken: 'A',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'A', nickname: 'A', distributor: true },
  });
  return { storage, session, namespace: 'review-real-api' };
}

describe('R4 distribution review regressions', () => {
  it.each([
    ['INSUFFICIENT_BALANCE', 409],
    ['VALIDATION_ERROR', 400],
    ['WITHDRAWAL_LIMIT', 409],
  ])(
    'releases a transactional %s rejection so a smaller new request and restart are possible',
    async (code, status) => {
      const options = persistence();
      const api = {
        requestWithdrawal: vi
          .fn()
          .mockRejectedValueOnce(new ApiError(code, '拒绝', null, {}, status))
          .mockResolvedValue(record('new', 'SUCCEEDED')),
      };
      const ctrl = new WithdrawalController(api as unknown as DistributionApi, undefined, options);
      await expect(ctrl.submitWithdrawal(2000, 'rejected')).rejects.toThrow('拒绝');
      expect(ctrl.pendingKey).toBeNull();
      ctrl.dispose();
      const restored = new WithdrawalController(
        api as unknown as DistributionApi,
        undefined,
        options,
      );
      expect(restored.pendingKey).toBeNull();
      api.requestWithdrawal.mockResolvedValue({ ...record('new', 'SUCCEEDED'), amountFen: 1000 });
      expect((await restored.submitWithdrawal(1000, 'new-key')).id).toBe('new');
      restored.dispose();
    },
  );

  it.each([
    new ApiError('NETWORK_ERROR', 'timeout'),
    new ApiError('IDEMPOTENCY_CONFLICT', 'conflict', null, {}, 409),
    new ApiError('IN_PROGRESS', 'busy', null, {}, 409),
    new ApiError('INSUFFICIENT_BALANCE', 'unknown server failure', null, {}, 500),
    new ApiError('UNKNOWN', 'unknown', null, {}, 409),
  ])('retains immutable intent for uncertain rejection $code/$status', async (error) => {
    const options = persistence();
    const ctrl = new WithdrawalController(
      { requestWithdrawal: vi.fn().mockRejectedValue(error) } as unknown as DistributionApi,
      undefined,
      options,
    );
    await expect(ctrl.submitWithdrawal(2000, 'original')).rejects.toThrow();
    expect(ctrl.pendingKey).toBe('original');
    expect(ctrl.pendingAmountFen).toBe(2000);
    ctrl.dispose();
  });

  it('does not let late selection A overwrite newer selection B', async () => {
    const a = deferred<WithdrawalRecord>();
    const ctrl = new WithdrawalController({
      getWithdrawal: (id: string) => (id === 'A' ? a.promise : Promise.resolve(record('B'))),
    } as unknown as DistributionApi);
    const first = ctrl.selectRecord('A').catch(() => null);
    await ctrl.selectRecord('B');
    a.resolve(record('A'));
    await first;
    expect(ctrl.currentRecord?.id).toBe('B');
    ctrl.dispose();
  });

  it('does not let an old poll regress a newer terminal read or restart polling', async () => {
    const old = deferred<WithdrawalRecord>();
    const ctrl = new WithdrawalController({
      refreshWithdrawal: () => old.promise,
      getWithdrawal: async () => record('A', 'SUCCEEDED'),
    } as unknown as DistributionApi);
    ctrl.currentRecord = record('A', 'PROCESSING');
    const pending = ctrl.refreshCurrent();
    await ctrl.selectRecord('A');
    old.resolve(record('A', 'PROCESSING'));
    await pending;
    expect(ctrl.currentRecord?.status).toBe('SUCCEEDED');
    expect(ctrl.polling).toBe(false);
    expect(ctrl.loading).toBe(false);
    ctrl.dispose();
  });

  it('keeps old polling from superseding a user selection while its detail is pending', async () => {
    vi.useFakeTimers();
    const b = deferred<WithdrawalRecord>();
    const api = {
      getWithdrawal: () => b.promise,
      refreshWithdrawal: vi.fn(async () => record('A', 'PROCESSING')),
    };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi);
    try {
      ctrl.currentRecord = record('A', 'PROCESSING');
      ctrl.startPolling();
      const selected = ctrl.selectRecord('B').catch(() => null);
      await vi.advanceTimersByTimeAsync(3000);
      b.resolve(record('B'));
      await selected;
      expect(ctrl.currentRecord?.id).toBe('B');
      expect(api.refreshWithdrawal).not.toHaveBeenCalled();
    } finally {
      ctrl.dispose();
      vi.useRealTimers();
    }
  });

  it('continues normal polling through processing to an authoritative terminal state', async () => {
    vi.useFakeTimers();
    const api = {
      getWithdrawal: vi.fn().mockResolvedValue(record('A', 'REQUESTED')),
      refreshWithdrawal: vi
        .fn()
        .mockResolvedValueOnce(record('A', 'PROCESSING'))
        .mockResolvedValueOnce(record('A', 'SUCCEEDED')),
    };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi);
    try {
      await ctrl.selectRecord('A');
      await vi.advanceTimersByTimeAsync(3000);
      expect(ctrl.currentRecord?.status).toBe('PROCESSING');
      expect(ctrl.polling).toBe(true);
      await vi.advanceTimersByTimeAsync(3000);
      expect(ctrl.currentRecord?.status).toBe('SUCCEEDED');
      expect(ctrl.polling).toBe(false);
      expect(api.refreshWithdrawal).toHaveBeenCalledTimes(2);
    } finally {
      ctrl.dispose();
      vi.useRealTimers();
    }
  });

  it('does not duplicate the new selection poll loop when an older active poll finishes', async () => {
    vi.useFakeTimers();
    const a = deferred<WithdrawalRecord>();
    const api = {
      getWithdrawal: vi.fn(async (id: string) => record(id, 'PROCESSING')),
      refreshWithdrawal: vi.fn((id: string) =>
        id === 'A' ? a.promise : Promise.resolve(record('B', 'PROCESSING')),
      ),
    };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi);
    try {
      await ctrl.selectRecord('A');
      await vi.advanceTimersByTimeAsync(3000);
      await ctrl.selectRecord('B');
      a.resolve(record('A', 'PROCESSING'));
      await vi.advanceTimersByTimeAsync(3000);
      expect(ctrl.currentRecord?.id).toBe('B');
      expect(api.refreshWithdrawal.mock.calls).toEqual([['A'], ['B']]);
    } finally {
      ctrl.dispose();
      vi.useRealTimers();
    }
  });

  it('confirms the explicitly requested record and never refreshes a new selection after the adapter resolves', async () => {
    const confirmation = deferred<boolean>();
    const api = {
      getWithdrawal: vi.fn(async (id: string) => record(id)),
      refreshWithdrawal: vi.fn(async (id: string) => record(id, 'SUCCEEDED')),
    };
    const adapter = { confirmReceipt: vi.fn(() => confirmation.promise) };
    const ctrl = new WithdrawalController(api as unknown as DistributionApi, adapter);
    ctrl.currentRecord = record('B');
    const pending = ctrl.invokeReceiptConfirmation('A');
    await vi.waitFor(() =>
      expect(adapter.confirmReceipt).toHaveBeenCalledWith({ packageInfo: 'package-A' }),
    );
    await ctrl.selectRecord('B');
    confirmation.resolve(true);
    await pending;
    expect(ctrl.currentRecord?.id).toBe('B');
    expect(api.refreshWithdrawal).not.toHaveBeenCalled();
    ctrl.dispose();
  });

  it('invalidates in-flight reads when the page is hidden', async () => {
    const old = deferred<WithdrawalRecord>();
    const ctrl = new WithdrawalController({
      refreshWithdrawal: () => old.promise,
    } as unknown as DistributionApi);
    ctrl.currentRecord = record('A', 'PROCESSING');
    const pending = ctrl.refreshCurrent();
    ctrl.pause();
    old.resolve(record('A', 'PROCESSING'));
    await pending;
    expect(ctrl.polling).toBe(false);
    expect(ctrl.loading).toBe(false);
    ctrl.dispose();
  });

  it('persists DEV risk freeze independently of promotion status, validates version, and unfreezes', () => {
    const handler = createDevDistributionHandler(new DevDistributionStore());
    const params = { get: () => null };
    const request: TransportRequest = {
      url: '',
      method: 'PATCH',
      headers: { Authorization: 'Bearer operator' },
      body: { version: 1, status: 'ACTIVE', riskBlocked: true, reason: 'risk review' },
    };
    const frozen = handler(request, '/admin/distributors/dist-1', params)!;
    expect(frozen.status).toBe(200);
    expect(frozen.body).toMatchObject({
      data: { status: 'ACTIVE', riskBlocked: true, reason: 'risk review', version: 2 },
    });
    const list = handler({ ...request, method: 'GET' }, '/admin/distributors', params)!;
    expect(list.body).toMatchObject({
      data: { items: [expect.objectContaining({ riskBlocked: true }), expect.anything()] },
    });
    expect(handler(request, '/admin/distributors/dist-1', params)?.status).toBe(409);
    expect(
      handler(
        {
          ...request,
          body: { version: 2, status: 'ACTIVE', riskBlocked: false, reason: 'cleared' },
        },
        '/admin/distributors/dist-1',
        params,
      )?.body,
    ).toMatchObject({
      data: { status: 'ACTIVE', riskBlocked: false, reason: 'cleared', version: 3 },
    });
  });
});
