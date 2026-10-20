// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { session } from '../src/api/session';
import AnalyticsView from '../src/views/analytics/AnalyticsView.vue';
import AnalyticsPanel from '../src/components/AnalyticsPanel.vue';

const api = vi.hoisted(() => ({
  getAnalyticsOverview: vi.fn(),
  getAnalyticsTrend: vi.fn(),
  createExport: vi.fn(),
  getExportTask: vi.fn(),
  forgetExportTask: vi.fn(),
}));
vi.mock('../src/api/operations', () => ({
  adminOperationsApi: api,
  forgetExportTask: api.forgetExportTask,
}));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'mock', apiBaseUrl: '/api/v1' },
}));
const metadata = {
  period: { startDate: '2026-10-02', endDate: '2026-10-08', timezone: 'Asia/Shanghai' },
  generatedAt: '2026-10-08T02:00:00Z',
  environment: 'test',
  includesDevChannel: false,
};
const overview = {
  ...metadata,
  periodMetrics: {
    orderCreatedCount: 3,
    paidOrderCount: 1,
    paidUserCount: 1,
    receivedAmountFen: 0,
    refundedAmountFen: 2000,
    netCashAmountFen: -2000,
    courseSoldQuantity: 1,
    bookSoldQuantity: 0,
    membershipNewPurchaseCount: 2,
    membershipRenewalCount: 3,
    commissionEarnedFen: 1000,
    commissionReversedFen: 200,
    withdrawalSucceededFen: 500,
  },
  currentMetrics: {
    activeMemberCount: 9,
    waitingShipmentOrderCount: 2,
    pendingCommissionFen: 800,
    availableCommissionFen: 700,
    withdrawingFen: 600,
    debtFen: 100,
  },
};
const trend = {
  ...metadata,
  items: [
    {
      date: '2026-10-08',
      orderCreatedCount: 0,
      paidOrderCount: 0,
      receivedAmountFen: 0,
      refundedAmountFen: 2000,
      netCashAmountFen: -2000,
    },
  ],
};
const wrappers: VueWrapper[] = [];
function login(permissions = ['ANALYTICS_READ']) {
  session.set({ accessToken: 'test-analytics', expiresAt: '2099-01-01T00:00:00Z', permissions });
}
function render() {
  const wrapper = mount(AnalyticsView, {
    global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
  wrappers.push(wrapper);
  return wrapper;
}
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))!;
}

