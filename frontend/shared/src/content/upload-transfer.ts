import type { UploadServices } from './upload';
import { ApiError } from '../http';
import { RuntimeURL } from '../runtime-url';

export interface UploadTransferConfig {
  apiBaseUrl: string;
  accessToken(): string | null;
  fetch?: typeof globalThis.fetch;
  timeoutMs?: number;
}

export function createUploadTransfer(config: UploadTransferConfig): UploadServices['transfer'] {
  const api = new RuntimeURL(config.apiBaseUrl);
  const send = config.fetch ?? globalThis.fetch;
  return async (ticket, file) => {
    let url: URL;
    try {
      url = new RuntimeURL(ticket.uploadUrl, api);
    } catch {
      throw new ApiError('INVALID_UPLOAD_URL', '上传地址无效');
    }
    const sameOrigin = url.origin === api.origin;
    if (
      url.username ||
      url.password ||
      url.hash ||
      (url.protocol !== 'https:' && !(sameOrigin && api.protocol === 'http:'))
    ) {
      throw new ApiError('INVALID_UPLOAD_URL', '上传地址必须使用安全的HTTP地址');
    }
    const headers: Record<string, string> = {
      'Content-Type': 'application/octet-stream',
      'X-Upload-Token': ticket.uploadToken,
    };
    if (sameOrigin) {
      const token = config.accessToken();
      if (!token) throw new ApiError('UNAUTHORIZED', '登录已失效，请重新登录后上传');
      headers.Authorization = `Bearer ${token}`;
    }
    if (!(file.source instanceof Blob) || file.source.size !== file.bytes) {
      throw new ApiError('INVALID_UPLOAD_FILE', '所选文件与上传信息不匹配，请重新选择');
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.timeoutMs ?? 120000);
    try {
      const response = await send(url.href, {
        method: 'PUT',
        headers,
        body: file.source,
        credentials: 'omit',
        redirect: 'error',
        signal: controller.signal,
      });
      if (!response.ok)
        throw new ApiError(
          'UPLOAD_FAILED',
          '文件传输失败，请重新获取凭证后重试',
          null,
          {},
          response.status,
        );
    } catch (error) {
      if (controller.signal.aborted) throw new ApiError('TIMEOUT', '文件上传超时，请重试');
      if (error instanceof ApiError) throw error;
      throw new ApiError('NETWORK_ERROR', '文件传输连接失败，请重试');
    } finally {
      clearTimeout(timeout);
    }
  };
}
