// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';

const acceptedLogin = {
  accessToken: 'new-customer-session',
  expiresAt: '2099-01-01T00:00:00Z',
  user: { id: 'customer', nickname: '登录用户', distributor: false },
};
const requests: UniApp.RequestOptions[] = [];
const wrappers: VueWrapper[] = [];
const hooks = vi.hoisted(() => ({ show: [] as Array<() => void> }));
vi.mock('@dcloudio/uni-app', () => ({ onShow: (fn: () => void) => hooks.show.push(fn) }));
const agreementConfiguration = {
  storeName: '力山学堂',
  customerService: null,
  allowedFileFormats: ['PDF'],
  agreementVersions: { terms: '2026-1', privacy: '2026-1' },
  agreementLinks: {
    terms: { title: '用户协议', version: '2026-1', articleId: '11' },
    privacy: { title: '隐私政策', version: '2026-1', articleId: '12' },
  },
};
let publicConfig: unknown;
let configFailure: boolean;
let status: number;
let response: unknown;

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv('MODE', 'development');
  vi.stubEnv('VITE_PROVIDER', 'http');
  vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/api/v1');
  vi.stubEnv('VITE_DEV_LOGIN', 'false');
  vi.stubEnv('VITE_DEV_PROXY', 'false');
  status = 200;
  response = { code: 'OK', message: 'OK', requestId: 'login', data: acceptedLogin };
  requests.length = 0;
  hooks.show.length = 0;
  publicConfig = structuredClone(agreementConfiguration);
  configFailure = false;
  vi.stubGlobal('getCurrentPages', () => [{ route: 'pages/home/index' }]);
  const storage = new Map<string, unknown>();
  vi.stubGlobal('uni', {
    getStorageSync: (key: string) => storage.get(key) ?? '',
    setStorageSync: vi.fn((key: string, value: unknown) => storage.set(key, value)),
    removeStorageSync: vi.fn((key: string) => storage.delete(key)),
    login: vi.fn().mockResolvedValue({ code: 'one-time-wechat-code', errMsg: 'login:ok' }),
    request: (request: UniApp.RequestOptions) => {
      requests.push(request);
      request.success?.({
        statusCode: request.url.endsWith('/config/public') ? (configFailure ? 503 : 200) : status,
        data: request.url.endsWith('/config/public')
          ? configFailure
            ? { code: 'SERVICE_UNAVAILABLE', message: '不可用', requestId: 'cfg', data: null }
            : { code: 'OK', message: 'OK', requestId: 'cfg', data: publicConfig }
          : response,
        header: {},
        cookies: [],
        errMsg: '',
      });
    },
    navigateTo: vi.fn().mockResolvedValue({ errMsg: 'navigateTo:ok' }),
    redirectTo: vi.fn().mockResolvedValue({ errMsg: 'redirectTo:ok' }),
    showToast: vi.fn(),
    navigateBack: vi.fn(),
    reLaunch: vi.fn(),
  });
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.resetModules();
});

async function openLogin() {
  const { createPinia } = await import('pinia');
  const { default: LoginPage } = await import('../src/pages/login/index.vue');
  const wrapper = mount(LoginPage, { global: { plugins: [createPinia()] } });
  wrappers.push(wrapper);
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  return wrapper;
}
async function acceptAgreements(wrapper: VueWrapper) {
  await wrapper.get('[role="checkbox"]').trigger('click');
}
const authRequests = () => requests.filter((r) => r.url.includes('/auth/'));
function rejectAuthentication() {
  status = 401;
  response = {
    code: 'UNAUTHORIZED',
    message: '微信登录凭证无效，请重试',
    requestId: 'login-denied',
    data: null,
  };
}

it('exchanges a WeChat code without attaching a revoked session to the login request', async () => {
  const wrapper = await openLogin();
  const { session } = await import('../src/api/session');
  session.set({ ...acceptedLogin, accessToken: 'revoked-token-with-future-expiry' });
  await acceptAgreements(wrapper);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(authRequests()[0]).toMatchObject({
    url: 'http://localhost:8080/api/v1/auth/wechat-login',
    method: 'POST',
    data: { code: 'one-time-wechat-code' },
  });
  expect(authRequests()[0]?.header).not.toHaveProperty('Authorization');
  expect(session.get()?.accessToken).toBe('new-customer-session');
  expect(uni.redirectTo).toHaveBeenCalledWith({ url: '/pages/home/index' });
});

