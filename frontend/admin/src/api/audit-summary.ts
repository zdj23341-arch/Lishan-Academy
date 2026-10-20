import type { AuditLogItem } from '@jxsc/shared';

// Show changed public configuration fields only. Never stringify arbitrary audit snapshots.
const safe = new Set([
  'version',
  'status',
  'state',
  'enabled',
  'riskBlocked',
  'rateBps',
  'commissionRateBps',
  'minFen',
  'maxFen',
  'dailyMaxCount',
  'observationDays',
  'sortNo',
  'durationMonths',
  'validityDays',
]);
export function auditSummary(item: Pick<AuditLogItem, 'before' | 'after'>): string {
  const record = (value: unknown): Record<string, unknown> =>
    value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : {};
  const before = record(item.before),
    after = record(item.after);
  const scalar = (value: unknown) =>
    typeof value === 'boolean' ||
    (typeof value === 'number' && Number.isFinite(value)) ||
    (typeof value === 'string' && /^[A-Z_]{1,40}$/.test(value));
  function diff(a: Record<string, unknown>, b: Record<string, unknown>, prefix = '') {
    return [...safe]
      .filter((key) => a[key] !== b[key] && (key in a || key in b))
      .map(
        (key) =>
          `${prefix}${key}: ${scalar(a[key]) ? String(a[key]) : '—'} → ${scalar(b[key]) ? String(b[key]) : '—'}`,
      );
  }
  const changes = diff(before, after);
  changes.push(...diff(record(before.limits), record(after.limits), 'limits.'));
  function rules(value: unknown) {
    const result = new Map<string, Record<string, unknown>>();
    if (Array.isArray(value))
      for (const item of value.slice(0, 100)) {
        const rule = record(item);
        if (typeof rule.distributorId === 'string' && /^[0-9]{1,24}$/.test(rule.distributorId))
          result.set(rule.distributorId, rule);
      }
    return result;
  }
  const oldRules = rules(before.rules),
    newRules = rules(after.rules);
  for (const id of new Set([...oldRules.keys(), ...newRules.keys()])) {
    changes.push(...diff(oldRules.get(id) ?? {}, newRules.get(id) ?? {}, `rules[${id}].`));
  }
  return changes.join('；') || '无可展示的配置差异';
}
