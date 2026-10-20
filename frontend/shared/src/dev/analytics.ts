import type {
  AnalyticsOverview,
  AnalyticsQuery,
  AnalyticsTrend,
  AnalyticsTrendPoint,
} from '../operations/types';
import { analyticsDates } from '../operations/analytics';

// Explicit front-end fixtures, never used as a fallback for a failed real API.
export function devAnalytics(query: AnalyticsQuery): {
  overview: AnalyticsOverview;
  trend: AnalyticsTrend;
} {
  const includesDevChannel = query.includeDevChannel ?? false;
  const metadata = {
    period: {
      startDate: query.startDate,
      endDate: query.endDate,
      timezone: 'Asia/Shanghai' as const,
    },
    generatedAt: new Date().toISOString(),
    environment: 'dev' as const,
    includesDevChannel,
  };
  const samples: Record<string, AnalyticsTrendPoint> = {
    '2026-10-06': {
      date: '2026-10-06',
      orderCreatedCount: 3,
      paidOrderCount: 1,
      receivedAmountFen: 10000,
      refundedAmountFen: 0,
      netCashAmountFen: 10000,
    },
    '2026-10-07': {
      date: '2026-10-07',
      orderCreatedCount: 0,
      paidOrderCount: 0,
      receivedAmountFen: 0,
      refundedAmountFen: 2000,
      netCashAmountFen: -2000,
    },
  };
  const items = analyticsDates(query.startDate, query.endDate).map((date) =>
    includesDevChannel && samples[date]
      ? { ...samples[date] }
      : {
          date,
          orderCreatedCount: 0,
          paidOrderCount: 0,
          receivedAmountFen: 0,
          refundedAmountFen: 0,
          netCashAmountFen: 0,
        },
  );
  const sum = (field: keyof Omit<AnalyticsTrendPoint, 'date'>) =>
    items.reduce((total, item) => total + item[field], 0);
  const sales = sum('paidOrderCount');
  return {
    overview: {
      ...metadata,
      periodMetrics: {
        orderCreatedCount: sum('orderCreatedCount'),
        paidOrderCount: sales,
        paidUserCount: sales,
        receivedAmountFen: sum('receivedAmountFen'),
        refundedAmountFen: sum('refundedAmountFen'),
        netCashAmountFen: sum('netCashAmountFen'),
        courseSoldQuantity: sales,
        bookSoldQuantity: 0,
        membershipNewPurchaseCount: 0,
        membershipRenewalCount: 0,
        commissionEarnedFen: sales * 1000,
        commissionReversedFen: sum('refundedAmountFen') / 10,
        withdrawalSucceededFen: 0,
      },
      currentMetrics: {
        activeMemberCount: 0,
        waitingShipmentOrderCount: 0,
        pendingCommissionFen: includesDevChannel ? 800 : 0,
        availableCommissionFen: 0,
        withdrawingFen: 0,
        debtFen: 0,
      },
      downloads: 0,
      downloadUsers: 0,
      previews: 0,
      goodsRefundsFen: 0,
      returnedUnits: 0,
      financialHoldOrders: 0,
      unclassifiedAuthorizations: 0,
    },
    trend: { ...metadata, items },
  };
}
