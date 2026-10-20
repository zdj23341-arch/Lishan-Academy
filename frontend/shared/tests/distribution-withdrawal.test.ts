import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import {
  validateWithdrawalAmount,
  validateDistributionRules,
  ReferralManager,
  WithdrawalController,
  createDistributionApi,
  createAdminDistributionApi,
  createAdminOperationsApi,
  createClient,
  createSession,
  type DistributorSummary,
  type ProductDistributorRule,
  type ReceiptConfirmationAdapter,
} from '../src';
import type { DistributionApi } from '../src/distribution/api';
import { devTransport } from '../src/dev/transport';

describe('Distribution Validation', () => {
  const baseSummary: DistributorSummary = {
    distributorId: 'dist-test-1',
    status: 'ACTIVE',
    pendingFen: 1000,
    availableFen: 5000,
    withdrawingFen: 0,
    paidOutFen: 20000,
    debtFen: 0,
    withdrawalEnabled: true,
    disabledReason: null,
    limits: {
      minAmountFen: 100, // 1.00元
      maxAmountFen: 100000, // 1000.00元
      dailyCountLimit: 3,
      dailyCountRemaining: 3,
    },
  };

  it('validates normal withdrawal amounts successfully', () => {
    const res = validateWithdrawalAmount('25.50', baseSummary);
    expect(res.valid).toBe(true);
    expect(res.amountFen).toBe(2550);
    expect(res.error).toBeUndefined();
  });

  it('rejects invalid number format or non-numeric strings', () => {
    const res1 = validateWithdrawalAmount('abc', baseSummary);
    expect(res1.valid).toBe(false);
    expect(res1.error).toBeDefined();

    const res2 = validateWithdrawalAmount('10.999', baseSummary);
    expect(res2.valid).toBe(false);
  });

  it('rejects zero or negative amounts', () => {
    const res1 = validateWithdrawalAmount('0', baseSummary);
    expect(res1.valid).toBe(false);
    expect(res1.error).toContain('大于 0');

    const res2 = validateWithdrawalAmount('-10', baseSummary);
    expect(res2.valid).toBe(false);
  });

  it('rejects amounts below min limit', () => {
    const res = validateWithdrawalAmount('0.5', baseSummary);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('最低提现金额');
  });

  it('rejects amounts above max limit', () => {
    const res = validateWithdrawalAmount('1500', baseSummary);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('最高提现金额');
  });

  it('rejects amounts exceeding available balance', () => {
    const res = validateWithdrawalAmount('60', baseSummary);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('超出可用余额');
  });

  it('rejects when account has debt', () => {
    const summaryWithDebt: DistributorSummary = {
      ...baseSummary,
      debtFen: 1500,
    };
    const res = validateWithdrawalAmount('20', summaryWithDebt);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('欠款');
  });

  it('rejects when account is disabled or blocked', () => {
    const disabledSummary: DistributorSummary = {
      ...baseSummary,
      status: 'DISABLED',
    };
    expect(validateWithdrawalAmount('20', disabledSummary).valid).toBe(false);

    const blockedSummary: DistributorSummary = {
      ...baseSummary,
      status: 'RISK_BLOCKED',
    };
    expect(validateWithdrawalAmount('20', blockedSummary).error).toContain('风险冻结');
  });

  it('rejects when withdrawal is disabled by merchant or platform', () => {
    const disabledWithdrawal: DistributorSummary = {
      ...baseSummary,
      withdrawalEnabled: false,
      disabledReason: '商家转账功能维护中',
    };
    const res = validateWithdrawalAmount('20', disabledWithdrawal);
    expect(res.valid).toBe(false);
    expect(res.error).toBe('商家转账功能维护中');
  });

  it('rejects when daily withdrawal count limit reached', () => {
    const limitReachedSummary: DistributorSummary = {
      ...baseSummary,
      limits: {
        ...baseSummary.limits,
        dailyCountRemaining: 0,
      },
    };
    const res = validateWithdrawalAmount('20', limitReachedSummary);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('今日提现次数已达上限');
  });

  it('validates distribution rules correctly', () => {
    const validRules: ProductDistributorRule[] = [
      { distributorId: 'd1', distributorName: 'A', rateBps: 1500, enabled: true },
      { distributorId: 'd2', distributorName: 'B', rateBps: 2000, enabled: true },
    ];
    expect(validateDistributionRules(validRules)).toBeNull();

    const duplicateRules: ProductDistributorRule[] = [
      { distributorId: 'd1', distributorName: 'A', rateBps: 1500, enabled: true },
      { distributorId: 'd1', distributorName: 'A2', rateBps: 1000, enabled: true },
    ];
    expect(validateDistributionRules(duplicateRules)).toContain('重复配置');

    const invalidRateRules: ProductDistributorRule[] = [
      { distributorId: 'd1', distributorName: 'A', rateBps: 12000, enabled: true },
    ];
    expect(validateDistributionRules(invalidRateRules)).toContain('0% ~ 100%');
  });
});

