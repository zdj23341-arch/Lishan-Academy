import type { AnalyticsMetadata, AnalyticsOverview, AnalyticsQuery, AnalyticsTrend } from './types';

const dayMs = 86400000;
export function analyticsDates(startDate: string, endDate: string): string[] {
  const parse = (date: string) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('请选择完整的开始和结束日期');
    const time = Date.parse(`${date}T00:00:00Z`);
    if (!Number.isFinite(time) || new Date(time).toISOString().slice(0, 10) !== date)
      throw new Error('统计日期无效');
    return time;
  };
  const start = parse(startDate),
    end = parse(endDate);
  const count = (end - start) / dayMs + 1;
  if (count < 1 || count > 366) throw new Error('开始日期不能晚于结束日期，且区间不能超过366天');
  return Array.from({ length: count }, (_, i) =>
    new Date(start + i * dayMs).toISOString().slice(0, 10),
  );
}

export function defaultAnalyticsDates(now = new Date()): [string, string] {
  // Fixed UTC+08 offset: browser locale/timezone must not move the reporting day.
  const today = new Date(now.getTime() + 8 * 3600000).toISOString().slice(0, 10);
  const first = new Date(Date.parse(`${today}T00:00:00Z`) - 6 * dayMs).toISOString().slice(0, 10);
  return [first, today];
}

function assertMetadata(value: AnalyticsMetadata, query: AnalyticsQuery) {
  if (
    !value ||
    value.period?.startDate !== query.startDate ||
    value.period?.endDate !== query.endDate ||
    value.period?.timezone !== 'Asia/Shanghai' ||
    !['dev', 'test', 'prod'].includes(value.environment) ||
    value.includesDevChannel !== (query.includeDevChannel ?? false) ||
    (value.environment === 'prod' && value.includesDevChannel) ||
    typeof value.generatedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.generatedAt))
  )
    throw new Error('统计响应口径不完整或与查询不一致，请重试并核对接口版本');
}

function assertMetrics(value: object | undefined, keys: readonly string[]) {
  const fields = value as Record<string, unknown> | undefined;
  if (!fields || keys.some((key) => !Number.isSafeInteger(fields[key])))
    throw new Error('统计响应包含缺失字段或超出安全整数范围的值');
}

export function validateAnalyticsOverview(value: AnalyticsOverview, query: AnalyticsQuery) {
  assertMetadata(value, query);
  assertMetrics(value.periodMetrics, [
    'orderCreatedCount',
    'paidOrderCount',
    'paidUserCount',
    'receivedAmountFen',
    'refundedAmountFen',
    'netCashAmountFen',
    'courseSoldQuantity',
    'bookSoldQuantity',
    'membershipNewPurchaseCount',
    'membershipRenewalCount',
    'commissionEarnedFen',
    'commissionReversedFen',
    'withdrawalSucceededFen',
  ]);
  assertMetrics(value.currentMetrics, [
    'activeMemberCount',
    'waitingShipmentOrderCount',
    'pendingCommissionFen',
    'availableCommissionFen',
    'withdrawingFen',
    'debtFen',
  ]);
  for (const key of [
    'downloads',
    'downloadUsers',
    'previews',
    'goodsRefundsFen',
    'returnedUnits',
    'financialHoldOrders',
    'unclassifiedAuthorizations',
  ] as const) {
    if (value[key] !== undefined) assertMetrics(value, [key]);
  }
  return value;
}

export function validateAnalyticsTrend(value: AnalyticsTrend, query: AnalyticsQuery) {
  assertMetadata(value, query);
  const dates = analyticsDates(query.startDate, query.endDate);
  if (!Array.isArray(value.items) || value.items.length !== dates.length)
    throw new Error('统计趋势日期不完整，请重试');
  value.items.forEach((item, i) => {
    if (item.date !== dates[i]) throw new Error('统计趋势日期顺序或范围错误');
    assertMetrics(item, [
      'orderCreatedCount',
      'paidOrderCount',
      'receivedAmountFen',
      'refundedAmountFen',
      'netCashAmountFen',
    ]);
  });
  return value;
}
