import { expect, it } from 'vitest';
import { createDevTransport } from '../src/dev/transport';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import type { LoginView } from '../src/contracts';

it('development admin login supports implemented admin roles without giving them to customer login', async () => {
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    transport: createDevTransport(),
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  const admin = await client.request<LoginView>('/admin/auth/login', {
    method: 'POST',
    body: { loginName: 'DEV', password: 'DEV' },
  });
  expect(admin.permissions).toEqual([
    'CONTENT_EDIT',
    'ORDER_OPERATE',
    'FINANCE_OPERATE',
    'DISTRIBUTION_OPERATE',
    'ANALYTICS_READ',
    'AUDIT_READ',
    'SETTINGS_MANAGE',
    'EXPORT_READ',
  ]);
  const customer = await client.request<LoginView>('/auth/dev-login', { method: 'POST' });
  expect(customer.permissions).toEqual([]);
  expect(customer.user?.id).not.toBe(admin.user?.id);
});

it('accepts real backend AdminLoginResult without customer user and parses permissions', () => {
  const memStore: Record<string, string> = {};
  const storage = {
    get: (k: string) => memStore[k] ?? null,
    set: (k: string, v: string) => {
      memStore[k] = v;
    },
    remove: (k: string) => {
      delete memStore[k];
    },
  };
  const sess = createSession(storage);
  const realBackendAdminLogin = {
    accessToken: 'admin-jwt-token-xyz',
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    permissions: ['CONTENT_EDIT', 'ORDER_OPERATE'],
    passwordChangeRequired: true,
  };
  // Should NOT throw '无效登录响应'
  expect(() => sess.set(realBackendAdminLogin)).not.toThrow();
  const current = sess.get();
  expect(current?.accessToken).toBe('admin-jwt-token-xyz');
  expect(current?.permissions).toContain('CONTENT_EDIT');
});

it('hasPermission correctly maps both action permissions and legacy role names', async () => {
  const { hasPermission } = await import('../src/contracts');
  // 1. 真实权限码
  const perms = ['CONTENT_EDIT', 'ORDER_OPERATE'];
  expect(hasPermission(perms, 'CONTENT_EDIT')).toBe(true);
  expect(hasPermission(perms, 'CONTENT_EDITOR')).toBe(true); // 别名兼容
  expect(hasPermission(perms, 'ORDER_OPERATE')).toBe(true);
  expect(hasPermission(perms, 'ORDER_OPERATOR')).toBe(true);
  expect(hasPermission(perms, 'SETTINGS_MANAGE')).toBe(false);

  // 2. 超级管理员
  expect(hasPermission(['SUPER_ADMIN'], 'CONTENT_EDIT')).toBe(true);
  expect(hasPermission(['SUPER_ADMIN'], 'SETTINGS_MANAGE')).toBe(true);
  expect(hasPermission(['SETTINGS_MANAGE'], 'SUPER_ADMIN')).toBe(false);
});