describe('analytics pages v1.1', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-07T17:00:00Z'));
    session.clear();
    api.getAnalyticsOverview.mockResolvedValue(overview);
    api.getAnalyticsTrend.mockResolvedValue(trend);
  });
  afterEach(() => {
    wrappers.splice(0).forEach((w) => w.unmount());
    session.clear();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('shows the compact overview and real trend without duplicating exports', async () => {
    login(['ANALYTICS_READ', 'ORDER_OPERATE']);
    const wrapper = mount(AnalyticsPanel, {
      props: { compact: true },
      global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    });
    wrappers.push(wrapper);
    await flushPromises();
    expect(api.getAnalyticsTrend).toHaveBeenCalledWith({
      startDate: '2026-10-02',
      endDate: '2026-10-08',
      includeDevChannel: false,
    });
    expect(wrapper.findAll('.summary-card')).toHaveLength(4);
    expect(wrapper.text()).toContain('销售额');
    expect(wrapper.text()).toContain('当前待发货订单');
    expect(wrapper.text()).toContain('¥20.00');
    expect(wrapper.find('ol[aria-label="每日实收金额"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('最近7天暂无实收金额');
    expect(wrapper.text()).toContain('查看订单');
    expect(wrapper.text()).not.toContain('导出报表');
  });

  it('requests both endpoints for Shanghai recent seven days using the backend permission code', async () => {
    login();
    const wrapper = render();
    await flushPromises();
    const query = { startDate: '2026-10-02', endDate: '2026-10-08', includeDevChannel: false };
    expect(api.getAnalyticsOverview).toHaveBeenCalledWith(query);
    expect(api.getAnalyticsTrend).toHaveBeenCalledWith(query);
    for (const label of [
      '创建订单',
      '支付订单',
      '区间新购会员',
      '区间续费会员',
      '当前有效会员',
      '待结算佣金',
      '可提现佣金',
      '提现中',
      '已成功提现',
      '欠款',
    ])
      expect(wrapper.text()).toContain(label);
    expect(wrapper.text()).toContain('-20.00');
    expect(wrapper.text()).toContain('测试环境');
    expect(wrapper.text()).not.toContain('NaN');
    expect(wrapper.text()).not.toContain('Top 4');
  });

  it('blocks requests without ANALYTICS_READ', async () => {
    login(['ORDER_OPERATE']);
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('无权限访问');
    expect(api.getAnalyticsOverview).not.toHaveBeenCalled();
    expect(api.getAnalyticsTrend).not.toHaveBeenCalled();
  });

  it('shows loading without fake zero metrics, then a visible error with working retry', async () => {
    login();
    let reject!: (e: Error) => void;
    api.getAnalyticsOverview.mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail;
        }),
    );
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('加载');
    expect(wrapper.find('[data-testid="period-metrics"]').exists()).toBe(false);
    reject(new Error('统计服务暂不可用'));
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('统计服务暂不可用');
    await button(wrapper, '重试').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="period-metrics"]').exists()).toBe(true);
  });

  it('renders genuine zero transactions as an empty period without hiding current balances', async () => {
    login();
    api.getAnalyticsOverview.mockResolvedValue({
      ...overview,
      periodMetrics: Object.fromEntries(Object.keys(overview.periodMetrics).map((key) => [key, 0])),
    });
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('所选区间暂无交易');
    expect(wrapper.text()).toContain('当前有效会员');
    expect(wrapper.text()).toContain('7.00');
  });

  it('rejects cleared or oversized date filters without new network requests', async () => {
    login();
    const wrapper = render();
    await flushPromises();
    wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', null);
    await button(wrapper, '查询').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('请选择完整');
    expect(api.getAnalyticsOverview).toHaveBeenCalledTimes(1);
  });

  it('clears protected metrics when the session changes during a request', async () => {
    login();
    let resolve!: (v: unknown) => void;
    api.getAnalyticsOverview.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const wrapper = render();
    await flushPromises();
    session.clear();
    resolve(overview);
    await flushPromises();
    expect(wrapper.find('[data-testid="period-metrics"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('无权限访问');
  });

  it('creates an export for the selected range and can query its eventual result', async () => {
    login(['ANALYTICS_READ', 'EXPORT_READ']);
    api.createExport.mockResolvedValue({
      id: 'exp-1',
      type: 'ANALYTICS',
      status: 'REQUESTED',
      downloadUrl: null,
    });
    api.getExportTask.mockResolvedValue({
      id: 'exp-1',
      type: 'ANALYTICS',
      status: 'SUCCEEDED',
      downloadUrl: '/api/v1/admin/exports/exp-1/content',
      expiresAt: '2099-01-01T00:00:00Z',
    });
    const wrapper = render();
    await flushPromises();
    await button(wrapper, '导出').trigger('click');
    await flushPromises();
    expect(api.createExport).toHaveBeenCalledWith('ANALYTICS', {
      startDate: '2026-10-02',
      endDate: '2026-10-08',
    });
    await button(wrapper, '刷新任务').trigger('click');
    await flushPromises();
    expect(api.getExportTask).toHaveBeenCalledWith('exp-1');
    expect(wrapper.find('a[download]').attributes('href')).toContain(
      '/api/v1/admin/exports/exp-1/content',
    );
  });
  it.each([true, false])(
    'discards an old download started while a new export is pending (success=%s)',
    async (success) => {
      login(['ANALYTICS_READ', 'EXPORT_READ']);
      let resolveExport!: (value: unknown) => void;
      api.createExport
        .mockResolvedValueOnce({
          id: 'old',
          type: 'ANALYTICS',
          status: 'SUCCEEDED',
          downloadUrl: '/api/v1/admin/exports/old/content',
          expiresAt: '2099-01-01T00:00:00Z',
        })
        .mockImplementationOnce(
          () =>
            new Promise((done) => {
              resolveExport = done;
            }),
        );
      let resolveDownload!: (value: unknown) => void;
      vi.stubGlobal(
        'fetch',
        vi.fn().mockReturnValue(
          new Promise((done) => {
            resolveDownload = done;
          }),
        ),
      );
      const createObjectURL = vi.fn(() => 'blob:old');
      URL.createObjectURL = createObjectURL;
      const wrapper = render();
      await flushPromises();
      await button(wrapper, '导出报表').trigger('click');
      await flushPromises();
      await button(wrapper, '导出报表').trigger('click');
      await flushPromises();
      await wrapper.find('a[download]').trigger('click');
      resolveExport({ id: 'new', type: 'ANALYTICS', status: 'REQUESTED', downloadUrl: null });
      await flushPromises();
      resolveDownload(
        success
          ? { ok: true, blob: async () => new Blob(['old csv']) }
          : { ok: false, status: 403 },
      );
      await flushPromises();
      expect(wrapper.text()).toContain('任务 new');
      expect(createObjectURL).not.toHaveBeenCalled();
      expect(api.forgetExportTask).not.toHaveBeenCalled();
    },
  );
  it('discards an old download after a newer export task replaces it', async () => {
    login(['ANALYTICS_READ', 'EXPORT_READ']);
    api.createExport
      .mockResolvedValueOnce({
        id: 'old',
        type: 'ANALYTICS',
        status: 'SUCCEEDED',
        downloadUrl: '/api/v1/admin/exports/old/content',
        expiresAt: '2099-01-01T00:00:00Z',
      })
      .mockResolvedValueOnce({
        id: 'new',
        type: 'ANALYTICS',
        status: 'REQUESTED',
        downloadUrl: null,
      });
    let resolve!: (value: unknown) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockReturnValue(
        new Promise((done) => {
          resolve = done;
        }),
      ),
    );
    const createObjectURL = vi.fn(() => 'blob:old');
    URL.createObjectURL = createObjectURL;
    const wrapper = render();
    await flushPromises();
    await button(wrapper, '导出报表').trigger('click');
    await flushPromises();
    await wrapper.find('a[download]').trigger('click');
    await button(wrapper, '导出报表').trigger('click');
    await flushPromises();
    resolve({ ok: true, blob: async () => new Blob(['old csv']) });
    await flushPromises();
    expect(wrapper.text()).toContain('任务 new');
    expect(createObjectURL).not.toHaveBeenCalled();
  });
});
