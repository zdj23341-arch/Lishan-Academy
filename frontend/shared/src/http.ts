import type { Session } from './session';
import { chineseErrorMessage, chineseFieldError } from './chinese-errors';
export type Query = Record<string, string | number | boolean | undefined | null>;
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Login exchanges are anonymous and must not reuse or invalidate an existing session. */
  authentication?: 'session' | 'none';
  query?: Query;
  body?: unknown;
  idempotencyKey?: string;
}
export interface TransportRequest {
  url: string;
  method: NonNullable<RequestOptions['method']>;
  headers: Record<string, string>;
  body?: unknown;
}
export type Transport = (request: TransportRequest) => Promise<{ status: number; body: unknown }>;
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public requestId: string | null = null,
    public fieldErrors: Record<string, string> = {},
    public status = 0,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
export function createClient(config: {
  baseUrl: string;
  transport: Transport;
  session: Session;
  onUnauthorized?: () => void;
}) {
  let lastUnauthorizedGeneration: number | undefined;
  return {
    async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
      if (!/^\/(?!\/)/.test(path) || /[\\?#\s]/.test(path) || /(?:^|\/)\.\.(?:\/|$)/.test(path))
        throw new ApiError('INVALID_PATH', '请求路径必须是内部API路径');
      const query = Object.entries(options.query ?? {})
        .filter(([, value]) => value !== undefined && value !== null)
        .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
        .join('&');
      const usesSession = options.authentication !== 'none';
      const token = usesSession ? (config.session.get()?.accessToken ?? null) : null;
      const generation = config.session.getGeneration();
      const headers: Record<string, string> = {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      };
      if (token) headers.Authorization = `Bearer ${token}`;
      if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
      let response: Awaited<ReturnType<Transport>>;
      try {
        response = await config.transport({
          url: `${config.baseUrl.replace(/\/+$/, '')}${path}${query ? `?${query}` : ''}`,
          method: options.method ?? 'GET',
          headers,
          body: options.body,
        });
      } catch (error) {
        if (error instanceof ApiError) throw error;
        throw new ApiError('NETWORK_ERROR', '网络连接失败，请重试；写操作请先查询原结果');
      }
      const body = response.body;
      const unauthorized =
        response.status === 401 || (record(body) && body.code === 'UNAUTHORIZED');
      if (unauthorized && usesSession) {
        if (
          config.session.getGeneration() === generation &&
          lastUnauthorizedGeneration !== generation
        ) {
          config.session.clear();
          // The resulting anonymous generation also suppresses repeated redirects.
          lastUnauthorizedGeneration = config.session.getGeneration();
          config.onUnauthorized?.();
        }
      }
      if (
        !record(body) ||
        typeof body.code !== 'string' ||
        typeof body.message !== 'string' ||
        typeof body.requestId !== 'string' ||
        !('data' in body)
      ) {
        throw new ApiError(
          'INVALID_RESPONSE',
          '服务响应格式异常，请稍后重试',
          null,
          {},
          response.status,
        );
      }
      if (response.status < 200 || response.status >= 300 || body.code !== 'OK') {
        const fields: Record<string, string> = {};
        if (record(body.data) && record(body.data.fieldErrors))
          for (const [key, value] of Object.entries(body.data.fieldErrors)) {
            if (typeof value === 'string') fields[key] = chineseFieldError(value);
          }
        throw new ApiError(
          body.code === 'OK' ? `HTTP_${response.status}` : body.code,
          chineseErrorMessage(body.code, body.message, response.status),
          body.requestId,
          fields,
          response.status,
        );
      }
      return body.data as T;
    },
  };
}
export type ApiClient = ReturnType<typeof createClient>;
