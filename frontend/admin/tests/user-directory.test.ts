// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createSession } from '../../shared/src/session';
import { session } from '../src/api/session';
import { useUserDirectory } from '../src/composables/useUserDirectory';
const api = vi.hoisted(() => ({
  listUsers: vi.fn(),
  updateUserStatus: vi.fn(),
  askReason: vi.fn(),
}));
vi.mock('../src/api/operations', () => ({ adminOperationsApi: api }));
vi.mock('../src/api/session', () => ({
  session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
}));
const row = {
  id: '5',
  nickname: '真实学员',
  username: null,
  phone: null,
  status: 'NORMAL' as const,
  version: 3,
  registeredAt: '2026-01-01T00:00:00Z',
  lastLoginAt: null,
};
const Harness = defineComponent({
  setup: () => useUserDirectory({ askReason: api.askReason }),
  template:
    '<div><p>{{error}}</p><p>{{message}}</p><p v-for="row in list" :key="row.id">{{row.nickname}} {{row.status}}</p><button @click="loadData">加载</button><button v-if="list[0]" :disabled="updating" @click="handleToggleStatus(list[0])">变更</button></div>',
});
const wrappers: VueWrapper[] = [];
function login(permissions = ['USER_MANAGE']) {
  session.set({
    accessToken: 'admin',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    admin: { id: 'a', loginName: '管理员', permissions },
  });
}
async function open() {
  const w = mount(Harness);
  wrappers.push(w);
  await w.findAll('button')[0].trigger('click');
  await flushPromises();
  return w;
}
beforeEach(() => {
  vi.resetAllMocks();
  session.clear();
  login();
  api.listUsers.mockResolvedValue({ items: [row], page: 1, pageSize: 20, total: 1 });
  api.askReason.mockResolvedValue('审查确认');
  api.updateUserStatus.mockResolvedValue({ ...row, status: 'BLOCKED', version: 4 });
});
afterEach(() => wrappers.splice(0).forEach((w) => w.unmount()));
it('requires USER_MANAGE and does not expose user records to unrelated operators', async () => {
  login(['ORDER_OPERATE']);
  const w = await open();
  expect(api.listUsers).not.toHaveBeenCalled();
  expect(w.text()).not.toContain('真实学员');
});
it('clears sensitive rows and discards old list responses after administrator replacement', async () => {
  let finish!: (value: unknown) => void;
  api.listUsers.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  const w = await open();
  login(['ORDER_OPERATE']);
  finish({ items: [row], total: 1 });
  await flushPromises();
  expect(w.text()).not.toContain('真实学员');
});
it('drops an old confirmation after session replacement without sending a write', async () => {
  let finish!: (value: string) => void;
  api.askReason.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  const w = await open();
  await w.findAll('button')[1].trigger('click');
  session.clear();
  login();
  finish('确认');
  await flushPromises();
  expect(api.updateUserStatus).not.toHaveBeenCalled();
});
it('keeps CLOSED accounts immutable and does not prompt to restore them', async () => {
  api.listUsers.mockResolvedValueOnce({
    items: [{ ...row, status: 'CLOSED' }],
    total: 1,
    page: 1,
    pageSize: 20,
  });
  const w = await open();
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(api.askReason).not.toHaveBeenCalled();
  expect(api.updateUserStatus).not.toHaveBeenCalled();
});
it('uses actual ID/version/reason, prevents duplicate confirmation and rereads after a successful write', async () => {
  let finish!: (value: string) => void;
  api.askReason.mockImplementationOnce(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  const w = await open();
  await w.findAll('button')[1].trigger('click');
  await w.findAll('button')[1].trigger('click');
  expect(api.askReason).toHaveBeenCalledTimes(1);
  finish(' 审查确认 ');
  await flushPromises();
  expect(api.updateUserStatus).toHaveBeenCalledWith('5', 'BLOCKED', '审查确认', 3);
  expect(api.listUsers).toHaveBeenCalledTimes(2);
  expect(w.text()).toContain('用户封禁成功');
});
it('validates reasons before mutation and does not report a failed write as success', async () => {
  const w = await open();
  api.askReason.mockResolvedValueOnce('   ');
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(api.updateUserStatus).not.toHaveBeenCalled();
  api.updateUserStatus.mockRejectedValueOnce(new Error('版本冲突'));
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(w.text()).toContain('版本冲突');
  expect(w.text()).not.toContain('成功');
});