describe('ReferralManager', () => {
  let referral: ReferralManager;

  beforeEach(() => {
    referral = new ReferralManager();
    referral.clear();
  });

  afterEach(() => {
    referral.clear();
  });

  it('saves and retrieves attribution token for same product', () => {
    const expiresAt = new Date(Date.now() + 86400000).toISOString();
    referral.saveAttribution('prod-1', 'token-abc-123', expiresAt);

    const token = referral.getValidTokenForProduct('prod-1');
    expect(token).toBe('token-abc-123');
  });

  it('isolates different products and returns null for unmatching product', () => {
    const expiresAt = new Date(Date.now() + 86400000).toISOString();
    referral.saveAttribution('prod-1', 'token-abc-123', expiresAt);

    const token = referral.getValidTokenForProduct('prod-2');
    expect(token).toBeNull();
  });

  it('expires attribution token after expiresAt has passed', () => {
    const past = new Date(Date.now() - 1000).toISOString();
    referral.saveAttribution('prod-1', 'token-expired', past);

    const token = referral.getValidTokenForProduct('prod-1');
    expect(token).toBeNull();
  });

  it('clears attribution storage on demand', () => {
    const future = new Date(Date.now() + 86400000).toISOString();
    referral.saveAttribution('prod-1', 'token-abc', future);
    referral.clear();

    expect(referral.getValidTokenForProduct('prod-1')).toBeNull();
  });
});

describe('WithdrawalController', () => {
  it('submits withdrawal and tracks current record', async () => {
    const mockApi = {
      requestWithdrawal: vi.fn().mockResolvedValue({
        id: 'w-1',
        withdrawalNo: 'W202610070001',
        amountFen: 5000,
        status: 'PROCESSING' as const,
        createdAt: '2026-10-07T12:00:00Z',
        succeededAt: null,
        failedReason: null,
      }),
      refreshWithdrawal: vi.fn(),
      getSummary: vi.fn(),
      getCommissions: vi.fn(),
      getWithdrawals: vi.fn(),
      createReferralCode: vi.fn(),
      resolveReferral: vi.fn(),
      claimReferral: vi.fn(),
    };

    const ctrl = new WithdrawalController(mockApi as unknown as DistributionApi);
    const res = await ctrl.submitWithdrawal(5000, 'idemp-1');

    expect(mockApi.requestWithdrawal).toHaveBeenCalledWith(5000, 'idemp-1');
    expect(res.id).toBe('w-1');
    expect(ctrl.currentRecord?.status).toBe('PROCESSING');
    expect(ctrl.polling).toBe(true);

    ctrl.stopPolling();
  });

  it('invokes receipt confirmation adapter when confirmation payload exists', async () => {
    const mockAdapter: ReceiptConfirmationAdapter = {
      confirmReceipt: vi.fn().mockResolvedValue(true),
    };

    const mockApi = {
      requestWithdrawal: vi.fn(),
      refreshWithdrawal: vi.fn().mockResolvedValue({
        id: 'w-2',
        withdrawalNo: 'W202610070002',
        amountFen: 3000,
        status: 'SUCCEEDED' as const,
        createdAt: '2026-10-07T12:00:00Z',
        succeededAt: '2026-10-07T12:05:00Z',
        failedReason: null,
      }),
      getSummary: vi.fn(),
      getCommissions: vi.fn(),
      getWithdrawals: vi.fn(),
      createReferralCode: vi.fn(),
      resolveReferral: vi.fn(),
      claimReferral: vi.fn(),
    };

    const ctrl = new WithdrawalController(mockApi as unknown as DistributionApi, mockAdapter);
    ctrl.currentRecord = {
      id: 'w-2',
      withdrawalNo: 'W202610070002',
      amountFen: 3000,
      status: 'USER_ACTION_REQUIRED',
      createdAt: '2026-10-07T12:00:00Z',
      succeededAt: null,
      failedReason: null,
      confirmation: {
        packageInfo: 'confirm_pkg_123',
        miniprogramAppId: 'wx12345678',
      },
    };

    // The real list omits credentials; owner detail is authoritative before invocation.
    Object.assign(mockApi, { getWithdrawal: vi.fn().mockResolvedValue(ctrl.currentRecord) });

    const result = await ctrl.invokeReceiptConfirmation();
    expect(mockAdapter.confirmReceipt).toHaveBeenCalledWith({
      packageInfo: 'confirm_pkg_123',
      miniprogramAppId: 'wx12345678',
    });
    expect(result).toBe(true);
    expect(ctrl.currentRecord?.status).toBe('SUCCEEDED');
  });
});

