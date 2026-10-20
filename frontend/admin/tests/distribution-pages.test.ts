// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { session } from '../src/api/session';
import { createPinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import DistributorList from '../src/views/distribution/DistributorList.vue';
import WithdrawalList from '../src/views/distribution/WithdrawalList.vue';

const distApiMock = vi.hoisted(() => ({
  listDistributors: vi.fn(),
  assignDistributor: vi.fn(),
  updateDistributorStatus: vi.fn(),
  getProductRules: vi.fn(),
  updateProductRules: vi.fn(),
  listWithdrawals: vi.fn(),
  refreshWithdrawal: vi.fn(),
}));

const opsApiMock = vi.hoisted(() => ({
  getAnalyticsOverview: vi.fn(),
  getAnalyticsTrend: vi.fn(),
  listUsers: vi.fn(),
  updateUserStatus: vi.fn(),
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  listAuditLogs: vi.fn(),
  createExport: vi.fn(),
  getExportTask: vi.fn(),
}));

vi.mock('../src/api/distribution', () => ({
  adminDistributionApi: distApiMock,
}));

vi.mock('../src/api/operations', () => ({
  adminOperationsApi: opsApiMock,
}));

vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api' },
}));

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div>home</div>' } }],
});

function login(
  permissions = ['DISTRIBUTION_OPERATOR', 'FINANCE_OPERATOR', 'ANALYST', 'SUPER_ADMIN'],
) {
  session.set({
    accessToken: 'admin-token',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions,
    user: { id: 'admin-1', nickname: '系统超级管理员', distributor: false },
  });
}

