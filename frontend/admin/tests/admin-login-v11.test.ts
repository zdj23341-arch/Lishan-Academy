// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import LoginView from '../src/views/LoginView.vue';
import { session } from '../src/api/session';

const mocks = vi.hoisted(() => ({ request: vi.fn(), replace: vi.fn() }));
vi.mock('../src/api/client', () => ({
  client: { request: mocks.request },
  environment: { provider: 'real' },
}));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'real', apiBaseUrl: '/api/v1' },
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
let wrapper: VueWrapper;
beforeEach(() => {
  vi.clearAllMocks();
  session.clear();
});
afterEach(() => {
  wrapper?.unmount();
  session.clear();
});
async function submit() {
  wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia()],
      stubs: { ElButton: { template: '<button type="submit"><slot /></button>' } },
    },
  });
  await wrapper.find('input[autocomplete="username"]').setValue('operator');
  await wrapper.find('input[type="password"]').setValue('example-password');
  await wrapper.find('form').trigger('submit');
  await flushPromises();
}
it('authenticates /admin/me with the accepted token and stores the real admin identity without inventing a customer', async () => {
  mocks.request.mockImplementation(async (path: string) => {
    if (path === '/admin/auth/login')
      return {
        accessToken: 'admin-token',
        expiresAt: '2099-01-01T00:00:00Z',
        permissions: ['ANALYTICS_READ'],
        passwordChangeRequired: false,
      };
    expect(session.get()?.accessToken).toBe('admin-token');
    return {
      id: 'admin-23',
      loginName: 'operator',
      permissions: ['ANALYTICS_READ'],
      roles: ['ANALYST'],
      passwordChangeRequired: false,
    };
  });
  await submit();
  expect(session.get()?.admin?.id).toBe('admin-23');
  expect(session.get()?.user).toBeUndefined();
  expect(mocks.replace).toHaveBeenCalledWith('/');
});
it('clears a provisional session when fetching the real admin identity fails', async () => {
  mocks.request
    .mockResolvedValueOnce({
      accessToken: 'admin-token',
      expiresAt: '2099-01-01T00:00:00Z',
      permissions: ['ANALYTICS_READ'],
    })
    .mockRejectedValueOnce(new Error('管理员信息获取失败'));
  await submit();
  expect(session.get()).toBeNull();
  expect(wrapper.find('[role="alert"]').text()).toContain('管理员信息获取失败');
  expect(mocks.replace).not.toHaveBeenCalled();
});

it('requires a bootstrap password change before navigation and clears the revoked session after changing it', async () => {
  const requests: Array<{ path: string; body: unknown }> = [];
  mocks.request.mockImplementation(async (path: string, options?: { body?: unknown }) => {
    requests.push({ path, body: options?.body });
    if (path === '/admin/auth/login')
      return {
        accessToken: 'bootstrap-token',
        expiresAt: '2099-01-01T00:00:00Z',
        permissions: [],
        passwordChangeRequired: true,
      };
    if (path === '/admin/me')
      return {
        id: '1',
        loginName: 'operator',
        roles: ['SUPER_ADMIN'],
        permissions: [],
        passwordChangeRequired: true,
      };
    if (path === '/admin/auth/change-password') return null;
    throw new Error('Unexpected request');
  });
  await submit();
  expect(mocks.replace).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain('修改密码');
  await wrapper.get('input[name="newPassword"]').setValue('local-new-password-123');
  await wrapper.get('input[name="confirmPassword"]').setValue('different-password');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(requests.some((request) => request.path === '/admin/auth/change-password')).toBe(false);
  await wrapper.get('input[name="confirmPassword"]').setValue('local-new-password-123');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(requests.at(-1)).toEqual({
    path: '/admin/auth/change-password',
    body: { currentPassword: 'example-password', newPassword: 'local-new-password-123' },
  });
  expect(session.get()).toBeNull();
  expect(wrapper.text()).toContain('请使用新密码登录');
  expect(mocks.replace).not.toHaveBeenCalled();
});