describe('Dev Transport Distribution & Operations Endpoints', () => {
  const session = (() => {
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
    const s = createSession(storage);
    s.set({
      accessToken: 'test-dev-token',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'u1', nickname: 'DEV分销员', distributor: true },
    });
    return s;
  })();

  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: devTransport,
  });

  const distApi = createDistributionApi(client);
  const adminDistApi = createAdminDistributionApi(client);
  const adminOpsApi = createAdminOperationsApi(client);

  it('fetches distributor summary and commissions from DEV transport', async () => {
    const summary = await distApi.getSummary();
    expect(summary.status).toBe('ACTIVE');
    expect(summary.availableFen).toBe(14800);
    expect(summary.withdrawalEnabled).toBe(true);
    expect(summary.limits.dailyCountRemaining).toBe(5);

    const comms = await distApi.getCommissions({ page: 1, pageSize: 10 });
    expect(comms.items.length).toBeGreaterThan(0);
    expect(comms.items[0]!.commissionFen).toBe(735);
  });

  it('creates and resolves referral code', async () => {
    const codeRes = await distApi.createReferralCode('prod-demo-book');
    expect(codeRes.code).toBeDefined();
    expect(codeRes.miniProgramPath).toContain('prod-demo-book');

    const resolveRes = await distApi.resolveReferral(codeRes.code, 'visitor-guest-1');
    expect(resolveRes.productId).toBe('prod-demo-book');
    expect(resolveRes.attributionToken).toBeDefined();
    expect(resolveRes.expiresAt).toBeDefined();
  });

  it('submits withdrawal and queries status with idempotency in DEV transport', async () => {
    const key = 'test-idemp-key-1';
    const record1 = await distApi.requestWithdrawal(2000, key);
    expect(record1.amountFen).toBe(2000);
    expect(record1.status).toBe('PROCESSING');

    // 同一幂等键重新提交，应返回同一笔提现
    const record2 = await distApi.requestWithdrawal(2000, key);
    expect(record2.id).toBe(record1.id);
    expect(record2.withdrawalNo).toBe(record1.withdrawalNo);

    // 刷新提现单状态
    const refreshed = await distApi.refreshWithdrawal(record1.id);
    expect(refreshed.id).toBe(record1.id);
  });

  it('manages admin distributor list and product commission rules', async () => {
    const list = await adminDistApi.listDistributors({ page: 1, pageSize: 10 });
    expect(list.items.length).toBeGreaterThanOrEqual(2);

    const first = list.items[0]!;
    const updated = await adminDistApi.updateDistributorStatus(
      first.id,
      'DISABLED',
      '违规推广',
      first.version,
    );
    expect(updated.status).toBe('DISABLED');

    // 商品分销规则
    const config = await adminDistApi.getProductDistributionConfig('prod-demo-book');
    expect(config.rules.length).toBe(1);

    const newConfig = await adminDistApi.saveProductDistributionConfig(
      'prod-demo-book',
      [
        { distributorId: 'dist-1', distributorName: '张金牌顾问', rateBps: 1800, enabled: true },
        { distributorId: 'dist-2', distributorName: '李名师特约', rateBps: 2200, enabled: true },
      ],
      config.version,
    );
    expect(newConfig.rules.length).toBe(2);
    expect(newConfig.rules[0]!.rateBps).toBe(1800);
  });

  it('fetches analytics overview, trend and exports in DEV transport', async () => {
    const overview = await adminOpsApi.getOverview({
      startDate: '2026-10-01',
      endDate: '2026-10-07',
      includeDevChannel: true,
    });
    expect(overview.periodMetrics.orderCreatedCount).toBe(3);
    expect(overview.periodMetrics.netCashAmountFen).toBe(8000);
    expect(overview.periodMetrics.commissionEarnedFen).toBe(1000);

    const trend = await adminOpsApi.getTrend({ startDate: '2026-10-01', endDate: '2026-10-07' });
    expect(trend.items.length).toBe(7);
    expect(trend.items[0]!.receivedAmountFen).toBe(0);

    // 导出任务
    const exportTask = await adminOpsApi.createExport({
      exportType: 'COMMISSIONS',
      filters: { startDate: '2026-10-01', endDate: '2026-10-07' },
    });
    expect(exportTask.id).toBeDefined();
    expect(exportTask.downloadUrl).toBeDefined();
  });
});
