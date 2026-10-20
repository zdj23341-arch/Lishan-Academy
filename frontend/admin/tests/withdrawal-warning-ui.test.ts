// @vitest-environment jsdom
import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import WithdrawalList from '../src/views/distribution/WithdrawalList.vue';
import { session } from '../src/api/session';
const api = vi.hoisted(() => ({ listWithdrawals: vi.fn() }));
vi.mock('../src/api/distribution', () => ({ adminDistributionApi: api }));
vi.mock('../src/api/operations', () => ({ adminOperationsApi: {} }));
vi.mock('../src/api/environment', () => ({
  environment: { provider: 'mock', mode: 'test', apiBaseUrl: '/api/v1' },
}));

it('keeps channel errors and fund warnings visible while diagnostic details are closed', async () => {
  api.listWithdrawals.mockResolvedValue({
    items: [
      {
        id: 'warning',
        withdrawalNo: 'test-warning',
        distributorId: 'test-distributor',
        amountFen: 500,
        status: 'FAILED',
        createdAt: '2026-10-10T01:00:00Z',
        alerted: true,
        errorCode: 'CHANNEL_FAILURE',
        outBillNo: 'technical-bill',
        reconciliation: { status: 'MISMATCH', observedPaidOutFen: 500, accountedPaidOutFen: 0 },
      },
    ],
    total: 1,
    page: 1,
    pageSize: 20,
  });
  session.set({
    accessToken: 'test-only',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: ['FINANCE_OPERATE'],
  });
  const w = mount(WithdrawalList, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { RouterLink: true } },
  });
  try {
    await flushPromises();
    const alerts = w.findAll('[role="alert"]');
    expect(alerts.every((item) => item.isVisible())).toBe(true);
    expect(alerts.map((item) => item.text()).join(' ')).toContain('CHANNEL_FAILURE');
    expect(alerts.map((item) => item.text()).join(' ')).toContain('已告警');
    expect(w.get('[role="status"]').text()).toContain('资金对账：不一致');
    expect(w.get('td details').attributes('open')).toBeUndefined();
    expect(w.text()).toContain('转账失败');
  } finally {
    w.unmount();
    session.clear();
  }
});
