// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { referralManager } from '@jxsc/shared';
import pageConfig from '../src/pages.json';

vi.hoisted(() => {
  (globalThis as any).uni = {
    showToast: vi.fn(),
    showModal: vi.fn(),
    showLoading: vi.fn(),
    hideLoading: vi.fn(),
    getStorageSync: vi.fn(),
    setStorageSync: vi.fn(),
    removeStorageSync: vi.fn(),
    navigateTo: vi.fn(),
    redirectTo: vi.fn(),
  };
});

vi.mock('../src/api/environment', () => ({
  environment: {
    mode: 'test',
    provider: 'mock',
    apiBaseUrl: '/api/v1',
    uploadBaseUrl: 'http://localhost/upload',
    imageBaseUrl: 'http://localhost/images',
    videoBaseUrl: 'http://localhost/videos',
  },
}));

vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  const values = new Map<string, string>();
  const sessionStorage = {
    get: (k: string) => values.get(k) ?? null,
    set: (k: string, v: string) => {
      values.set(k, v);
    },
    remove: (k: string) => {
      values.delete(k);
    },
  };
  return {
    session: createSession(sessionStorage),
    sessionStorage,
    operationNamespace: 'test-distribution-pages',
  };
});

const hooks = vi.hoisted(() => ({
  show: [] as Array<() => void>,
  hide: [] as Array<() => void>,
  request: vi.fn(),
}));

vi.mock('@dcloudio/uni-app', () => ({
  onShow: (cb: () => void) => hooks.show.push(cb),
  onHide: (cb: () => void) => hooks.hide.push(cb),
  onLoad: vi.fn(),
  onShareAppMessage: vi.fn(),
}));

import DistributionIndex from '../src/pages/distribution/index.vue';
import WithdrawalPage from '../src/pages/distribution/withdrawal.vue';
import { wechatReceiptConfirmationAdapter } from '../src/api/distribution';
import { session, sessionStorage, operationNamespace } from '../src/api/session';
import LoginPage from '../src/pages/login/index.vue';

