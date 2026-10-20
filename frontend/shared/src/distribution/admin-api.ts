import type { Page } from '../contracts';
import type { ApiClient } from '../http';
import type {
  AdminDistributor,
  DistributorStatus,
  ProductDistributionConfig,
  ProductDistributorRule,
  AdminWithdrawalAuditView,
  CommissionItem,
} from './types';

function idPath(id: string) {
  if (!id.trim()) throw new Error('ID不能为空');
  return encodeURIComponent(id);
}

export function createAdminDistributionApi(client: ApiClient) {
  return {
    async listDistributors(
      params: {
        keyword?: string;
        status?: DistributorStatus;
        page?: number;
        pageSize?: number;
      } = {},
    ): Promise<Page<AdminDistributor>> {
      return client.request<Page<AdminDistributor>>('/admin/distributors', {
        method: 'GET',
        query: {
          page: params.page ?? 1,
          pageSize: params.pageSize ?? 20,
        },
      });
    },

    async assignDistributor(userId: string, reason = '运营指派'): Promise<AdminDistributor> {
      return client.request<AdminDistributor>('/admin/distributors', {
        method: 'POST',
        body: { userId, reason },
      });
    },

    async updateDistributorStatus(
      id: string,
      status: DistributorStatus,
      reason: string,
      version: number,
      riskBlocked?: boolean,
    ): Promise<AdminDistributor> {
      const isRiskBlocked = riskBlocked ?? status === 'RISK_BLOCKED';
      const serverStatus = status === 'RISK_BLOCKED' ? 'DISABLED' : status;
      return client.request<AdminDistributor>(`/admin/distributors/${idPath(id)}`, {
        method: 'PATCH',
        body: {
          status: serverStatus,
          riskBlocked: isRiskBlocked,
          reason: reason || '状态调整',
          version,
        },
      });
    },

    async getProductRules(productId: string): Promise<ProductDistributionConfig> {
      const result = await client.request<ProductDistributionConfig>(
        `/admin/products/${idPath(productId)}/distributors`,
        {
          method: 'GET',
        },
      );
      return { ...result, productId };
    },

    async getProductDistributionConfig(productId: string): Promise<ProductDistributionConfig> {
      return this.getProductRules(productId);
    },

    async updateProductRules(
      productId: string,
      rules: ProductDistributorRule[],
      version: number,
      reason = '调整商品分销配置',
    ): Promise<ProductDistributionConfig> {
      const result = await client.request<ProductDistributionConfig>(
        `/admin/products/${idPath(productId)}/distributors`,
        {
          method: 'PUT',
          body: { rules, version, reason },
        },
      );
      return { ...result, productId };
    },

    async saveProductDistributionConfig(
      productId: string,
      rules: ProductDistributorRule[],
      version: number,
    ): Promise<ProductDistributionConfig> {
      return this.updateProductRules(productId, rules, version);
    },

    async listCommissions(
      params: {
        status?: string;
        page?: number;
        pageSize?: number;
      } = {},
    ): Promise<Page<CommissionItem>> {
      return client.request<Page<CommissionItem>>('/admin/commissions', {
        method: 'GET',
        query: {
          page: params.page ?? 1,
          pageSize: params.pageSize ?? 20,
        },
      });
    },

    async listWithdrawals(
      params: {
        status?: string;
        keyword?: string;
        page?: number;
        pageSize?: number;
      } = {},
    ): Promise<Page<AdminWithdrawalAuditView>> {
      return client.request<Page<AdminWithdrawalAuditView>>('/admin/withdrawals', {
        method: 'GET',
        query: {
          page: params.page ?? 1,
          pageSize: params.pageSize ?? 20,
        },
      });
    },

    async refreshWithdrawal(id: string): Promise<AdminWithdrawalAuditView> {
      return client.request<AdminWithdrawalAuditView>(`/admin/withdrawals/${idPath(id)}/refresh`, {
        method: 'POST',
      });
    },
  };
}

export type AdminDistributionApi = ReturnType<typeof createAdminDistributionApi>;
