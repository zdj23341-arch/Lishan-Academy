import type { TransportRequest } from '../http';
import { devAnalytics } from './analytics';
import type { AdminUser, AdminSettings, AuditLogItem, ExportTask } from '../operations/types';

export class DevOperationsStore {
  users: AdminUser[] = [
    {
      id: 'user-101',
      username: 'stu_zhang',
      nickname: '张同学',
      phone: '13812345678',
      status: 'NORMAL',
      registeredAt: '2026-09-01T10:00:00Z',
      lastLoginAt: '2026-10-07T08:00:00Z',
      version: 1,
    },
    {
      id: 'user-102',
      username: 'tea_li',
      nickname: '李老师',
      phone: '13987654321',
      status: 'NORMAL',
      registeredAt: '2026-09-10T11:00:00Z',
      lastLoginAt: '2026-10-06T15:00:00Z',
      version: 1,
    },
  ];

  settings: AdminSettings = {
    mallName: '力山学堂',
    customerServiceWechat: 'jxsc_helper',
    aftersaleDays: 7,
    attributionWindowDays: 30,
    logisticsProviders: ['顺丰速运', '中通快递', '圆通速递', '中国邮政EMS'],
    version: 1,
  };

  auditLogs: AuditLogItem[] = [
    {
      id: 'log-1',
      actorType: 'ADMIN',
      actorId: 'DEV-admin',
      resourceType: 'PRODUCT_DISTRIBUTION',
      resourceId: 'prod-demo-book',
      action: 'UPDATE_COMMISSION_RULE',
      reason: '设置秋季冲刺图书分销比例为15%',
      before: { rateBps: 1000 },
      after: { rateBps: 1500 },
      createdAt: '2026-10-07T09:00:00Z',
      requestId: 'req-audit-1',
    },
  ];

  exportTasks = new Map<string, ExportTask>();
}

export function createDevOperationsHandler(store: DevOperationsStore = new DevOperationsStore()) {
  let exportSeq = 1;

  return (
    request: TransportRequest,
    path: string,
    params: { get: (k: string) => string | null },
  ): { status: number; body: unknown } | null => {
    const reply = (data: unknown, status = 200, code = 'OK', message = '') => ({
      status,
      body: { code, message, data: JSON.parse(JSON.stringify(data)), requestId: 'DEV-ops-req' },
    });

    const getBody = () => (request.body ?? {}) as Record<string, unknown>;

    if (
      ['/admin/analytics/overview', '/admin/analytics/trend'].includes(path) &&
      request.method === 'GET'
    ) {
      try {
        const result = devAnalytics({
          startDate: params.get('startDate') ?? '',
          endDate: params.get('endDate') ?? '',
          includeDevChannel: params.get('includeDevChannel') === 'true',
        });
        return reply(path.endsWith('/overview') ? result.overview : result.trend);
      } catch (cause) {
        return reply(
          null,
          400,
          'VALIDATION_ERROR',
          cause instanceof Error ? cause.message : '日期区间错误',
        );
      }
    }

    if (path === '/admin/users' && request.method === 'GET') {
      return reply({
        items: store.users,
        page: 1,
        pageSize: 20,
        total: store.users.length,
      });
    }

    if (
      path.startsWith('/admin/users/') &&
      path.endsWith('/status') &&
      request.method === 'PATCH'
    ) {
      const id = path.split('/')[3]!;
      const { status, version } = getBody() as {
        status: 'NORMAL' | 'BLOCKED';
        reason: string;
        version: number;
      };
      const user = store.users.find((u) => u.id === id);
      if (!user) return reply(null, 404, 'NOT_FOUND', '用户不存在');
      if (status) user.status = status;
      user.version = (version || user.version) + 1;
      return reply(user);
    }

    if (path === '/admin/settings') {
      if (request.method === 'GET') return reply(store.settings);
      if (request.method === 'PATCH') {
        const patch = getBody() as Partial<AdminSettings>;
        store.settings = { ...store.settings, ...patch, version: store.settings.version + 1 };
        return reply(store.settings);
      }
    }

    if (path === '/admin/audit-logs' && request.method === 'GET') {
      return reply({
        items: store.auditLogs,
        page: 1,
        pageSize: 20,
        total: store.auditLogs.length,
      });
    }

    if (path === '/admin/exports' && request.method === 'POST') {
      const { type } = getBody() as { type: ExportTask['type'] };
      const id = `exp-${Date.now()}-${exportSeq++}`;
      const task: ExportTask = {
        id,
        type: type || 'COMMISSIONS',
        status: 'SUCCEEDED',
        downloadUrl: null,
        expiresAt: new Date(Date.now() + 3600000).toISOString(),
        createdAt: new Date().toISOString(),
        errorReason: null,
      };
      store.exportTasks.set(id, task);
      return reply(task);
    }

    if (path.startsWith('/admin/exports/') && request.method === 'GET') {
      const id = path.replace('/admin/exports/', '');
      const task = store.exportTasks.get(id);
      if (!task) return reply(null, 404, 'NOT_FOUND', '导出任务不存在');
      return reply(task);
    }

    return null;
  };
}
