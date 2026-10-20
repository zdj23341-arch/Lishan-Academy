import type { Page } from '../contracts';
import type { ApiClient } from '../http';
import type {
  AnalyticsOverview,
  AnalyticsTrend,
  AnalyticsQuery,
  AdminUser,
  AdminSettings,
  AuditLogItem,
  ExportTask,
  ExportType,
  RuntimeReadinessView,
  FinanceSettingsView,
} from './types';
import { analyticsDates, validateAnalyticsOverview, validateAnalyticsTrend } from './analytics';

function idPath(id: string) {
  if (!id.trim()) throw new Error('ID不能为空');
  return encodeURIComponent(id);
}

export function createAdminOperationsApi(client: ApiClient) {
  function analyticsQuery(params: string | AnalyticsQuery, endDate?: string): AnalyticsQuery {
    const query =
      typeof params === 'string'
        ? { startDate: params, endDate: endDate ?? '', includeDevChannel: false }
        : { ...params, includeDevChannel: params.includeDevChannel ?? false };
    analyticsDates(query.startDate, query.endDate);
    return query;
  }
  return {
    async getAnalyticsOverview(
      startDateOrParams: string | AnalyticsQuery,
      endDateArg?: string,
    ): Promise<AnalyticsOverview> {
      const query = analyticsQuery(startDateOrParams, endDateArg);
      const result = await client.request<AnalyticsOverview>('/admin/analytics/overview', {
        method: 'GET',
        query: { ...query },
      });
      return validateAnalyticsOverview(result, query);
    },

    async getOverview(params: AnalyticsQuery): Promise<AnalyticsOverview> {
      return this.getAnalyticsOverview(params);
    },

    async getAnalyticsTrend(
      startDateOrParams: string | AnalyticsQuery,
      endDateArg?: string,
    ): Promise<AnalyticsTrend> {
      const query = analyticsQuery(startDateOrParams, endDateArg);
      const result = await client.request<AnalyticsTrend>('/admin/analytics/trend', {
        method: 'GET',
        query: { ...query },
      });
      return validateAnalyticsTrend(result, query);
    },

    async getTrend(params: AnalyticsQuery): Promise<AnalyticsTrend> {
      return this.getAnalyticsTrend(params);
    },

    async listUsers(
      params: { keyword?: string; status?: string; page?: number; pageSize?: number } = {},
    ): Promise<Page<AdminUser>> {
      return client.request<Page<AdminUser>>('/admin/users', {
        method: 'GET',
        query: {
          keyword: params.keyword,
          status: params.status,
          page: params.page ?? 1,
          pageSize: params.pageSize ?? 20,
        },
      });
    },

    async updateUserStatus(
      id: string,
      status: 'NORMAL' | 'BLOCKED',
      reason: string,
      version: number,
    ): Promise<AdminUser> {
      return client.request<AdminUser>(`/admin/users/${idPath(id)}/status`, {
        method: 'PATCH',
        body: { status, reason, version },
      });
    },

    async getSettings(): Promise<AdminSettings> {
      return client.request<AdminSettings>('/admin/settings', {
        method: 'GET',
      });
    },

    async updateSettings(
      settings: Partial<AdminSettings>,
      version: number,
    ): Promise<AdminSettings> {
      return client.request<AdminSettings>('/admin/settings', {
        method: 'PATCH',
        body: { ...settings, version },
      });
    },

    async listAuditLogs(
      params: {
        targetType?: string;
        operatorId?: string;
        startDate?: string;
        endDate?: string;
        page?: number;
        pageSize?: number;
      } = {},
    ): Promise<Page<AuditLogItem>> {
      return client.request<Page<AuditLogItem>>('/admin/audit-logs', {
        method: 'GET',
        query: {
          page: params.page ?? 1,
          pageSize: params.pageSize ?? 20,
        },
      });
    },

    async getRuntimeReadiness(): Promise<RuntimeReadinessView> {
      return client.request<RuntimeReadinessView>('/admin/runtime', {
        method: 'GET',
      });
    },

    async getFinanceSettings(): Promise<FinanceSettingsView> {
      return client.request<FinanceSettingsView>('/admin/settings', {
        method: 'GET',
      });
    },

    async updateFinanceSettings(params: {
      version: number;
      values: Record<string, number>;
      reason: string;
    }): Promise<FinanceSettingsView> {
      return client.request<FinanceSettingsView>('/admin/settings', {
        method: 'PATCH',
        body: { version: params.version, values: params.values, reason: params.reason },
      });
    },

    async createExport(
      typeOrPayload:
        | ExportType
        | {
            type?: ExportType;
            exportType?: ExportType;
            startDate?: string;
            endDate?: string;
            filters?: Record<string, unknown>;
          },
      startDateOrFilters?: string | Record<string, unknown>,
      endDateArg?: string,
    ): Promise<ExportTask> {
      let type: ExportType;
      let startDate: string | undefined;
      let endDate: string | undefined;
      const filters =
        typeof typeOrPayload === 'object'
          ? typeOrPayload.filters
          : typeof startDateOrFilters === 'object'
            ? startDateOrFilters
            : undefined;
      if (
        Object.entries(filters ?? {}).some(
          ([key, value]) =>
            !['startDate', 'endDate'].includes(key) &&
            value !== undefined &&
            value !== null &&
            value !== '',
        )
      )
        throw new Error('当前导出仅支持日期范围，请清除其他筛选后重试');

      if (typeof typeOrPayload === 'object') {
        type = (typeOrPayload.type || typeOrPayload.exportType) as ExportType;
        startDate = typeOrPayload.startDate || (typeOrPayload.filters?.startDate as string);
        endDate = typeOrPayload.endDate || (typeOrPayload.filters?.endDate as string);
      } else {
        type = typeOrPayload;
        if (typeof startDateOrFilters === 'string') {
          startDate = startDateOrFilters;
          endDate = endDateArg;
        } else if (typeof startDateOrFilters === 'object') {
          startDate = startDateOrFilters?.startDate as string;
          endDate = (startDateOrFilters?.endDate || endDateArg) as string;
        }
      }

      if (!startDate || !endDate) throw new Error('请选择导出开始与结束日期');
      analyticsDates(startDate, endDate);
      if (!['ORDERS', 'COMMISSIONS', 'WITHDRAWALS', 'ANALYTICS'].includes(type))
        throw new Error('导出类型无效');

      return client.request<ExportTask>('/admin/exports', {
        method: 'POST',
        body: { type, startDate, endDate },
      });
    },

    async getExportTask(id: string): Promise<ExportTask> {
      return client.request<ExportTask>(`/admin/exports/${idPath(id)}`, {
        method: 'GET',
      });
    },
  };
}

export type AdminOperationsApi = ReturnType<typeof createAdminOperationsApi>;