it('shows a rejected login on the current form without opening another login page', async () => {
  rejectAuthentication();
  const wrapper = await openLogin();
  await acceptAgreements(wrapper);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('微信登录凭证无效，请重试');
  expect(uni.navigateTo).not.toHaveBeenCalled();
  expect(uni.redirectTo).not.toHaveBeenCalled();
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeUndefined();
});

it('also leaves a development login request free of an old bearer', async () => {
  vi.stubEnv('VITE_DEV_LOGIN', 'true');
  const wrapper = await openLogin();
  const { session } = await import('../src/api/session');
  session.set({ ...acceptedLogin, accessToken: 'old-dev-token' });
  await acceptAgreements(wrapper);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(authRequests()[0]).toMatchObject({
    url: 'http://localhost:8080/api/v1/auth/dev-login',
    data: { fixture: 'buyer-active-member' },
  });
  expect(authRequests()[0]?.header).not.toHaveProperty('Authorization');
  expect(uni.login).not.toHaveBeenCalled();
});

it('handles an expired-session navigation timeout and preserves the return destination', async () => {
  rejectAuthentication();
  const failure = Promise.reject({ errMsg: 'navigateTo:fail timeout' });
  // Observe the platform promise here so the failing pre-fix test has no stray rejection.
  void failure.catch(() => {});
  vi.mocked(uni.navigateTo).mockReturnValue(failure);
  const { client } = await import('../src/api/client');
  const { session } = await import('../src/api/session');
  session.set(acceptedLogin);
  await expect(client.request('/orders')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  await flushPromises();
  expect(session.get()).toBeNull();
  expect(session.takeReturnTo()).toBe('/pages/home/index');
  expect(uni.showToast).toHaveBeenCalledWith(
    expect.objectContaining({ icon: 'none', title: expect.stringContaining('登录页') }),
  );
  vi.mocked(uni.navigateTo).mockResolvedValueOnce({
    errMsg: 'navigateTo:ok',
    eventChannel: {} as UniApp.EventChannel,
  });
  session.set({ ...acceptedLogin, accessToken: 'retry-revoked-session' });
  await expect(client.request('/orders')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  await flushPromises();
  expect(uni.navigateTo).toHaveBeenCalledTimes(2);
});

it('opens only one login page while another login navigation is still pending', async () => {
  rejectAuthentication();
  let finish!: (value: UniApp.NavigateToSuccessOptions) => void;
  vi.mocked(uni.navigateTo).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  const { client } = await import('../src/api/client');
  const { session } = await import('../src/api/session');
  session.set(acceptedLogin);
  await expect(client.request('/orders')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  session.set({ ...acceptedLogin, accessToken: 'another-revoked-session' });
  await expect(client.request('/addresses')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  expect(uni.navigateTo).toHaveBeenCalledTimes(1);
  finish({ errMsg: 'navigateTo:ok', eventChannel: {} as UniApp.EventChannel });
  await flushPromises();
});

it('preserves the selected checkout after a post-login redirect timeout and retries without logging in again', async () => {
  const wrapper = await openLogin();
  const { session } = await import('../src/api/session');
  const target = '/pages/order/confirm?productId=real%2Fsix&quantity=1';
  session.rememberReturnTo(target);
  const failure = Promise.reject({ errMsg: 'redirectTo:fail timeout' });
  void failure.catch(() => {});
  vi.mocked(uni.redirectTo).mockReturnValueOnce(failure);
  await acceptAgreements(wrapper);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('登录已成功，页面跳转失败，请重试');
  expect(session.takeReturnTo()).toBe(target);
  session.rememberReturnTo(target);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试跳转')!
    .trigger('click');
  await flushPromises();
  expect(uni.redirectTo).toHaveBeenLastCalledWith({ url: target });
  expect(uni.login).toHaveBeenCalledTimes(1);
  expect(authRequests()).toHaveLength(1);
  expect(session.takeReturnTo()).toBeNull();
  expect(wrapper.text()).not.toContain('页面跳转失败');
});

it('requires an active unchecked-by-default consent before any platform or HTTP login', async () => {
  const wrapper = await openLogin();
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('false');
  await wrapper.get('.ls-button--primary').trigger('click');
  expect(uni.login).not.toHaveBeenCalled();
  expect(authRequests()).toHaveLength(0);
  expect(wrapper.text()).toContain('请先阅读并同意');
});

it('opens both real article links without toggling consent and preserves consent on return', async () => {
  const wrapper = await openLogin();
  await wrapper.findAll('.ls-agreement-link')[0]!.trigger('click');
  expect(uni.navigateTo).toHaveBeenLastCalledWith(
    expect.objectContaining({
      url: '/pages/article/detail?id=11&agreement=terms',
    }),
  );
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('false');
  await acceptAgreements(wrapper);
  await wrapper.findAll('.ls-agreement-link')[1]!.trigger('click');
  expect(uni.navigateTo).toHaveBeenLastCalledWith(
    expect.objectContaining({
      url: '/pages/article/detail?id=12&agreement=privacy',
    }),
  );
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('true');
  expect(authRequests()).toHaveLength(0);
});

it('resets consent when a published version or article reference changes on return', async () => {
  const wrapper = await openLogin();
  await acceptAgreements(wrapper);
  publicConfig = {
    ...agreementConfiguration,
    agreementLinks: {
      ...agreementConfiguration.agreementLinks,
      privacy: { title: '隐私政策', version: '2026-2', articleId: '13' },
    },
  };
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('false');
  await wrapper.get('.ls-button--primary').trigger('click');
  expect(authRequests()).toHaveLength(0);
});

it('blocks missing content and recovers from public configuration failure by retrying anonymously', async () => {
  configFailure = true;
  const wrapper = await openLogin();
  expect(wrapper.text()).toContain('协议加载失败');
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeDefined();
  configFailure = false;
  publicConfig = {
    ...agreementConfiguration,
    agreementLinks: { terms: agreementConfiguration.agreementLinks.terms },
  };
  await wrapper.get('.ls-agreement-retry').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('协议暂未配置完整');
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeDefined();
  publicConfig = agreementConfiguration;
  await wrapper.get('.ls-agreement-retry').trigger('click');
  await flushPromises();
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeUndefined();
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('false');
  for (const r of requests) expect(r.header).not.toHaveProperty('Authorization');
  expect(uni.login).not.toHaveBeenCalled();
});

it('retains returnTo and shows recovery when an agreement navigation fails', async () => {
  const wrapper = await openLogin();
  const { session } = await import('../src/api/session');
  session.rememberReturnTo('/pages/order/confirm?productId=8');
  vi.mocked(uni.navigateTo).mockRejectedValueOnce({ errMsg: 'navigateTo:fail timeout' });
  await wrapper.findAll('.ls-agreement-link')[0]!.trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('协议页面打开失败');
  expect(session.takeReturnTo()).toBe('/pages/order/confirm?productId=8');
  expect(authRequests()).toHaveLength(0);
});

it('prevents duplicate login while the platform exchange is pending and recovers after cancellation', async () => {
  const wrapper = await openLogin();
  await acceptAgreements(wrapper);
  let reject!: (reason: unknown) => void;
  vi.mocked(uni.login).mockReturnValueOnce(
    new Promise((_resolve, no) => {
      reject = no;
    }),
  );
  await wrapper.get('.ls-button--primary').trigger('click');
  await wrapper.get('.ls-button--primary').trigger('click');
  expect(uni.login).toHaveBeenCalledTimes(1);
  reject({ errMsg: 'login:fail cancel' });
  await flushPromises();
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeUndefined();
  expect(authRequests()).toHaveLength(0);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(authRequests()).toHaveLength(1);
});

it('leaves browsing available with no agreements and clears the login destination only when cancelling', async () => {
  publicConfig = { ...agreementConfiguration, agreementLinks: {} };
  const wrapper = await openLogin();
  const { session } = await import('../src/api/session');
  session.rememberReturnTo('/pages/order/confirm?productId=8');
  await wrapper.get('.ls-login-secondary').trigger('click');
  expect(uni.navigateBack).toHaveBeenCalled();
  expect(session.takeReturnTo()).toBeNull();
  expect(authRequests()).toHaveLength(0);
});

it('keeps login blocked while a configuration refresh is pending and drops consent after failure', async () => {
  const wrapper = await openLogin();
  await acceptAgreements(wrapper);
  let pending!: UniApp.RequestOptions;
  const original = uni.request;
  vi.spyOn(uni, 'request').mockImplementation((options: UniApp.RequestOptions) => {
    if (options.url.endsWith('/config/public')) {
      pending = options;
      return {} as UniApp.RequestTask;
    }
    return original(options);
  });
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(wrapper.text()).toContain('正在加载协议');
  await wrapper.get('.ls-button--primary').trigger('click');
  expect(uni.login).not.toHaveBeenCalled();
  pending.fail?.({ errMsg: 'request:fail offline' });
  await flushPromises();
  expect(wrapper.text()).toContain('协议加载失败');
  expect(wrapper.find('[role="checkbox"]').exists()).toBe(false);
  expect(authRequests()).toHaveLength(0);
});

it('does not authorize a malformed article reference or an empty agreement version', async () => {
  publicConfig = {
    ...agreementConfiguration,
    agreementLinks: {
      terms: { title: '用户协议', version: '1', articleId: '../11' },
      privacy: { title: '隐私政策', version: ' ', articleId: '12' },
    },
  };
  const wrapper = await openLogin();
  expect(wrapper.get('.ls-button--primary').attributes('disabled')).toBeDefined();
  expect(wrapper.find('[role="checkbox"]').exists()).toBe(false);
  expect(authRequests()).toHaveLength(0);
});

it('recovers referral claiming after login without exchanging the WeChat code twice', async () => {
  const wrapper = await openLogin();
  const { referralManager } = await import('@jxsc/shared');
  const { session } = await import('../src/api/session');
  const target = '/pages/order/confirm?productId=8';
  session.rememberReturnTo(target);
  referralManager.saveAttribution('8', 'isolated-referral-token', '2099-01-01T00:00:00Z');
  const original = uni.request;
  let claims = 0;
  vi.spyOn(uni, 'request').mockImplementation((options: UniApp.RequestOptions) => {
    if (!options.url.endsWith('/referrals/claim')) return original(options);
    claims++;
    const result =
      claims === 1
        ? {
            statusCode: 503,
            data: {
              code: 'SERVICE_UNAVAILABLE',
              message: '不可用',
              requestId: 'claim',
              data: null,
            },
          }
        : {
            statusCode: 200,
            data: {
              code: 'OK',
              message: 'OK',
              requestId: 'claim',
              data: { claimed: true, productId: '8' },
            },
          };
    options.success?.({ ...result, header: {}, cookies: [], errMsg: '' });
    return {} as UniApp.RequestTask;
  });
  await acceptAgreements(wrapper);
  await wrapper.get('.ls-button--primary').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('重试认领推荐信息');
  expect(uni.redirectTo).not.toHaveBeenCalled();
  await wrapper.get('.ls-button--primary').trigger('click');
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '重试认领推荐信息')!
    .trigger('click');
  await flushPromises();
  expect(claims).toBe(2);
  expect(uni.login).toHaveBeenCalledTimes(1);
  expect(authRequests()).toHaveLength(1);
  expect(uni.redirectTo).toHaveBeenLastCalledWith({ url: target });
});

it('clears existing consent when the opened agreement reports a body-read failure', async () => {
  const wrapper = await openLogin();
  await acceptAgreements(wrapper);
  await wrapper.findAll('.ls-agreement-link')[0]!.trigger('click');
  const options = vi.mocked(uni.navigateTo).mock.calls.at(-1)![0];
  expect(options.events?.agreementUnavailable).toBeTypeOf('function');
  options.events!.agreementUnavailable();
  hooks.show.forEach((fn) => fn());
  await flushPromises();
  expect(wrapper.get('[role="checkbox"]').attributes('aria-checked')).toBe('false');
  expect(wrapper.text()).toContain('协议正文暂不可用');
  await wrapper.get('.ls-button--primary').trigger('click');
  expect(authRequests()).toHaveLength(0);
});
