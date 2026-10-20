import type { Id, IsoTime, MoneyFen, CommissionStatus, WithdrawalStatus } from '../contracts';

export type { WithdrawalStatus } from '../contracts';
export type DistributorStatus = 'ACTIVE' | 'DISABLED' | 'RISK_BLOCKED';

export interface WithdrawalLimits {
  minFen?: MoneyFen;
  maxFen?: MoneyFen;
  dailyMaxCount?: number;
  minAmountFen?: MoneyFen;
  maxAmountFen?: MoneyFen;
  dailyCountLimit?: number;
  dailyCountRemaining?: number;
}

export interface DistributorSummary {
  distributorId: Id;
  status?: DistributorStatus;
  receiptReady?: boolean;
  pendingFen: MoneyFen;
  availableFen: MoneyFen;
  withdrawingFen: MoneyFen;
  paidOutFen: MoneyFen;
  debtFen: MoneyFen;
  withdrawalEnabled: boolean;
  disabledReason: string | null;
  limits: WithdrawalLimits;
  promotableProductCount?: number;
  observedUnaccountedPaidOutFen?: number;
}

export interface CommissionItem {
  id: Id;
  orderId?: string;
  orderNo?: string;
  productId?: Id;
  distributorId?: string;
  goodsTitle?: string;
  baseFen?: MoneyFen;
  orderPaidAmountFen?: MoneyFen;
  rateBps: number; // 0 - 10000 (1% = 100 bps)
  earnedFen?: MoneyFen;
  commissionFen?: MoneyFen;
  reversedFen: MoneyFen;
  remainingFen: MoneyFen;
  state?: string;
  status?: CommissionStatus;
  createdAt?: IsoTime;
  eligibleAt?: IsoTime | null;
  settledAt?: IsoTime | null;
}

export interface CommissionFilters {
  status?: CommissionStatus;
  page?: number;
  pageSize?: number;
}

export interface ReferralCodeView {
  code: string;
  productId: Id;
  miniProgramPath: string;
  imageUrl: string | null;
  expiresAt: IsoTime;
}

export interface ResolveReferralResult {
  productId: Id;
  attributionToken: string;
  expiresAt: IsoTime;
}

export interface ClaimReferralResult {
  claimed: boolean;
  productId: Id;
}

export interface WithdrawalRequest {
  amountFen: MoneyFen;
}

export interface ConfirmationPayload {
  action?: 'WECHAT_CONFIRM_RECEIPT';
  merchantId?: string;
  appId?: string;
  packageInfo: string;
  miniprogramAppId?: string;
}

export interface WithdrawalRecord {
  id: Id;
  withdrawalNo: string;
  amountFen: MoneyFen;
  status: WithdrawalStatus;
  createdAt: IsoTime;
  successAt?: IsoTime | null;
  succeededAt?: IsoTime | null;
  failReason?: string | null;
  failedReason?: string | null;
  confirmation?: ConfirmationPayload | null;
  distributorId?: Id;
  outBillNo?: string;
  channel?: string;
  providerState?: string | null;
  errorCode?: string | null;
  nextQueryAt?: IsoTime | null;
  alerted?: boolean;
  reconciliation?: {
    status: string;
    accountedPaidOutFen: MoneyFen;
    observedPaidOutFen: MoneyFen;
    lastQueryState?: string | null;
  } | null;
}

// B端后台管理类型
export interface AdminDistributor {
  id: Id;
  userId: Id;
  username?: string;
  nickname?: string;
  phone?: string;
  status: DistributorStatus;
  createdAt?: IsoTime;
  version: number;
  totalCommissionFen?: MoneyFen;
  withdrawnFen?: MoneyFen;
  riskBlocked?: boolean;
  receiptReady?: boolean;
  reason?: string;
}

export interface ProductDistributorRule {
  distributorId: Id;
  distributorName?: string;
  rateBps: number; // 0-10000
  enabled: boolean;
  effectiveAt?: IsoTime | null;
  expiresAt?: IsoTime | null;
  ruleVersion?: number;
}

export interface ProductDistributionConfig {
  productId: Id;
  rules: ProductDistributorRule[];
  version: number;
}

export interface AdminWithdrawalAuditView extends WithdrawalRecord {
  distributorId: Id;
  distributorName?: string;
  phone?: string;
  channelTransferNo?: string | null;
}