describe('Admin Distribution & Analytics Pages', () => {
  beforeEach(() => {
    session.clear();
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  it('renders distributor list in DistributorList view', async () => {
    login(['DISTRIBUTION_OPERATOR']);
    distApiMock.listDistributors.mockResolvedValue({
      items: [
        {
          id: 'dist-1',
          userId: 'u1',
          username: 'agent_zhang',
          nickname: '张顾问',
          phone: '13812345678',
          status: 'ACTIVE',
          createdAt: '2026-09-01T00:00:00Z',
          version: 1,
          totalCommissionFen: 42750,
          withdrawnFen: 35000,
        },
      ],
      page: 1,
      pageSize: 20,
      total: 1,
    });

    const wrapper = mount(DistributorList, {
      global: {
        plugins: [createPinia(), router, ElementPlus],
      },
    });

    await flushPromises();
    expect(distApiMock.listDistributors).toHaveBeenCalled();
    expect(wrapper.text()).toContain('张顾问');
    expect(wrapper.text()).toContain('正常推广');
  });

  it('renders the real DistributorView without inventing missing financial totals or names', async () => {
    login(['DISTRIBUTION_OPERATE']);
    distApiMock.listDistributors.mockResolvedValue({
      items: [
        {
          id: '9',
          userId: '12345',
          status: 'ACTIVE',
          riskBlocked: false,
          receiptReady: false,
          reason: '指派',
          version: 2,
        },
      ],
      total: 1,
    });
    const wrapper = mount(DistributorList, {
      global: { plugins: [createPinia(), router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.text()).toContain('12345');
    expect(wrapper.text()).toContain('未绑定');
    expect(wrapper.text()).not.toContain('¥0.00');
    wrapper.unmount();
  });

  it('triggers safe idempotent refresh on withdrawal list', async () => {
    login(['FINANCE_OPERATOR']);
    distApiMock.listWithdrawals.mockResolvedValue({
      items: [
        {
          id: 'wd-1',
          withdrawalNo: 'WN202610070001',
          distributorId: 'dist-1',
          distributorName: '张顾问',
          phone: '138****5678',
          amountFen: 5000,
          status: 'PROCESSING',
          channelTransferNo: 'WXTRwd-1',
          createdAt: '2026-10-07T10:00:00Z',
          successAt: null,
          failReason: null,
          confirmation: null,
        },
      ],
      page: 1,
      pageSize: 20,
      total: 1,
    });
    distApiMock.refreshWithdrawal.mockResolvedValue({
      id: 'wd-1',
      withdrawalNo: 'WN202610070001',
      status: 'SUCCEEDED',
      channelTransferNo: 'WXTRwd-1',
    });

    const wrapper = mount(WithdrawalList, {
      global: {
        plugins: [createPinia(), router, ElementPlus],
      },
    });

    await flushPromises();
    expect(wrapper.text()).toContain('WN202610070001');
    expect(wrapper.text()).toContain('微信处理中');

    // 触发重新查单
    const refreshBtn = wrapper.findAll('button').find((b) => b.text().includes('重新查单'));
    expect(refreshBtn?.exists()).toBe(true);
    await refreshBtn!.trigger('click');
    await flushPromises();

    expect(distApiMock.refreshWithdrawal).toHaveBeenCalledWith('wd-1');
  });

  it('opens a product selector before fetching rules and refuses saving an edited unloaded target', async () => {
    login(['DISTRIBUTION_OPERATE']);
    distApiMock.listDistributors.mockResolvedValue({ items: [], total: 0 });
    distApiMock.getProductRules.mockResolvedValue({
      productId: '123',
      version: 7,
      rules: [{ distributorId: '9', distributorName: '顾问9', rateBps: 1500, enabled: true }],
    });
    const wrapper = mount(DistributorList, {
      global: { plugins: [createPinia(), router, ElementPlus] },
    });
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('配置商品佣金规则'))!
      .trigger('click');
    await flushPromises();
    expect(distApiMock.getProductRules).not.toHaveBeenCalled();
    const input = wrapper.find('input[placeholder="请输入真实商品ID"]');
    expect(input.exists()).toBe(true);
    await input.setValue('123');
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载商品规则'))!
      .trigger('click');
    await flushPromises();
    expect(distApiMock.getProductRules).toHaveBeenCalledWith('123');
    await input.setValue('456');
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('保存生效'))!
      .trigger('click');
    await flushPromises();
    expect(distApiMock.updateProductRules).not.toHaveBeenCalled();
    await input.setValue('123');
    distApiMock.updateProductRules.mockResolvedValue({ productId: '123', version: 8, rules: [] });
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('保存生效'))!
      .trigger('click');
    await flushPromises();
    expect(distApiMock.updateProductRules).toHaveBeenCalledWith(
      '123',
      expect.any(Array),
      7,
      expect.any(String),
    );
    wrapper.unmount();
  });

  it('shows real finance diagnostics and hides unsupported list filters', async () => {
    login(['FINANCE_OPERATE']);
    distApiMock.listWithdrawals.mockResolvedValue({
      items: [
        {
          id: '200',
          withdrawalNo: 'OUT-200',
          outBillNo: 'OUT-200',
          distributorId: 'DIST-9',
          channel: 'WECHAT',
          status: 'UNKNOWN',
          amountFen: 5000,
          providerState: 'CHANNEL_UNKNOWN',
          errorCode: 'BANK_ERROR',
          nextQueryAt: '2026-10-09T00:00:00Z',
          alerted: true,
          reconciliation: {
            status: 'MISMATCH',
            observedPaidOutFen: 5000,
            accountedPaidOutFen: 0,
            lastQueryState: 'PENDING_QUERY',
          },
        },
      ],
      total: 1,
    });
    const wrapper = mount(WithdrawalList, {
      global: { plugins: [createPinia(), router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.text()).toContain('DIST-9');
    expect(wrapper.text()).toContain('BANK_ERROR');
    expect(wrapper.text()).toContain('CHANNEL_UNKNOWN');
    expect(wrapper.text()).toContain('不一致');
    expect(wrapper.text()).toContain('50.00');
    expect(wrapper.find('input[placeholder="搜索提现号 / 分销员姓名"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('微信转账单号');
    wrapper.unmount();
  });
});