describe('Distribution Miniprogram Page & Adapters', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hooks.show.length = 0;
    hooks.hide.length = 0;
    referralManager.clear();
    referralManager.bind({ session, storage: sessionStorage, namespace: operationNamespace });
    sessionStorage.remove('jxsc.withdrawal.v2:["test-distribution-pages","A"]');
    session.set({
      accessToken: 'A',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'A', nickname: 'A', distributor: true },
    });
  });

  afterEach(() => {
    referralManager.clear();
  });

  it.each([false, true])(
    'shows receipt result %s using the queried original record',
    async (confirmed) => {
      const storageKey = 'jxsc.withdrawal.v2:["test-distribution-pages","A"]';
      sessionStorage.set(
        storageKey,
        JSON.stringify({ key: 'receipt-key', amountFen: 1000, resourceId: 'receipt-old' }),
      );
      const base = {
        id: 'receipt-old',
        withdrawalNo: 'OUT-OLD',
        amountFen: 1000,
        status: 'USER_ACTION_REQUIRED',
        createdAt: '2026-10-14T00:00:00Z',
        confirmation: { merchantId: 'mch', appId: 'wx-own', packageInfo: 'old-package' },
      };
      let returned = false;
      vi.stubGlobal('wx', {
        requestMerchantTransfer: ({ success, fail }: any) => {
          returned = true;
          if (confirmed) success();
          else fail();
        },
      });
      const { client } = await import('../src/api/client');
      const request = vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
        if (path === '/distribution/summary')
          return {
            distributorId: 'A',
            receiptReady: true,
            availableFen: 1000,
            pendingFen: 0,
            withdrawingFen: 0,
            paidOutFen: 0,
            debtFen: 0,
            withdrawalEnabled: true,
            disabledReason: null,
            limits: {},
          };
        if (path === '/operations/receipt-key')
          return { state: 'SUCCEEDED', resourceId: 'receipt-old' };
        if (path === '/distribution/withdrawals/receipt-old') return base;
        if (path === '/distribution/withdrawals/receipt-old/refresh') {
          if (!returned) throw new Error('Confirmation has not returned');
          return base;
        }
        throw new Error(`Unexpected request: ${path}`);
      });
      const page = mount(WithdrawalPage);
      try {
        hooks.show.forEach((cb) => cb());
        await flushPromises();
        await page
          .findAll('button')
          .find((b) => b.text() === '立即确认收款')!
          .trigger('click');
        await flushPromises();
        expect(page.text()).not.toContain('已完成收款确认');
        if (confirmed) expect(page.get('.success-card').text()).toContain('待确认收款');
        else {
          expect(page.find('.success-card').exists()).toBe(false);
          expect(page.get('[role="alert"]').text()).toContain('收款确认未完成');
        }
        expect(page.text()).toContain('OUT-OLD');
        expect(page.text()).toContain('待确认收款');
        expect(page.text()).toContain('立即确认收款');
        expect(sessionStorage.get(storageKey)).not.toBeNull();
        expect(request).toHaveBeenCalledWith('/distribution/withdrawals/receipt-old/refresh', {
          method: 'POST',
        });
        expect(request.mock.calls.filter(([p]) => p === '/distribution/withdrawals')).toHaveLength(
          0,
        );
      } finally {
        page.unmount();
        vi.unstubAllGlobals();
      }
    },
  );

  it('removes a stale recovery error after retrying the unchanged amount and key', async () => {
    sessionStorage.set(
      'jxsc.withdrawal.v2:["test-distribution-pages","A"]',
      JSON.stringify({ key: 'lost-key', amountFen: 1000, resourceId: null }),
    );
    const { client } = await import('../src/api/client');
    const request = vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary')
        return {
          distributorId: 'A',
          availableFen: 1000,
          pendingFen: 0,
          withdrawingFen: 0,
          paidOutFen: 0,
          debtFen: 0,
          withdrawalEnabled: true,
          disabledReason: null,
          limits: {},
        };
      if (path === '/operations/lost-key') return { state: 'PROCESSING', resourceId: null };
      if (path === '/distribution/withdrawals')
        return {
          id: 'old',
          withdrawalNo: 'OUT-OLD',
          amountFen: 1000,
          status: 'USER_ACTION_REQUIRED',
          createdAt: '2026-10-14T00:00:00Z',
          confirmation: null,
        };
      throw new Error(`Unexpected request: ${path}`);
    });
    const page = mount(WithdrawalPage);
    try {
      hooks.show.forEach((cb) => cb());
      await flushPromises();
      expect(page.get('[role="alert"]').text()).toContain('尚未确认');
      await page.get('input').setValue('9');
      await page
        .findAll('button')
        .find((b) => b.text() === '按原金额重试')!
        .trigger('click');
      await flushPromises();
      expect(page.find('[role="alert"]').exists()).toBe(false);
      expect(page.get('.success-card').text()).toContain('待确认收款');
      expect(request).toHaveBeenCalledWith('/distribution/withdrawals', {
        method: 'POST',
        body: { amountFen: 1000 },
        idempotencyKey: 'lost-key',
      });
    } finally {
      page.unmount();
    }
  });

  it('renders distribution summary and commission list on show', async () => {
    const mockSummary = {
      distributorId: 'dist-test-01',
      status: 'ACTIVE' as const,
      pendingFen: 2850,
      availableFen: 14800,
      withdrawingFen: 3200,
      paidOutFen: 35000,
      debtFen: 0,
      withdrawalEnabled: true,
      disabledReason: null,
      limits: {
        minAmountFen: 100,
        maxAmountFen: 500000,
        dailyCountLimit: 5,
        dailyCountRemaining: 5,
      },
    };

    const mockCommissions = {
      items: [
        {
          id: 'comm-1',
          orderNo: 'SO2026100700192',
          productId: 'prod-1',
          goodsTitle: '初高中考点精编',
          orderPaidAmountFen: 4900,
          rateBps: 1500,
          commissionFen: 735,
          reversedFen: 0,
          remainingFen: 735,
          status: 'AVAILABLE' as const,
          createdAt: '2026-10-01T10:00:00Z',
          settledAt: '2026-10-05T10:00:00Z',
        },
      ],
      page: 1,
      pageSize: 20,
      total: 1,
    };

    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary') return mockSummary;
      if (path === '/distribution/commissions') return mockCommissions;
      throw new Error(`Unexpected path: ${path}`);
    });

    const wrapper = mount(DistributionIndex);
    for (const cb of hooks.show) cb();
    await flushPromises();

    expect(wrapper.text()).toContain('初高中考点精编');
    expect(wrapper.text()).toContain('已入账可提现');
    wrapper.unmount();
  });

  it('shows a precise Chinese message when the account has no distributor profile', async () => {
    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockRejectedValue(
      Object.assign(new Error('当前账号尚未开通分销资格'), {
        code: 'DISTRIBUTOR_NOT_ENABLED',
      }),
    );
    const wrapper = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(wrapper.text()).toContain('当前账号尚未开通分销资格');
    expect(wrapper.text()).not.toContain('暂未查到');
    wrapper.unmount();
  });

  it('does not display a fabricated zero balance when withdrawal conditions fail to load', async () => {
    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockRejectedValue(new Error('提现条件读取失败'));
    const page = mount(WithdrawalPage);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(page.find('.balance-meta').exists()).toBe(false);
    expect(page.text()).toContain('余额暂不可用');
    expect(page.get('.submit-btn').attributes('disabled')).toBeDefined();
    expect(page.findAll('button').some((b) => b.text() === '重新读取提现条件')).toBe(true);
    page.unmount();
  });

  it('explains why a bound but disabled distributor cannot withdraw', async () => {
    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockResolvedValue({
      distributorId: 'A',
      status: 'DISABLED',
      receiptReady: true,
      pendingFen: 0,
      availableFen: 1000,
      withdrawingFen: 0,
      paidOutFen: 0,
      debtFen: 0,
      limits: {},
      withdrawalEnabled: false,
      disabledReason: 'DISTRIBUTOR_DISABLED',
    });
    const page = mount(WithdrawalPage);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(page.get('.receipt-status').text()).toBe('已绑定');
    expect(page.text()).toContain('分销档案已停用，请联系客服');
    expect(page.get('.submit-btn').attributes('disabled')).toBeDefined();
    page.unmount();
  });

  it.each([
    [true, false, '已绑定'],
    [false, false, '未绑定'],
    [undefined, true, '绑定状态待确认'],
  ])(
    'shows receipt readiness %s independently of withdrawal capability %s',
    async (receiptReady, withdrawalEnabled, label) => {
      const { client } = await import('../src/api/client');
      vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
        if (path === '/distribution/summary')
          return {
            distributorId: 'A',
            availableFen: 1000,
            limits: {},
            receiptReady,
            withdrawalEnabled,
          };
        return { items: [], total: 0 };
      });
      const center = mount(DistributionIndex);
      hooks.show.forEach((cb) => cb());
      await flushPromises();
      expect(center.get('[data-testid="receipt-status"]').text()).toBe(label);
      center.unmount();
      const page = mount(WithdrawalPage);
      hooks.show.at(-1)?.();
      await flushPromises();
      expect(page.get('.receipt-status').text()).toBe(label);
      page.unmount();
    },
  );

  it('retains the entered name after binding fails and prevents disabled accounts from binding', async () => {
    const { client } = await import('../src/api/client');
    let disabled = false;
    const request = vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary')
        return {
          distributorId: 'A',
          status: disabled ? 'DISABLED' : 'ACTIVE',
          availableFen: 1000,
          limits: {},
          receiptReady: false,
          withdrawalEnabled: false,
          disabledReason: disabled ? 'DISTRIBUTOR_DISABLED' : 'WITHDRAWAL_NOT_READY',
        };
      if (path === '/distribution/receipt-profile') throw new Error('绑定渠道暂不可用');
      return { items: [], total: 0 };
    });
    const wrapper = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    await wrapper.get('[data-testid="receipt-real-name"]').setValue('李明');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '绑定本人微信收款身份')!
      .trigger('click');
    await flushPromises();
    expect(
      (wrapper.get('[data-testid="receipt-real-name"]').element as HTMLInputElement).value,
    ).toBe('李明');
    disabled = true;
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '绑定本人微信收款身份')!
        .attributes('disabled'),
    ).toBeDefined();
    expect(
      request.mock.calls.filter(([path]) => path === '/distribution/receipt-profile'),
    ).toHaveLength(1);
    wrapper.unmount();
  });

  it('shows claim failures after login and lets the user discard the token before continuing', async () => {
    session.clear();
    referralManager.saveAttribution('product', 'guest-token', '2099-01-01T00:00:00Z');
    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/config/public')
        return {
          agreementLinks: {
            terms: { title: '用户协议', version: 'test-1', articleId: '11' },
            privacy: { title: '隐私政策', version: 'test-1', articleId: '12' },
          },
        };
      if (path.startsWith('/auth/'))
        return {
          accessToken: 'B',
          expiresAt: '2099-01-01T00:00:00Z',
          user: { id: 'B', nickname: 'B', distributor: false },
        };
      throw new Error('推荐认领网络失败');
    });
    (globalThis as any).uni.login = vi.fn().mockResolvedValue({ code: 'wx-code' });
    const wrapper = mount(LoginPage, { global: { plugins: [createPinia()] } });
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    await wrapper.get('[role="checkbox"]').trigger('click');
    await wrapper.get('.ls-button--primary').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('推荐认领网络失败');
    expect(wrapper.text()).toContain('重试认领推荐信息');
    expect(referralManager.getValidTokenForProduct('product')).toBeNull();
    expect(uni.redirectTo).not.toHaveBeenCalled();
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '不使用推荐继续')!
      .trigger('click');
    expect(referralManager.getPendingAttribution()).toBeNull();
    expect(uni.redirectTo).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('localizes disabled reasons and all withdrawal states while prioritizing Chinese channel reasons', async () => {
    const { client } = await import('../src/api/client');
    vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary')
        return {
          distributorId: 'A',
          status: 'ACTIVE',
          pendingFen: 0,
          availableFen: 1000,
          withdrawingFen: 0,
          paidOutFen: 0,
          debtFen: 0,
          withdrawalEnabled: false,
          disabledReason: 'WITHDRAWAL_NOT_READY',
          limits: {},
        };
      if (path === '/distribution/commissions')
        return {
          items: [
            {
              id: 'c',
              orderId: 'ORD123',
              goodsTitle: '语文图书',
              earnedFen: 100,
              rateBps: 1000,
              reversedFen: 0,
              remainingFen: 100,
              state: 'NEW_COMMISSION_STATE',
            },
          ],
          total: 1,
        };
      return {
        items: [
          {
            id: 'failed',
            withdrawalNo: 'OUT-1',
            amountFen: 100,
            status: 'FAILED',
            createdAt: '2026-10-08T00:00:00Z',
            errorCode: 'CHANNEL_ERROR',
            failReason: '收款账户异常，请联系客服',
          },
          {
            id: 'unknown',
            withdrawalNo: 'OUT-2',
            amountFen: 100,
            status: 'UNKNOWN',
            createdAt: '2026-10-08T00:00:00Z',
          },
          {
            id: 'closed',
            withdrawalNo: 'OUT-3',
            amountFen: 100,
            status: 'CLOSED',
            createdAt: '2026-10-08T00:00:00Z',
          },
        ],
        total: 3,
      };
    });
    const wrapper = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(wrapper.text()).toContain('收款身份或转账渠道尚未就绪');
    expect(wrapper.text()).toContain('提现失败');
    expect(wrapper.text()).toContain('结果待确认');
    expect(wrapper.text()).toContain('已关闭');
    expect(wrapper.text()).toContain('佣金状态待确认');
    expect(wrapper.text()).toContain('收款账户异常，请联系客服');
    for (const code of [
      'WITHDRAWAL_NOT_READY',
      'CHANNEL_ERROR',
      'NEW_COMMISSION_STATE',
      'FAILED',
      'UNKNOWN',
      'CLOSED',
    ])
      expect(wrapper.text()).not.toContain(code);
    expect(wrapper.text()).toContain('OUT-1');
    wrapper.unmount();
  });

  it('clears private balances and lists immediately on logout and ignores late old-account responses', async () => {
    const { client } = await import('../src/api/client');
    let resolveSummary!: (value: any) => void;
    vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary')
        return new Promise((resolve) => {
          resolveSummary = resolve;
        });
      return {
        items: [{ id: 'private', goodsTitle: 'A的私有佣金', earnedFen: 100, rateBps: 1500 }],
        total: 1,
      };
    });
    const wrapper = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    session.clear();
    resolveSummary({
      distributorId: 'A',
      availableFen: 999999,
      limits: {},
      withdrawalEnabled: true,
    });
    await flushPromises();
    expect(wrapper.text()).not.toContain('A的私有佣金');
    expect(wrapper.text()).not.toContain('认证特约分销顾问');
    expect(wrapper.text()).toContain('请先登录');
    wrapper.unmount();
  });

  it('explicitly binds own real receipt profile and refreshes summary', async () => {
    const { client } = await import('../src/api/client');
    const request = vi.spyOn(client, 'request').mockImplementation(async (path: string) => {
      if (path === '/distribution/summary')
        return {
          distributorId: 'A',
          availableFen: 1000,
          limits: {},
          withdrawalEnabled: false,
          disabledReason: 'WITHDRAWAL_NOT_READY',
        };
      if (path === '/distribution/receipt-profile') return { ready: true, nameProvided: true };
      return { items: [], total: 0 };
    });
    const wrapper = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    const button = wrapper.findAll('button').find((b) => b.text() === '绑定本人微信收款身份');
    expect(button).toBeDefined();
    await wrapper.find('[data-testid="receipt-real-name"]').setValue('李明');
    await button!.trigger('click');
    await flushPromises();
    expect(request).toHaveBeenCalledWith('/distribution/receipt-profile', {
      method: 'POST',
      body: { realName: '李明' },
    });
    expect(request.mock.calls.filter(([path]) => path === '/distribution/summary').length).toBe(2);
    wrapper.unmount();
  });

  it('reacts to polling transitions and retrieves the next page of withdrawal history', async () => {
    vi.useFakeTimers();
    const { client } = await import('../src/api/client');
    const base = {
      id: 'wd-1',
      withdrawalNo: 'OUT-1',
      amountFen: 1000,
      createdAt: '2026-10-08T00:00:00Z',
      status: 'PROCESSING',
    };
    let created = false;
    const request = vi
      .spyOn(client, 'request')
      .mockImplementation(
        async (path: string, options?: { method?: string; query?: { page?: number } }) => {
          if (path === '/distribution/summary')
            return {
              distributorId: 'A',
              pendingFen: 0,
              availableFen: 1000,
              withdrawingFen: 0,
              paidOutFen: 0,
              debtFen: 0,
              withdrawalEnabled: true,
              limits: { minFen: 100 },
            };
          if (path === '/distribution/commissions') return { items: [], total: 0 };
          if (path === '/distribution/withdrawals' && options?.method === 'POST') {
            created = true;
            return base;
          }
          if (path === '/distribution/withdrawals')
            return {
              items:
                options?.query?.page === 2
                  ? [{ ...base, id: 'old-page-2', withdrawalNo: '历史第11笔' }]
                  : created
                    ? [base]
                    : [],
              total: 11,
            };
          if (path === '/distribution/withdrawals/wd-1/refresh')
            return { ...base, status: 'USER_ACTION_REQUIRED' };
          throw new Error(path);
        },
      );
    const center = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    await center
      .findAll('button')
      .find((b) => b.text() === '申请提现')!
      .trigger('click');
    const wrapper = mount(WithdrawalPage);
    hooks.show.at(-1)?.();
    await flushPromises();
    await wrapper.find('[data-testid="withdrawal-amount"]').setValue('10');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '确认提现')!
      .trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('立即确认收款');
    await vi.advanceTimersByTimeAsync(3000);
    await flushPromises();
    expect(wrapper.text()).toContain('当前提现单');
    await center
      .findAll('button')
      .find((b) => b.text() === '下一页提现')!
      .trigger('click');
    await flushPromises();
    expect(request).toHaveBeenCalledWith('/distribution/withdrawals', {
      method: 'GET',
      query: { page: 2, pageSize: 10 },
    });
    expect(center.text()).toContain('历史第11笔');
    center.unmount();
    wrapper.unmount();
    vi.useRealTimers();
  });

  it('opens the independent withdrawal screen and keeps its idempotent controller flow', async () => {
    const { client } = await import('../src/api/client');
    const request = vi
      .spyOn(client, 'request')
      .mockImplementation(async (path: string, options?: any) => {
        if (path === '/distribution/summary')
          return {
            distributorId: 'A',
            availableFen: 1000,
            withdrawingFen: 0,
            pendingFen: 0,
            paidOutFen: 0,
            debtFen: 0,
            withdrawalEnabled: true,
            limits: { minFen: 100 },
          };
        if (path === '/distribution/commissions') return { items: [], total: 0 };
        if (path === '/distribution/withdrawals' && options?.method === 'POST')
          return {
            id: 'wd-new',
            withdrawalNo: 'OUT-NEW',
            amountFen: 1000,
            status: 'PROCESSING',
            createdAt: '2026-10-11T00:00:00Z',
          };
        if (path === '/distribution/withdrawals') return { items: [], total: 0 };
        throw new Error(path);
      });
    const center = mount(DistributionIndex);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    await center
      .findAll('button')
      .find((b) => b.text() === '申请提现')!
      .trigger('click');
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/distribution/withdrawal' });
    center.unmount();

    const page = mount(WithdrawalPage);
    hooks.show.forEach((cb) => cb());
    await flushPromises();
    expect(
      pageConfig.pages.find((entry) => entry.path === 'pages/distribution/withdrawal')?.style
        .navigationBarTitleText,
    ).toBe('申请提现');
    expect(page.text()).toContain('可提现余额');
    await page.find('[data-testid="withdrawal-amount"]').setValue('10');
    await page
      .findAll('button')
      .find((b) => b.text() === '确认提现')!
      .trigger('click');
    await flushPromises();
    expect(request).toHaveBeenCalledWith(
      '/distribution/withdrawals',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(page.text()).toContain('提现单已受理');
    page.unmount();
  });

  it('handles WeChat receipt confirmation adapter environment detection', async () => {
    (globalThis as any).wx = undefined;
    await expect(
      wechatReceiptConfirmationAdapter.confirmReceipt({
        packageInfo: 'test_pkg',
        miniprogramAppId: 'wx123',
      }),
    ).rejects.toThrow('当前非微信小程序环境');

    (globalThis as any).wx = {};
    await expect(
      wechatReceiptConfirmationAdapter.confirmReceipt({
        packageInfo: 'test_pkg',
        miniprogramAppId: 'wx123',
      }),
    ).rejects.toThrow('当前微信版本过低');

    (globalThis as any).wx = {
      openBusinessView: vi.fn(({ success }: any) => {
        success({ extraData: {} });
      }),
    };
    const res = await wechatReceiptConfirmationAdapter.confirmReceipt({
      packageInfo: 'pkg_confirmed',
      miniprogramAppId: 'wx123',
    });
    expect(res).toBe(true);
    expect((globalThis as any).wx.openBusinessView).toHaveBeenCalledWith(
      expect.objectContaining({
        businessType: 'confirmReceipt',
        extraData: { package_info: 'pkg_confirmed' },
      }),
    );
  });

  it('uses official requestMerchantTransfer with owner merchant/app/package parameters when available', async () => {
    const invoke = vi.fn(({ success }: { success: () => void }) => success());
    vi.stubGlobal('wx', { requestMerchantTransfer: invoke });
    expect(
      await wechatReceiptConfirmationAdapter.confirmReceipt({
        packageInfo: 'owner-package',
        merchantId: 'mch',
        appId: 'wx-own',
      }),
    ).toBe(true);
    expect(invoke).toHaveBeenCalledWith(
      expect.objectContaining({ mchId: 'mch', appId: 'wx-own', package: 'owner-package' }),
    );
  });
});
