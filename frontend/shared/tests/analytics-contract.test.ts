import { describe, expect, it, vi } from 'vitest';
import { createClient, createAdminOperationsApi, createSession, type Transport } from '../src';
import { createDevOperationsHandler } from '../src/dev/operations';

const period = { startDate: '2026-10-06', endDate: '2026-10-07', timezone: 'Asia/Shanghai' };
const metadata = {
  period,
  generatedAt: '2026-10-08T00:00:00Z',
  environment: 'test',
  includesDevChannel: false,
};
const overview = {
  ...metadata,
  periodMetrics: {
    orderCreatedCount: 3,
    paidOrderCount: 1,
    paidUserCount: 1,
    receivedAmountFen: 10000,
    refundedAmountFen: 2000,
    netCashAmountFen: 8000,
    courseSoldQuantity: 1,
    bookSoldQuantity: 0,
    membershipNewPurchaseCount: 0,
    membershipRenewalCount: 0,
    commissionEarnedFen: 1000,
    commissionReversedFen: 200,
    withdrawalSucceededFen: 0,
  },
  currentMetrics: {
    activeMemberCount: 4,
    waitingShipmentOrderCount: 0,
    pendingCommissionFen: 800,
    availableCommissionFen: 0,
    withdrawingFen: 0,
    debtFen: 0,
  },
};
const trend = {
  ...metadata,
  items: [
    {
      date: '2026-10-06',
      orderCreatedCount: 3,
      paidOrderCount: 1,
      receivedAmountFen: 10000,
      refundedAmountFen: 0,
      netCashAmountFen: 10000,
    },
    {
      date: '2026-10-07',
      orderCreatedCount: 0,
      paidOrderCount: 0,
      receivedAmountFen: 0,
      refundedAmountFen: 2000,
      netCashAmountFen: -2000,
    },
  ],
};

function setup(data: unknown, status = 200) {
  const transport = vi.fn<Transport>(async () => ({
    status,
    body: {
      code: status === 200 ? 'OK' : 'ANALYTICS_RANGE_EXCEEDED',
      message: '范围超限',
      data,
      requestId: 'test',
    },
  }));
  return {
    api: createAdminOperationsApi(
      createClient({ baseUrl: '/api/v1', transport, session: emptySession() }),
    ),
    transport,
  };
}

function emptySession() {
  return createSession({ get: () => null, set: () => {}, remove: () => {} });
}

describe('analytics v1.1 public API boundary', () => {
  it('preserves period/current metadata from the backend, with required dates and DEV exclusion', async () => {
    const { api, transport } = setup(overview);
    expect(await api.getAnalyticsOverview(period.startDate, period.endDate)).toEqual(overview);
    expect(transport.mock.calls[0]?.[0]).toMatchObject({
      url: '/api/v1/admin/analytics/overview?startDate=2026-10-06&endDate=2026-10-07&includeDevChannel=false',
    });
  });

  it('preserves negative daily net cash rather than interpreting items as points', async () => {
    const { api } = setup(trend);
    expect(await api.getAnalyticsTrend(period.startDate, period.endDate)).toEqual(trend);
  });

  it.each([
    ['', '2026-10-07'],
    ['2026-02-30', '2026-03-01'],
    ['2026-10-08', '2026-10-07'],
    ['2025-01-01', '2026-01-02'],
  ])('rejects invalid date range %s / %s before sending', async (start, end) => {
    const { api, transport } = setup(overview);
    await expect(api.getAnalyticsOverview(start, end)).rejects.toThrow();
    expect(transport).not.toHaveBeenCalled();
  });

  it('rejects unsafe integer amounts instead of displaying rounded money', async () => {
    const { api } = setup({
      ...overview,
      periodMetrics: { ...overview.periodMetrics, netCashAmountFen: Number.MAX_SAFE_INTEGER + 1 },
    });
    await expect(api.getAnalyticsOverview(period.startDate, period.endDate)).rejects.toThrow();
  });

  it('propagates backend overflow failure without zero or mock fallback', async () => {
    const { api } = setup(null, 422);
    await expect(api.getAnalyticsOverview(period.startDate, period.endDate)).rejects.toMatchObject({
      code: 'ANALYTICS_RANGE_EXCEEDED',
    });
  });

  it('rejects missing environment metadata rather than labelling it as production data', async () => {
    const { api } = setup({ ...overview, environment: undefined });
    await expect(api.getAnalyticsOverview(period.startDate, period.endDate)).rejects.toThrow();
  });

  it('sends analytics export dates at the request top level', async () => {
    const { api, transport } = setup({ id: 'export-1', type: 'ANALYTICS', status: 'PENDING' });
    await api.createExport('ANALYTICS', { startDate: period.startDate, endDate: period.endDate });
    expect(transport.mock.calls[0]?.[0]).toMatchObject({
      body: { type: 'ANALYTICS', startDate: period.startDate, endDate: period.endDate },
    });
  });

  it('DEV fixture uses the same DTO, limits samples to the selected period and fills empty dates', async () => {
    const handler = createDevOperationsHandler();
    const api = createAdminOperationsApi(
      createClient({
        baseUrl: '/api/v1',
        session: emptySession(),
        transport: async (request) => {
          const url = new URL(request.url, 'https://local.test');
          return handler(request, url.pathname.replace('/api/v1', ''), url.searchParams)!;
        },
      }),
    );
    const result = await api.getAnalyticsTrend({
      startDate: '2026-10-06',
      endDate: '2026-10-08',
      includeDevChannel: true,
    });
    expect(result.environment).toBe('dev');
    expect(result.includesDevChannel).toBe(true);
    expect(result.items.map((item) => item.date)).toEqual([
      '2026-10-06',
      '2026-10-07',
      '2026-10-08',
    ]);
    expect(result.items[2]).toMatchObject({
      receivedAmountFen: 0,
      refundedAmountFen: 0,
      netCashAmountFen: 0,
    });
    const excluded = await api.getAnalyticsOverview('2026-10-06', '2026-10-08');
    expect(excluded.periodMetrics.receivedAmountFen).toBe(0);
  });
});
