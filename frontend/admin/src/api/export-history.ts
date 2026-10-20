import { hasPermission, type ExportTask, type ExportType } from '@jxsc/shared';

export type ExportHistoryItem = Pick<ExportTask, 'id' | 'type' | 'status' | 'createdAt'>;
const typePermissions: Record<ExportType, string> = {
  ORDERS: 'ORDER_OPERATE',
  COMMISSIONS: 'DISTRIBUTION_OPERATE',
  WITHDRAWALS: 'FINANCE_OPERATE',
  ANALYTICS: 'ANALYTICS_READ',
};
export function canExport(permissions: readonly string[] | undefined, type: ExportType) {
  return (
    hasPermission(permissions, 'EXPORT_READ') && hasPermission(permissions, typePermissions[type])
  );
}
export function createExportHistory(
  storage: Pick<Storage, 'getItem' | 'setItem'>,
  scope: readonly string[],
) {
  const key = `jxsc.admin.exports.v2:${JSON.stringify(scope)}`;
  function list(): ExportHistoryItem[] {
    try {
      const value: unknown = JSON.parse(storage.getItem(key) ?? '[]');
      if (!Array.isArray(value)) return [];
      return value
        .filter(
          (item): item is ExportHistoryItem =>
            !!item &&
            typeof item === 'object' &&
            typeof item.id === 'string' &&
            item.id.length > 0 &&
            Object.hasOwn(typePermissions, item.type) &&
            ['REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED'].includes(item.status) &&
            (item.createdAt === undefined ||
              (typeof item.createdAt === 'string' && Number.isFinite(Date.parse(item.createdAt)))),
        )
        .slice(0, 20)
        .map(({ id, type, status, createdAt }) => ({ id, type, status, createdAt }));
    } catch {
      return [];
    }
  }
  return {
    list,
    remove(id: string) {
      try {
        storage.setItem(key, JSON.stringify(list().filter((item) => item.id !== id)));
      } catch {
        /* Optional history. */
      }
    },
    save(task: ExportTask) {
      const { id, type, status, createdAt } = task;
      const items = [
        { id, type, status, createdAt },
        ...list().filter((item) => item.id !== id),
      ].slice(0, 20);
      try {
        storage.setItem(key, JSON.stringify(items));
      } catch {
        /* History is optional; the task ID remains visible. */
      }
    },
  };
}
