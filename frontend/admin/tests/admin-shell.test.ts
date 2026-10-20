// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import AdminShell from '../src/components/AdminShell.vue';
import { session } from '../src/api/session';
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'http', apiBaseUrl: '/api/v1' },
}));
const wrappers: VueWrapper[] = [];
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  session.clear();
});
it('keeps permitted navigation, account and breadcrumbs on detail routes', async () => {
  const permissions = ['ORDER_OPERATE', 'AUDIT_READ'];
  session.set({
    accessToken: 'shell-token',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    admin: { id: '42', loginName: 'operator', permissions },
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div>业务页</div>' } }],
  });
  await router.push('/commerce/orders/31');
  const wrapper = mount(AdminShell, {
    attachTo: document.body,
    slots: { default: '订单详情内容' },
    global: { plugins: [createPinia(), router], stubs: { DevCommerceScenarios: true } },
  });
  wrappers.push(wrapper);
  await flushPromises();
  const links = wrapper
    .findAll('nav[aria-label="后台业务导航"] a')
    .map((link) => link.attributes('href'));
  expect(links).toContain('/commerce/orders');
  expect(links).toContain('/learning/membership/records');
  expect(links).toContain('/operations/audit-logs');
  expect(links).not.toContain('/operations/settings');
  expect(links).not.toContain('/content/products');
  expect(links).not.toContain('/operations/exports');
  expect(wrapper.text()).toContain('operator');
  expect(wrapper.find('nav[aria-label="当前位置"]').text()).toContain(
    '运营后台 / 交易履约 / 订单管理 / 详情',
  );
  expect(wrapper.text()).toContain('订单详情内容');
  expect(wrapper.findAll('button').some((button) => button.text() === '退出登录')).toBe(true);
  expect(wrapper.get('a[href="/commerce/orders"]').isVisible()).toBe(true);
  expect(wrapper.get('a[href="/operations/audit-logs"]').isVisible()).toBe(false);
  await wrapper
    .findAll('button')
    .find((b) => b.text().startsWith('经营管理'))!
    .trigger('click');
  expect(wrapper.get('a[href="/operations/audit-logs"]').isVisible()).toBe(true);
  await router.push('/learning/membership/records');
  await flushPromises();
  expect(wrapper.get('a[href="/learning/membership/records"]').isVisible()).toBe(true);
  expect(wrapper.get('a[href="/commerce/orders"]').isVisible()).toBe(false);
  expect(wrapper.get('a[href="/operations/audit-logs"]').isVisible()).toBe(true);
});
