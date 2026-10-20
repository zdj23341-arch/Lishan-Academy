import { ApiError, createClient, resolveServiceUrls, type Transport } from '@jxsc/shared';
import { session } from './session';
import { environment } from './environment';
export { environment } from './environment';
const uniTransport: Transport = (request) =>
  new Promise((resolve, reject) => {
    const headers = { ...request.headers };
    if (request.method === 'PATCH') {
      headers['X-HTTP-Method-Override'] = 'PATCH';
    }
    uni.request({
      url: request.url,
      method: request.method as UniApp.RequestOptions['method'],
      header: headers,
      data: request.body as Record<string, unknown> | undefined,
      timeout: 15000,
      success: (response) =>
        resolve({
          status: response.statusCode,
          body: resolveServiceUrls(response.data, environment.apiBaseUrl),
        }),
      fail: (error) =>
        reject(
          new ApiError(
            error.errMsg?.includes('timeout') ? 'TIMEOUT' : 'NETWORK_ERROR',
            '请求未完成，请刷新查询或重试',
          ),
        ),
    });
  });
const transport: Transport = async (request) => {
  if (import.meta.env.VITE_PROVIDER === 'mock')
    return (await import('../../../shared/src/dev/transport')).devTransport(request);
  return uniTransport(request);
};
let openingLogin = false;
async function openLogin() {
  if (openingLogin) return;
  const page = getCurrentPages().slice(-1)[0];
  if (page?.route === 'pages/login/index') return;
  if (page?.route) {
    const options = (page as { options?: Record<string, string> }).options ?? {};
    const query = Object.entries(options)
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
      .join('&');
    session.rememberReturnTo(`/${page.route}${query ? `?${query}` : ''}`);
  }
  openingLogin = true;
  try {
    await uni.navigateTo({ url: '/pages/login/index' });
  } catch {
    uni.showToast({ title: '登录页打开失败，请稍后重试', icon: 'none' });
  } finally {
    openingLogin = false;
  }
}
export const client = createClient({
  baseUrl: environment.apiBaseUrl,
  transport,
  session,
  onUnauthorized: () => {
    void openLogin();
  },
});
