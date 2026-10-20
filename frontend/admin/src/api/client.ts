import { ApiError, createClient, type Transport } from '@jxsc/shared';
import { session } from './session';
import { router } from '../router';
import { environment } from './environment';
export { environment } from './environment';
const fetchTransport: Transport = async (request) => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      signal: controller.signal,
    });
    const body: unknown = await response.json().catch(() => null);
    return { status: response.status, body };
  } catch (error) {
    if (controller.signal.aborted)
      throw new ApiError('TIMEOUT', '请求超时，请查询原操作状态后再继续');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
};
const transport: Transport = async (request) => {
  if (import.meta.env.VITE_PROVIDER === 'mock')
    return (await import('../../../shared/src/dev/transport')).devTransport(request);
  return fetchTransport(request);
};
export const client = createClient({
  baseUrl: environment.apiBaseUrl,
  transport,
  session,
  onUnauthorized: () => {
    if (router.currentRoute.value.path !== '/login') {
      session.rememberReturnTo(router.currentRoute.value.fullPath);
      void router.replace('/login');
    }
  },
});
