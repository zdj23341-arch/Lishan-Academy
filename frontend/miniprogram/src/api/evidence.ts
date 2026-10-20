import { ApiError, type Session, type Transport } from '../../../shared/src';
import { RuntimeURL } from '../../../shared/src/runtime-url';
import type { AdminAsset, UploadTicket } from '../../../shared/src/content/admin-types';
import {
  createUploadController,
  validateUpload,
  type UploadFile,
  type UploadServices,
  type UploadState,
} from '../../../shared/src/content/upload';
import { createUploadTransfer } from '../../../shared/src/content/upload-transfer';

const limit = 10 * 1024 * 1024;
export function evidenceFile(source: ArrayBuffer, name = ''): UploadFile {
  const bytes = new Uint8Array(source);
  const starts = (signature: number[]) => signature.every((value, i) => bytes[i] === value);
  const format = starts([137, 80, 78, 71, 13, 10, 26, 10])
    ? 'png'
    : starts([255, 216, 255])
      ? 'jpg'
      : starts([82, 73, 70, 70]) && [87, 69, 66, 80].every((value, i) => bytes[i + 8] === value)
        ? 'webp'
        : null;
  if (!format) throw new ApiError('VALIDATION_ERROR', '请选择 PNG、JPEG 或 WEBP 图片');
  const extension = name
    .split(/[?#]/)[0]
    ?.split('/')
    .at(-1)
    ?.match(/\.([a-z0-9]+)$/i)?.[1]
    ?.toLowerCase();
  if (extension && extension !== format && !(format === 'jpg' && extension === 'jpeg'))
    throw new ApiError('VALIDATION_ERROR', '图片内容与扩展名不匹配');
  const file: UploadFile = {
    source,
    name: `evidence.${format}`,
    mime: format === 'jpg' ? 'image/jpeg' : `image/${format}`,
    bytes: source.byteLength,
    purpose: 'AFTERSALE_EVIDENCE',
  };
  validateUpload(file, limit);
  return file;
}

export function createEvidenceTransfer(config: {
  apiBaseUrl: string;
  accessToken(): string | null;
  send: Transport;
}): UploadServices['transfer'] {
  return async (ticket, file) => {
    const api = new RuntimeURL(config.apiBaseUrl);
    let url: URL;
    try {
      url = new RuntimeURL(ticket.uploadUrl, api);
    } catch {
      throw new ApiError('INVALID_UPLOAD_URL', '上传地址无效');
    }
    const sameOrigin = api.origin === url.origin;
    if (
      url.username ||
      url.password ||
      url.hash ||
      (url.protocol !== 'https:' && !(sameOrigin && api.protocol === 'http:'))
    )
      throw new ApiError('INVALID_UPLOAD_URL', '上传地址必须使用安全连接');
    if (!(file.source instanceof ArrayBuffer) || file.source.byteLength !== file.bytes)
      throw new ApiError('INVALID_UPLOAD_FILE', '图片数据不匹配，请重新选择');
    const headers: Record<string, string> = {
      'Content-Type': 'application/octet-stream',
      'X-Upload-Token': ticket.uploadToken,
    };
    if (sameOrigin) {
      const token = config.accessToken();
      if (!token) throw new ApiError('UNAUTHORIZED', '请登录后上传');
      headers.Authorization = `Bearer ${token}`;
    }
    const response = await config.send({
      url: url.href,
      method: 'PUT',
      headers,
      body: file.source,
    });
    if (response.status < 200 || response.status >= 300)
      throw new ApiError('UPLOAD_FAILED', '上传失败，请重试');
  };
}

export function createEvidenceUpload(session: Session, services: UploadServices) {
  const listeners = new Set<(state: UploadState) => void>();
  let disposed = false;
  let controller: ReturnType<typeof createUploadController> | null = null;
  let unsubscribe: (() => void) | null = null;
  let generation = -1;
  const empty = (): UploadState => ({ phase: 'IDLE', asset: null, error: null, fileName: '' });
  function reset() {
    unsubscribe?.();
    unsubscribe = null;
    controller = null;
    for (const listener of listeners) listener(empty());
  }
  const stopSession = session.subscribe(reset);
  function check() {
    const login = session.get();
    if (disposed || !login || session.getGeneration() !== generation)
      throw new ApiError('SESSION_CHANGED', '登录状态已变化，请重新选择图片');
  }
  return {
    start(file: UploadFile) {
      if (!controller) {
        generation = session.getGeneration();
        try {
          check();
        } catch (error) {
          return Promise.reject(error);
        }
        const captured = generation;
        const guard = () => {
          check();
          if (captured !== session.getGeneration())
            throw new ApiError('SESSION_CHANGED', '登录状态已变化');
        };
        controller = createUploadController(
          {
            async presign(input) {
              guard();
              const result = await services.presign(input);
              guard();
              return result;
            },
            async transfer(ticket, selected) {
              guard();
              await services.transfer(ticket, selected);
              guard();
            },
            async complete(id) {
              guard();
              const result = await services.complete(id);
              guard();
              return result;
            },
          },
          limit,
        );
        unsubscribe = controller.subscribe((state) => {
          if (!disposed && session.getGeneration() === captured)
            for (const listener of listeners) listener(state);
        });
      }
      return controller.start(file);
    },
    retry() {
      return controller ? controller.retry() : Promise.reject(new Error('请重新选择图片'));
    },
    subscribe(listener: (state: UploadState) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    dispose() {
      disposed = true;
      reset();
      stopSession();
      listeners.clear();
    },
  };
}

export async function chooseEvidence(
  isCurrent: () => boolean = () => true,
): Promise<{ file: UploadFile; preview: string }> {
  const selected = await new Promise<{ path: string; name: string; blob?: Blob }>(
    (resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sizeType: ['original'],
        sourceType: ['album', 'camera'],
        success(result) {
          const path = result.tempFilePaths[0];
          const entry = Array.isArray(result.tempFiles) ? result.tempFiles[0] : undefined;
          const blob = typeof Blob !== 'undefined' && entry instanceof Blob ? entry : undefined;
          if (!path) {
            reject(new Error('未选择图片'));
            return;
          }
          if (entry && entry.size > limit) {
            reject(new Error('图片不能超过 10 兆字节（按 1024 进制计）'));
            return;
          }
          resolve({
            path,
            name: blob && 'name' in blob ? String(blob.name) : path.startsWith('blob:') ? '' : path,
            blob,
          });
        },
        fail: reject,
      });
    },
  );
  if (!isCurrent()) throw new ApiError('SESSION_CHANGED', '登录状态已变化');
  let source: ArrayBuffer;
  if (selected.blob) source = await selected.blob.arrayBuffer();
  else if (typeof window !== 'undefined' && typeof fetch === 'function') {
    const response = await fetch(selected.path);
    if (!response.ok) throw new Error('读取图片失败');
    source = await response.arrayBuffer();
  } else {
    source = await new Promise<ArrayBuffer>((resolve, reject) => {
      uni.getFileSystemManager().readFile({
        filePath: selected.path,
        success(result) {
          if (result.data instanceof ArrayBuffer) resolve(result.data);
          else reject(new Error('读取图片字节失败'));
        },
        fail: reject,
      });
    });
  }
  if (!isCurrent()) throw new ApiError('SESSION_CHANGED', '登录状态已变化');
  return { file: evidenceFile(source, selected.name), preview: selected.path };
}

export async function evidenceServices(session: Session): Promise<UploadServices> {
  const { client, environment } = await import('./client');
  const token = session.get()?.accessToken ?? null;
  const generation = session.getGeneration();
  const check = () => {
    if (!session.get() || session.getGeneration() !== generation)
      throw new ApiError('SESSION_CHANGED', '登录状态已变化');
  };
  const send: Transport = async (request) => {
    check();
    if (environment.provider === 'mock') {
      const { devTransport } = await import('../../../shared/src/dev/transport');
      check();
      return devTransport(request);
    }
    return new Promise((resolve, reject) =>
      uni.request({
        url: request.url,
        method: 'PUT',
        header: request.headers,
        data: request.body as ArrayBuffer,
        timeout: 120000,
        success: (result) => resolve({ status: result.statusCode, body: result.data }),
        fail: () => reject(new Error('文件传输失败，请重试')),
      }),
    );
  };
  const raw = createEvidenceTransfer({
    apiBaseUrl: environment.apiBaseUrl,
    accessToken: () => token,
    send,
  });
  const h5 =
    typeof window !== 'undefined' && typeof fetch === 'function' && environment.provider !== 'mock'
      ? createUploadTransfer({ apiBaseUrl: environment.apiBaseUrl, accessToken: () => token })
      : null;
  return {
    presign(input) {
      check();
      return client.request<UploadTicket>('/files/presign', { method: 'POST', body: input });
    },
    transfer(ticket, file) {
      check();
      return h5
        ? h5(ticket, {
            ...file,
            source: new Blob([file.source as ArrayBuffer], { type: file.mime }),
          })
        : raw(ticket, file);
    },
    complete(id) {
      check();
      return client.request<AdminAsset>(`/files/${encodeURIComponent(id)}/complete`, {
        method: 'POST',
        body: {},
      });
    },
  };
}
