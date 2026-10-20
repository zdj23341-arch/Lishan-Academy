// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ExportsView from '../src/views/operations/ExportsView.vue';
import { session } from '../src/api/session';
import { exportHistory } from '../src/api/operations';

const request = vi.hoisted(() => vi.fn());
vi.mock('../src/api/client', () => ({ client: { request } }));
vi.mock('../src/api/environment', () => ({ environment: { mode: 'test', provider: 'http', apiBaseUrl: 'https://api.example/api/v1' } }));
const wrappers: VueWrapper[] = [];
function login(id: string, permissions = ['EXPORT_READ', 'ORDER_OPERATE']) {
  session.set({ accessToken: `token-${id}`, expiresAt: '2099-01-01T00:00:00Z', permissions, admin: { id, loginName: id, permissions } });
}
function render() {
  const wrapper = mount(ExportsView, { global: { plugins: [ElementPlus] } });
  wrappers.push(wrapper);
  return wrapper;
}
beforeEach(() => { request.mockReset(); localStorage.clear(); session.clear(); login('a'); });
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); session.clear(); vi.unstubAllGlobals(); });

it('clears account A data on logout and never persists A late response into B history', async () => {
  let resolve!: (value: unknown) => void;
  request.mockReturnValue(new Promise((done) => { resolve = done; }));
  const wrapper = render();
  await wrapper.findAll('button').find((button) => button.text() === '创建导出任务')!.trigger('click');
  session.clear();
  login('b');
  resolve({ id: 'a-task', type: 'ORDERS', status: 'REQUESTED', downloadUrl: null, expiresAt: null });
  await flushPromises();
  expect(wrapper.text()).not.toContain('a-task');
  expect(exportHistory()?.list()).toEqual([]);
  login('a');
  expect(exportHistory()?.list()).toEqual([]);
});

it('shares current actor tasks across entry points and downloads real SUCCEEDED with Bearer', async () => {
  const task = { id: '37', type: 'ORDERS', status: 'SUCCEEDED', downloadUrl: '/api/v1/admin/exports/37/content', expiresAt: '2099-01-01T00:00:00Z' };
  request.mockResolvedValue(task);
  const wrapper = render();
  await wrapper.findAll('button').find((button) => button.text() === '创建导出任务')!.trigger('click');
  await flushPromises();
  expect(exportHistory()?.list()).toMatchObject([{ id: '37', status: 'SUCCEEDED' }]);
  const fetcher = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['csv']) });
  vi.stubGlobal('fetch', fetcher);
  URL.createObjectURL = vi.fn(() => 'blob:export-test');
  URL.revokeObjectURL = vi.fn();
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  await wrapper.findAll('button').find((button) => button.text() === '下载 CSV')!.trigger('click');
  await flushPromises();
  expect(fetcher).toHaveBeenCalledWith('https://api.example/api/v1/admin/exports/37/content', expect.objectContaining({ headers: { Authorization: 'Bearer token-a' } }));
  expect(click).toHaveBeenCalled();
  click.mockRestore();
  session.clear();
  login('b');
  await flushPromises();
  expect(wrapper.find('[data-testid="export-task"]').exists()).toBe(false);
  expect(exportHistory()?.list()).toEqual([]);
});

it('shows no export form with only business permission', async () => {
  login('a', ['ORDER_OPERATE']);
  const wrapper = render();
  await flushPromises();
  expect(wrapper.text()).toContain('无可用导出权限');
  expect(request).not.toHaveBeenCalled();
});
it.each([401, 403])('revokes a rejected download and its history on HTTP %s', async (status) => {
  request.mockResolvedValue({ id: '37', type: 'ORDERS', status: 'SUCCEEDED', downloadUrl: '/api/v1/admin/exports/37/content', expiresAt: '2099-01-01T00:00:00Z' });
  const wrapper = render();
  await wrapper.findAll('button').find((button) => button.text() === '创建导出任务')!.trigger('click');
  await flushPromises();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status }));
  await wrapper.findAll('button').find((button) => button.text() === '下载 CSV')!.trigger('click');
  await flushPromises();
  expect(wrapper.find('[data-testid="export-task"]').exists()).toBe(false);
  if (status === 401) expect(session.get()).toBeNull();
  else expect(exportHistory()?.list()).toEqual([]);
});
