import type { Id, IsoTime, MoneyFen } from '../contracts';

export interface AnalyticsQuery {
  startDate: string;
  endDate: string;
  includeDevChannel?: boolean;
}

export interface AnalyticsMetadata {
  period: { startDate: string; endDate: string; timezone: 'Asia/Shanghai' };
  generatedAt: IsoTime;
  environment: 'dev' | 'test' | 'prod';
  includesDevChannel: boolean;
}

export interface AnalyticsOverview extends AnalyticsMetadata {
  periodMetrics: {
    orderCreatedCount: number;
    paidOrderCount: number;
    paidUserCount: number;
    receivedAmountFen: MoneyFen;
    refundedAmountFen: MoneyFen;
    netCashAmountFen: MoneyFen;
    courseSoldQuantity: number;
    bookSoldQuantity: number;
    membershipNewPurchaseCount: number;
    membershipRenewalCount: number;
    commissionEarnedFen: MoneyFen;
    commissionReversedFen: MoneyFen;
    withdrawalSucceededFen: MoneyFen;
  };
  currentMetrics: {
    activeMemberCount: number;
    waitingShipmentOrderCount: number;
    pendingCommissionFen: MoneyFen;
    availableCommissionFen: MoneyFen;
    withdrawingFen: MoneyFen;
    debtFen: MoneyFen;
  };
  // Backend detailed report fields remain available alongside the v1.1 summary.
  downloads?: number;
  downloadUsers?: number;
  previews?: number;
  goodsRefundsFen?: MoneyFen;
  returnedUnits?: number;
  financialHoldOrders?: number;
  unclassifiedAuthorizations?: number;
}

export interface AnalyticsTrendPoint {
  date: string;
  orderCreatedCount: number;
  paidOrderCount: number;
  receivedAmountFen: MoneyFen;
  refundedAmountFen: MoneyFen;
  netCashAmountFen: MoneyFen;
}

export interface AnalyticsTrend extends AnalyticsMetadata {
  items: AnalyticsTrendPoint[];
}

export interface AdminUser {
  id: Id;
  username: string | null;
  nickname: string;
  phone: string | null;
  status: 'NORMAL' | 'BLOCKED' | 'CLOSED';
  registeredAt: IsoTime;
  lastLoginAt: IsoTime | null;
  version: number;
  membership?: {
    status: 'NONE' | 'ACTIVE' | 'EXPIRED' | 'PENDING';
    startsAt: IsoTime | null;
    expiresAt: IsoTime | null;
  } | null;
}

export interface AdminSettings {
  mallName: string;
  customerServiceWechat: string;
  aftersaleDays: number;
  attributionWindowDays: number;
  logisticsProviders: string[];
  version: number;
}

export interface AuditLogItem {
  id: Id;
  actorType: string;
  actorId: Id;
  resourceType: string;
  resourceId: string;
  before: unknown;
  after: unknown;
  action: string;
  reason: string;
  createdAt: IsoTime;
  requestId: string;
}

export type ExportType = 'ORDERS' | 'COMMISSIONS' | 'WITHDRAWALS' | 'ANALYTICS';

export interface ExportTask {
  id: Id;
  type: ExportType;
  status: 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
  downloadUrl: string | null;
  expiresAt: IsoTime | null;
  createdAt?: IsoTime;
  rowCount?: number;
  errorCode?: string | null;
  errorReason?: string | null;
}

export interface RuntimeTaskStatus {
  type: string;
  status: string;
  count: number;
  retries: number;
}

export interface RuntimeReadinessView {
  tasks: RuntimeTaskStatus[];
  expiredAssetLeases: number;
  heldOrders: number;
  unresolvedTransfers: number;
  transferRecoveryReady: boolean;
  transferCreateReady: boolean;
}

export interface FinanceSettingsLimits {
  minFen: number;
  maxFen: number;
  dailyMaxCount: number;
}

export interface FinanceSettingsView {
  version: number;
  limits: FinanceSettingsLimits;
  observationDays: number;
}
