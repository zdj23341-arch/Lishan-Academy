import type { TransportRequest } from '../http';
import type {
  DistributorSummary,
  CommissionItem,
  WithdrawalRecord,
  AdminDistributor,
  DistributorStatus,
  ProductDistributionConfig,
  AdminWithdrawalAuditView,
  ProductDistributorRule,
} from '../distribution/types';

export class DevDistributionStore {
  summary: DistributorSummary = {
    distributorId: 'DEV-distributor-1',
    status: 'ACTIVE',
    pendingFen: 2850,
    availableFen: 14800,
    withdrawingFen: 0,
    paidOutFen: 35000,
    debtFen: 0,
    withdrawalEnabled: true,
    disabledReason: null,
    limits: {
      minAmountFen: 100, // 1.00元
      maxAmountFen: 500000, // 5000元
      dailyCountLimit: 5,
      dailyCountRemaining: 5,
    },
  };

  commissions: CommissionItem[] = [
    {
      id: 'comm-1',
      orderNo: 'SO2026100700192',
      productId: 'prod-demo-book',
      goodsTitle: '初中古诗文考点精编与名师拓展训练全套',
      orderPaidAmountFen: 4900,
      rateBps: 1500, // 15%
      commissionFen: 735,
      reversedFen: 0,
      remainingFen: 735,
      status: 'AVAILABLE',
      createdAt: '2026-09-28T10:00:00Z',
      settledAt: '2026-10-05T10:00:00Z',
    },
    {
      id: 'comm-2',
      orderNo: 'SO2026100700188',
      productId: 'prod-demo-course',
      goodsTitle: '中考语文现代文阅读满分答题技巧精讲',
      orderPaidAmountFen: 9900,
      rateBps: 2000, // 20%
      commissionFen: 1980,
      reversedFen: 0,
      remainingFen: 1980,
      status: 'PENDING',
      createdAt: '2026-10-06T14:00:00Z',
      settledAt: null,
    },
  ];

  withdrawals = new Map<string, WithdrawalRecord>();
  idempotentWithdrawals = new Map<string, string>(); // idempotencyKey -> withdrawalId
  withdrawalOwners = new Map<string, string>();

  adminDistributors: AdminDistributor[] = [
    {
      id: 'dist-1',
      userId: 'user-101',
      username: 'agent_zhang',
      nickname: '张金牌顾问',
      phone: '13812345678',
      status: 'ACTIVE',
      riskBlocked: false,
      receiptReady: false,
      reason: 'DEV 初始分销员',
      createdAt: '2026-09-01T08:00:00Z',
      version: 1,
      totalCommissionFen: 42750,
      withdrawnFen: 35000,
    },
    {
      id: 'dist-2',
      userId: 'user-102',
      username: 'agent_li',
      nickname: '李名师特约',
      phone: '13987654321',
      status: 'ACTIVE',
      riskBlocked: false,
      receiptReady: false,
      reason: 'DEV 初始分销员',
      createdAt: '2026-09-15T09:00:00Z',
      version: 1,
      totalCommissionFen: 18900,
      withdrawnFen: 10000,
    },
  ];

  productRules = new Map<string, ProductDistributionConfig>();

  constructor() {
    this.productRules.set('prod-demo-book', {
      productId: 'prod-demo-book',
      version: 1,
      rules: [
        { distributorId: 'dist-1', distributorName: '张金牌顾问', rateBps: 1500, enabled: true },
      ],
    });
    this.productRules.set('prod-demo-course', {
      productId: 'prod-demo-course',
      version: 1,
      rules: [
        { distributorId: 'dist-1', distributorName: '张金牌顾问', rateBps: 2000, enabled: true },
      ],
    });
  }
}

