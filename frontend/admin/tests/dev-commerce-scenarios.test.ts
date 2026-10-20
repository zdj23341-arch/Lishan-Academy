// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import DevCommerceScenarios from '../src/components/DevCommerceScenarios.vue';

const state = vi.hoisted(() => ({ mode: 'test', provider: 'mock', fail: false, calls: 0 }));
vi.mock('../src/api/environment', () => ({ environment: state }));
vi.mock('../../shared/src/dev/transport', async (original) => {
  const actual = await original<typeof import('../../shared/src/dev/transport')>();
  return {
    ...actual,
    devTransport: async (...args: Parameters<typeof actual.devTransport>) => {
      state.calls++;
      if (state.fail) {
        state.fail = false;
        throw new Error('DEV temporary failure');
      }
      return actual.devTransport(...args);
    },
  };
});
let wrapper: VueWrapper;
afterEach(() => {
  wrapper?.unmount();
  state.mode = 'test';
  state.provider = 'mock';
});
async function page() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  });
  await router.push('/');
  wrapper = mount(DevCommerceScenarios, { global: { plugins: [router] } });
  return wrapper;
}
it.each([
  { mode: 'production', provider: 'mock' },
  { mode: 'development', provider: 'http' },
])('hides setup and does not dispatch in $mode/$provider', async (environment) => {
  Object.assign(state, environment);
  const calls = state.calls;
  const w = await page();
  expect(w.find('button').exists()).toBe(false);
  expect(state.calls).toBe(calls);
});
it.each([
  { mode: 'production', provider: 'mock' },
  { mode: 'development', provider: 'http' },
])('blocks an already-rendered button after switching to $mode/$provider', async (environment) => {
  const w = await page();
  const calls = state.calls;
  Object.assign(state, environment);
  await w.get('button').trigger('click');
  await flushPromises();
  expect(state.calls).toBe(calls);
});
it('only prepares data after activation, shows failures, and retries to actual detail links', async () => {
  const w = await page();
  const calls = state.calls;
  await flushPromises();
  expect(state.calls).toBe(calls);
  state.fail = true;
  await w.get('button').trigger('click');
  await vi.dynamicImportSettled();
  await flushPromises();
  expect(w.get('[role="alert"]').text()).toContain('DEV temporary failure');
  await w.get('button').trigger('click');
  await flushPromises();
  expect(w.find('[role="alert"]').exists()).toBe(false);
  expect(w.text()).toContain('本地开发示例');
  const links = w.findAll('a').map((link) => link.attributes('href'));
  expect(links.filter((link) => link?.startsWith('/commerce/orders/'))).toHaveLength(3);
  expect(links.filter((link) => link?.startsWith('/commerce/aftersales?id='))).toHaveLength(2);
  const { devTransport } = await import('../../shared/src/dev/transport');
  for (const href of links) {
    const path = href!.startsWith('/commerce/orders/')
      ? href!.replace('/commerce', '')
      : `/aftersales/${new URL(href!, 'http://local').searchParams.get('id')}`;
    const response = await devTransport({ url: `/api/v1${path}`, method: 'GET', headers: {} });
    expect(response.status).toBe(200);
  }
});
