// @vitest-environment jsdom
import { expect, it, vi } from 'vitest';
import { hasPermission } from '../../shared/src/contracts';
vi.mock('../src/api/session', () => ({ session: { get: () => null } }));
import { router } from '../src/router';

it.each([
  ['/operations/users', 'USER_MANAGE', 'ORDER_OPERATE'],
  ['/learning/membership/records', 'ORDER_OPERATE', 'CONTENT_EDIT'],
  ['/operations/settings', 'SETTINGS_MANAGE', 'ORDER_OPERATE'],
  ['/operations/audit-logs', 'AUDIT_READ', 'ANALYTICS_READ'],
  ['/operations/exports', 'EXPORT_READ', 'FINANCE_OPERATE'],
  ['/distribution/withdrawals', 'FINANCE_OPERATE', 'CONTENT_EDIT'],
])(
  'uses actual %s route capability without unrelated permission fallback',
  (path, allowed, denied) => {
    const meta = router.resolve(path).meta;
    const can = (permission: string) =>
      typeof meta.permission === 'string'
        ? hasPermission([permission], meta.permission)
        : (meta.permissions as string[]).some((required) => hasPermission([permission], required));
    expect(can(allowed)).toBe(true);
    expect(can(denied)).toBe(false);
  },
);