export function createDevDistributionHandler(
  store: DevDistributionStore = new DevDistributionStore(),
) {
  let seq = 1;

  return (
    request: TransportRequest,
    path: string,
    params: { get: (k: string) => string | null },
  ): { status: number; body: unknown } | null => {
    const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
      status,
      body: { code, message, data: JSON.parse(JSON.stringify(data)), requestId: 'DEV-dist-req' },
    });

    const getBody = () => (request.body ?? {}) as Record<string, unknown>;
    const actor = request.headers.Authorization ?? 'DEV-anonymous';
    const operationKey = (key: string) => JSON.stringify([actor, key]);
    const paginate = <T>(items: T[]) => {
      const page = Math.max(1, Number(params.get('page') ?? 1));
      const pageSize = Math.min(100, Math.max(1, Number(params.get('pageSize') ?? 20)));
      return {
        items: items.slice((page - 1) * pageSize, page * pageSize),
        page,
        pageSize,
        total: items.length,
      };
    };
    if (
      path.startsWith('/operations/') &&
      request.method === 'GET' &&
      params.get('scope') === 'CREATE_WITHDRAWAL'
    ) {
      const key = decodeURIComponent(path.slice('/operations/'.length));
      const id = store.idempotentWithdrawals.get(operationKey(key));
      return reply(
        id
          ? { state: 'SUCCEEDED', resourceId: id, response: { id } }
          : { state: 'NOT_FOUND', resourceId: null, response: null },
      );
    }
    if (path === '/distribution/receipt-profile' && request.method === 'POST')
      return reply(
        null,
        503,
        'WITHDRAWAL_NOT_READY',
        'DEV演示不绑定真实微信收款身份，请在真实微信环境完成',
      );

    // 1. 生成推广码
    if (path === '/distribution/referral-codes' && request.method === 'POST') {
      const { productId } = getBody() as { productId: string };
      const code = `REF-${Math.random().toString(36).substring(2, 8).toUpperCase()}`;
      return reply({
        code,
        productId,
        miniProgramPath: `/pages/product/detail?id=${productId}&ref=${code}`,
        imageUrl: null,
        expiresAt: new Date(Date.now() + 30 * 86400000).toISOString(),
      });
    }

    // 2. 解析推广码
    if (path === '/referrals/resolve' && request.method === 'POST') {
      const { code } = getBody() as { code: string; visitorKey: string };
      return reply({
        productId: 'prod-demo-book',
        attributionToken: `att-${code}-${Date.now()}`,
        expiresAt: new Date(Date.now() + 30 * 86400000).toISOString(),
      });
    }

    // 3. 认领触点
    if (path === '/referrals/claim' && request.method === 'POST') {
      return reply({ claimed: true, productId: 'prod-demo-book' });
    }

    // 4. 分销员资产总览
    if (path === '/distribution/summary' && request.method === 'GET') {
      return reply(store.summary);
    }

    // 5. 佣金列表
    if (path === '/distribution/commissions' && request.method === 'GET') {
      const status = params.get('status');
      const filtered = status
        ? store.commissions.filter((c) => c.status === status)
        : store.commissions;
      return reply({
        items: filtered,
        page: 1,
        pageSize: 20,
        total: filtered.length,
      });
    }

    // 6. 提现申请
    if (path === '/distribution/withdrawals' && request.method === 'POST') {
      const headers = request.headers;
      const key = operationKey(headers['idempotency-key'] || headers['Idempotency-Key'] || '');
      if (key && store.idempotentWithdrawals.has(key)) {
        const existId = store.idempotentWithdrawals.get(key)!;
        const existing = store.withdrawals.get(existId)!;
        if (existing.amountFen !== Number(getBody().amountFen))
          return reply(null, 409, 'IDEMPOTENCY_CONFLICT', '原提现金额不可修改');
        return reply(existing);
      }

      const { amountFen } = getBody() as { amountFen: number };
      const id = `WD-${Date.now()}-${seq++}`;
      const record: WithdrawalRecord = {
        id,
        withdrawalNo: `WN${Date.now()}`,
        amountFen: Number(amountFen) || 0,
        status: 'PROCESSING',
        createdAt: new Date().toISOString(),
        successAt: null,
        failReason: null,
        confirmation: null,
      };

      store.withdrawals.set(id, record);
      store.withdrawalOwners.set(id, actor);
      if (key) store.idempotentWithdrawals.set(key, id);

      // 更新资产
      store.summary.availableFen -= record.amountFen;
      store.summary.withdrawingFen += record.amountFen;
      if (store.summary.limits.dailyCountRemaining !== undefined) {
        store.summary.limits.dailyCountRemaining = Math.max(
          0,
          store.summary.limits.dailyCountRemaining - 1,
        );
      }

      return reply(record);
    }

    if (path === '/distribution/withdrawals' && request.method === 'GET') {
      const items = Array.from(store.withdrawals.values())
        .filter((w) => store.withdrawalOwners.get(w.id) === actor)
        .reverse()
        .map((w) => ({ ...w, confirmation: null }));
      return reply(paginate(items));
    }
    // 7. 提现单查询与 refresh
    if (path.startsWith('/distribution/withdrawals/') && request.method === 'GET') {
      const id = path.replace('/distribution/withdrawals/', '');
      const record = store.withdrawals.get(id);
      if (!record || store.withdrawalOwners.get(id) !== actor)
        return reply(null, 404, 'NOT_FOUND', '未找到提现单');
      return reply(record);
    }

    if (
      path.startsWith('/distribution/withdrawals/') &&
      path.endsWith('/refresh') &&
      request.method === 'POST'
    ) {
      const id = path.replace('/distribution/withdrawals/', '').replace('/refresh', '');
      const record = store.withdrawals.get(id);
      if (!record || store.withdrawalOwners.get(id) !== actor)
        return reply(null, 404, 'NOT_FOUND', '未找到提现单');

      // 模拟状态流转到 SUCCEEDED
      if (record.status === 'PROCESSING') {
        record.status = 'SUCCEEDED';
        record.successAt = new Date().toISOString();
        store.summary.withdrawingFen = Math.max(0, store.summary.withdrawingFen - record.amountFen);
        store.summary.paidOutFen += record.amountFen;
      }
      return reply(record);
    }

    // B端后台接口
    if (path === '/admin/distributors') {
      if (request.method === 'GET') {
        return reply({
          items: store.adminDistributors,
          page: 1,
          pageSize: 20,
          total: store.adminDistributors.length,
        });
      }
      if (request.method === 'POST') {
        const { userId, reason } = getBody() as { userId: string; reason?: string };
        const newDist: AdminDistributor = {
          id: `dist-${Date.now()}`,
          userId,
          username: `user_${userId}`,
          nickname: '新指定顾问',
          phone: '13800000000',
          status: 'ACTIVE',
          riskBlocked: false,
          receiptReady: false,
          reason: reason ?? '运营指派',
          createdAt: new Date().toISOString(),
          version: 1,
          totalCommissionFen: 0,
          withdrawnFen: 0,
        };
        store.adminDistributors.push(newDist);
        return reply(newDist);
      }
    }

    if (path.startsWith('/admin/distributors/') && request.method === 'PATCH') {
      const id = path.replace('/admin/distributors/', '');
      const dist = store.adminDistributors.find((d) => d.id === id);
      if (!dist) return reply(null, 404, 'NOT_FOUND', '未找到分销员');
      const { status, version, riskBlocked, reason } = getBody() as {
        status?: DistributorStatus;
        riskBlocked?: boolean;
        reason?: string;
        version?: number;
      };
      if (version !== dist.version) return reply(null, 409, 'VERSION_CONFLICT', '分销员版本已变化');
      if (
        !['ACTIVE', 'DISABLED'].includes(status ?? '') ||
        typeof riskBlocked !== 'boolean' ||
        !reason?.trim()
      )
        return reply(null, 400, 'VALIDATION_ERROR', '请提供推广状态、风险状态和操作原因');
      dist.status = status!;
      dist.riskBlocked = riskBlocked;
      dist.reason = reason;
      dist.version++;
      return reply(dist);
    }

    if (path.startsWith('/admin/products/') && path.endsWith('/distributors')) {
      const prodId = path.split('/')[3]!;
      if (request.method === 'GET') {
        const config = store.productRules.get(prodId) || {
          productId: prodId,
          version: 1,
          rules: [],
        };
        return reply(config);
      }
      if (request.method === 'PUT') {
        const { rules, version } = getBody() as unknown as {
          rules: ProductDistributorRule[];
          version: number;
        };
        const config: ProductDistributionConfig = {
          productId: prodId,
          version: (version || 1) + 1,
          rules: rules || [],
        };
        store.productRules.set(prodId, config);
        return reply(config);
      }
    }

    if (path === '/admin/withdrawals' && request.method === 'GET') {
      const auditList: AdminWithdrawalAuditView[] = Array.from(store.withdrawals.values()).map(
        (w) => ({
          ...w,
          distributorId: 'dist-1',
          distributorName: '张金牌顾问',
          phone: '138****5678',
          outBillNo: w.withdrawalNo,
          channel: 'DEV',
          providerState: w.status,
          errorCode: null,
          nextQueryAt: null,
          alerted: false,
          reconciliation: {
            status: 'MATCHED',
            observedPaidOutFen: w.status === 'SUCCEEDED' ? w.amountFen : 0,
            accountedPaidOutFen: w.status === 'SUCCEEDED' ? w.amountFen : 0,
            lastQueryState: w.status,
          },
        }),
      );
      return reply({
        items: auditList,
        page: 1,
        pageSize: 20,
        total: auditList.length,
      });
    }

    if (
      path.startsWith('/admin/withdrawals/') &&
      path.endsWith('/refresh') &&
      request.method === 'POST'
    ) {
      const id = path.split('/')[3]!;
      const w = store.withdrawals.get(id);
      if (!w) return reply(null, 404, 'NOT_FOUND', '提现单不存在');
      return reply({
        ...w,
        distributorId: 'dist-1',
        distributorName: '张金牌顾问',
        phone: '138****5678',
        channelTransferNo: `WXTR${w.id}`,
      });
    }

    return null;
  };
}
