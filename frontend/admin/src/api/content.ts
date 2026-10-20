import { createAdminContentApi } from '@jxsc/shared/content/admin-api';
import { createCatalogApi } from '@jxsc/shared/content/api';
import { createUploadTransfer } from '@jxsc/shared/content/upload-transfer';
import type { UploadServices } from '@jxsc/shared/content/upload';
import { ApiError } from '@jxsc/shared';
import { client, environment } from './client';
import { session } from './session';

export const contentApi = createAdminContentApi(client, () => session.get()?.permissions ?? []);
export const catalogApi = createCatalogApi(client);
const httpTransfer = createUploadTransfer({
  apiBaseUrl: environment.apiBaseUrl,
  accessToken: () => session.get()?.accessToken ?? null,
});
export const uploadServices: UploadServices = {
  presign: contentApi.presign,
  complete: contentApi.complete,
  read: contentApi.asset,
  async transfer(ticket, file) {
    if (import.meta.env.VITE_PROVIDER !== 'mock') return httpTransfer(ticket, file);
    const { devTransport } = await import('../../../shared/src/dev/transport');
    const result = await devTransport({
      url: ticket.uploadUrl,
      method: 'PUT',
      headers: { 'X-Upload-Token': ticket.uploadToken },
      body: file.source,
    });
    if (result.status < 200 || result.status >= 300)
      throw new ApiError('UPLOAD_FAILED', '开发上传场景失败，请重试', null, {}, result.status);
  },
};
