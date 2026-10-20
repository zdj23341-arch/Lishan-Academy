import { ApiError } from '../http';
import type { AdminAsset, UploadInput, UploadTicket } from './admin-types';
export interface UploadFile extends UploadInput {
  source: unknown;
}
export interface UploadState {
  phase:
    | 'IDLE'
    | 'AUTHORIZING'
    | 'UPLOADING'
    | 'FINALIZING'
    | 'PROCESSING'
    | 'READY'
    | 'FAILED'
    | 'DISABLED';
  asset: AdminAsset | null;
  error: Error | null;
  fileName: string;
}
export interface UploadServices {
  presign(input: UploadInput): Promise<UploadTicket>;
  transfer(ticket: UploadTicket, file: UploadFile): Promise<void>;
  complete(id: string): Promise<AdminAsset>;
  read?(id: string): Promise<AdminAsset>;
}
const formats: Record<string, string[]> = {
  DOC: ['application/msword'],
  DOCX: ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  PDF: ['application/pdf'],
  JPG: ['image/jpeg'],
  JPEG: ['image/jpeg'],
  PNG: ['image/png'],
  WEBP: ['image/webp'],
  MP4: ['video/mp4'],
};
export function validateUpload(file: UploadInput, maxBytes = Number.MAX_SAFE_INTEGER) {
  const extension = file.name.split('.').at(-1)?.toUpperCase() ?? '';
  const allowed =
    file.purpose === 'COURSE_VIDEO'
      ? ['MP4']
      : file.purpose === 'PAPER_ATTACHMENT'
        ? ['DOC', 'DOCX', 'PDF', 'JPG', 'JPEG', 'PNG', 'WEBP']
        : ['JPG', 'JPEG', 'PNG', 'WEBP'];
  if (
    !file.name.trim() ||
    file.name.length > 200 ||
    !allowed.includes(extension) ||
    !formats[extension]?.includes(file.mime)
  )
    throw new ApiError('VALIDATION_ERROR', '文件格式或MIME不匹配');
  const formatLimit =
    extension === 'MP4'
      ? 2 * 1024 * 1024 * 1024
      : ['DOC', 'DOCX', 'PDF'].includes(extension)
        ? 50 * 1024 * 1024
        : 10 * 1024 * 1024;
  if (
    !Number.isSafeInteger(file.bytes) ||
    file.bytes <= 0 ||
    file.bytes > Math.min(maxBytes, formatLimit)
  )
    throw new ApiError('VALIDATION_ERROR', '文件大小无效或超过上传限制');
}
export function createUploadController(services: UploadServices, maxBytes?: number) {
  const state: UploadState = { phase: 'IDLE', asset: null, error: null, fileName: '' };
  const listeners = new Set<(state: UploadState) => void>();
  let last: UploadFile | null = null;
  let lastAssetId: string | null = null;
  let pending: Promise<AdminAsset> | null = null;
  function update(change: Partial<UploadState>) {
    Object.assign(state, change);
    for (const listener of listeners) listener({ ...state });
  }
  function accept(asset: AdminAsset): AdminAsset {
    if (asset.status === 'DISABLED') throw new ApiError('FILE_DISABLED', '文件已停用，请更换文件');
    if (asset.status === 'REJECTED')
      throw new ApiError('FILE_REJECTED', '文件未通过服务端检查，请更换文件');
    if (asset.id !== lastAssetId || asset.purpose !== last?.purpose)
      throw new ApiError('INVALID_RESPONSE', '文件检查结果不匹配，请核对原文件');
    if (asset.status !== 'READY')
      throw new ApiError('FILE_PROCESSING', '上传完成，文件处理中，请稍后继续处理', null, {}, 409);
    lastAssetId = null;
    update({ phase: 'READY', asset, error: null });
    return asset;
  }
  function failure(cause: unknown): never {
    const error = cause instanceof Error ? cause : new Error('上传失败');
    const disabled = cause instanceof ApiError && cause.code === 'FILE_DISABLED';
    const rejected = cause instanceof ApiError && cause.code === 'FILE_REJECTED';
    if (disabled || rejected) lastAssetId = null;
    update({
      phase: disabled ? 'DISABLED' : lastAssetId ? 'PROCESSING' : 'FAILED',
      error,
      asset: null,
    });
    throw error;
  }
  function start(file: UploadFile): Promise<AdminAsset> {
    if (pending) return pending;
    last = file;
    lastAssetId = null;
    update({ phase: 'AUTHORIZING', asset: null, error: null, fileName: file.name });
    pending = Promise.resolve().then(async () => {
      try {
        validateUpload(file, maxBytes);
        const { source: _, ...input } = file;
        void _;
        const ticket = await services.presign(input);
        if (
          Date.parse(ticket.expiresAt) <= Date.now() ||
          !Number.isFinite(Date.parse(ticket.expiresAt))
        )
          throw new ApiError('UPLOAD_EXPIRED', '上传凭证已过期，请重试');
        update({ phase: 'UPLOADING' });
        await services.transfer(ticket, file);
        update({ phase: 'FINALIZING' });
        lastAssetId = ticket.id;
        return accept(await services.complete(ticket.id));
      } catch (cause) {
        return failure(cause);
      } finally {
        pending = null;
      }
    });
    return pending;
  }
  return {
    state,
    start,
    retry() {
      if (pending) return pending;
      if (lastAssetId) {
        update({ phase: 'FINALIZING', error: null });
        pending = Promise.resolve().then(async () => {
          try {
            const inspected = await (services.read
              ? services.read(lastAssetId!)
              : services.complete(lastAssetId!));
            const asset =
              inspected.status === 'UPLOADED' && services.read
                ? await services.complete(lastAssetId!)
                : inspected;
            return accept(asset);
          } catch (cause) {
            return failure(cause);
          } finally {
            pending = null;
          }
        });
        return pending;
      }
      return last ? start(last) : Promise.reject(new Error('请先选择文件'));
    },
    subscribe(listener: (state: UploadState) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
