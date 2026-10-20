import type { Page } from '../contracts';
import type { ApiClient } from '../http';
import type {
  DistributorSummary,
  CommissionItem,
  CommissionFilters,
  ReferralCodeView,
  ResolveReferralResult,
  ClaimReferralResult,
  WithdrawalRecord,
} from './types';

function idPath(id: string) {
  if (!id.trim()) throw new Error('ID不能为空');
  return encodeURIComponent(id);
}

export function createDistributionApi(client: ApiClient) {
  return {
    async createReferralCode(productId: string): Promise<ReferralCodeView> {
      return client.request<ReferralCodeView>('/distribution/referral-codes', {
        method: 'POST',
        body: { productId },
      });
    },

    async resolveReferral(code: string, visitorKey: string): Promise<ResolveReferralResult> {
      return client.request<ResolveReferralResult>('/referrals/resolve', {
        method: 'POST',
        body: { code, visitorKey },
      });
    },

    async claimReferral(attributionToken: string): Promise<ClaimReferralResult> {
      const result = await client.request<ClaimReferralResult & { attributionToken?: string }>(
        '/referrals/claim',
        {
          method: 'POST',
          body: { attributionToken },
        },
      );
      return {
        productId: String(result.productId),
        claimed: result.claimed ?? result.attributionToken === attributionToken,
      };
    },

    async getSummary(): Promise<DistributorSummary> {
      return client.request<DistributorSummary>('/distribution/summary', {
        method: 'GET',
      });
    },

    async getOptionalSummary(): Promise<DistributorSummary | null> {
      return client.request<DistributorSummary | null>('/distribution/summary', {
        method: 'GET',
        query: { optional: true },
      });
    },

    async bindReceiptProfile(realName = ''): Promise<{ ready: boolean; nameProvided: boolean }> {
      if (realName.trim().length > 64) throw new Error('收款实名不能超过64个字符');
      return client.request('/distribution/receipt-profile', {
        method: 'POST',
        body: { realName: realName.trim() || null },
      });
    },

    async queryWithdrawalOperation(
      key: string,
    ): Promise<{ state: string; resourceId?: string | null; response?: unknown }> {
      return client.request(`/operations/${idPath(key)}`, {
        method: 'GET',
        query: { scope: 'CREATE_WITHDRAWAL' },
      });
    },

    async getCommissions(filters: CommissionFilters = {}): Promise<Page<CommissionItem>> {
      return client.request<Page<CommissionItem>>('/distribution/commissions', {
        method: 'GET',
        query: {
          status: filters.status,
          page: filters.page ?? 1,
          pageSize: filters.pageSize ?? 20,
        },
      });
    },

    async requestWithdrawal(amountFen: number, idempotencyKey: string): Promise<WithdrawalRecord> {
      return client.request<WithdrawalRecord>('/distribution/withdrawals', {
        method: 'POST',
        body: { amountFen },
        idempotencyKey,
      });
    },

    async getWithdrawal(id: string): Promise<WithdrawalRecord> {
      return client.request<WithdrawalRecord>(`/distribution/withdrawals/${idPath(id)}`, {
        method: 'GET',
      });
    },

    async refreshWithdrawal(id: string): Promise<WithdrawalRecord> {
      return client.request<WithdrawalRecord>(`/distribution/withdrawals/${idPath(id)}/refresh`, {
        method: 'POST',
      });
    },

    async listWithdrawals(page = 1, pageSize = 20): Promise<Page<WithdrawalRecord>> {
      return client.request<Page<WithdrawalRecord>>('/distribution/withdrawals', {
        method: 'GET',
        query: { page, pageSize },
      });
    },
  };
}

export type DistributionApi = ReturnType<typeof createDistributionApi>;
