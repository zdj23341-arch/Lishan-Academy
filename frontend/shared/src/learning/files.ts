import type { AssetSummary, FileFormat } from '../contracts';
import { ApiError } from '../http';
import { RuntimeURL } from '../runtime-url';
import type { Session } from '../session';
import type { LearningApi } from './api';
import type { FileAction, FileAuthorization } from './types';

const messages = {
  UNAUTHORIZED: '请先登录后再访问文件',
  FORBIDDEN: '当前账号没有此文件的访问权限，请检查会员状态',
  MEMBERSHIP_EXPIRED: '会员已过期，请续费后重试',
  FILE_NOT_READY: '文件尚未准备完成，请稍后重试',
  INVALID_FILE: '文件信息不完整或格式与类型不符，请刷新后重试',
  UNSUPPORTED_FORMAT: '仅支持 DOC、DOCX、PDF、JPG、JPEG、PNG、WEBP，请联系管理员更换格式',
  INVALID_FILE_URL: '文件地址不受支持，请刷新授权后重试',
  AUTHORIZATION_EXPIRED: '文件授权已过期，请重新点击获取授权',
  AUTHORIZATION_FAILED: '文件授权失败，请重试',
  TRANSFER_FAILED: '文件传输失败，请检查网络后重试',
  PREVIEW_UNSUPPORTED: '当前环境无法预览此文件，请允许浏览器弹窗或改用下载功能',
  SAVE_UNSUPPORTED: '当前环境不支持保存或分享此文件，请使用支持该功能的微信客户端或浏览器',
  PREVIEW_FAILED: '文件预览失败，请重试或使用支持此格式的应用',
  SAVE_FAILED: '保存或分享未完成，请重试',
  ALBUM_PERMISSION_DENIED: '相册权限未开启；仍可预览。如需保存，请点击权限设置后开启相册权限',
  SETTINGS_UNSUPPORTED: '当前环境无法打开权限设置，请在系统设置中开启相册权限',
  CANCELLED: '操作已取消，请重新点击重试',
} as const;
export type ProtectedFileErrorCode = keyof typeof messages;
export class ProtectedFileError extends Error {
  constructor(public readonly code: ProtectedFileErrorCode) {
    super(messages[code]);
    this.name = 'ProtectedFileError';
  }
}
/** Only codes from our fixed vocabulary are exposed; server/native error text is never copied. */
export function protectedFileError(
  error: unknown,
  fallback: ProtectedFileErrorCode,
): ProtectedFileError {
  if (error instanceof ProtectedFileError) return new ProtectedFileError(error.code);
  if (error instanceof ApiError && Object.hasOwn(messages, error.code))
    return new ProtectedFileError(error.code as ProtectedFileErrorCode);
  return new ProtectedFileError(fallback);
}
const mimeByFormat: Record<FileFormat, string> = {
  DOC: 'application/msword',
  DOCX: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  PDF: 'application/pdf',
  JPG: 'image/jpeg',
  JPEG: 'image/jpeg',
  PNG: 'image/png',
  WEBP: 'image/webp',
};
export function protectedFileFormat(file: AssetSummary): FileFormat {
  if (!file || typeof file.extension !== 'string') throw new ProtectedFileError('INVALID_FILE');
  const format = file.extension.toUpperCase();
  if (!Object.hasOwn(mimeByFormat, format)) throw new ProtectedFileError('UNSUPPORTED_FORMAT');
  if (
    typeof file.id !== 'string' ||
    !file.id.trim() ||
    typeof file.name !== 'string' ||
    !file.name.trim() ||
    typeof file.mime !== 'string' ||
    file.mime.toLowerCase() !== mimeByFormat[format as FileFormat] ||
    !Number.isSafeInteger(file.bytes) ||
    file.bytes <= 0
  )
    throw new ProtectedFileError('INVALID_FILE');
  return format as FileFormat;
}
export interface ProtectedFileValidationOptions {
  now?: () => number;
  /** DEV mock provider only. Exact root-relative fixture paths, never arbitrary URLs or query strings. */
  mockAssetUrls?: readonly string[];
  /** Development/test HTTP is accepted only when the ticket shares this API base's origin. */
  developmentHttpApiBaseUrl?: string;
}
export function validateFileAuthorization(
  auth: FileAuthorization,
  options: ProtectedFileValidationOptions = {},
): FileFormat {
  if (!auth || typeof auth !== 'object') throw new ProtectedFileError('INVALID_FILE');
  const format = protectedFileFormat(auth.file);
  const expiry = Date.parse(auth.expiresAt);
  if (
    typeof auth.expiresAt !== 'string' ||
    !/(?:Z|[+-]\d{2}:\d{2})$/.test(auth.expiresAt) ||
    !Number.isFinite(expiry) ||
    expiry <= (options.now ?? Date.now)()
  )
    throw new ProtectedFileError('AUTHORIZATION_EXPIRED');
  const url = auth.url;
  const mock =
    typeof url === 'string' &&
    /^\/(?!\/)[a-z\d/_.-]+$/i.test(url) &&
    !url.split('/').some((part) => part === '..' || part === '.') &&
    options.mockAssetUrls?.includes(url);
  // No URL global is required: WeChat JS runtimes do not all implement it.
  const match =
    typeof url === 'string'
      ? /^(https?):\/\/([a-z\d.-]+)(?::(\d{1,5}))?(?:[/?][^\s#\\]*)?$/i.exec(url)
      : null;
  let developmentHttp = false;
  if (match?.[1]?.toLowerCase() === 'http' && options.developmentHttpApiBaseUrl) {
    try {
      const ticketUrl = new RuntimeURL(url!);
      const apiBaseUrl = new RuntimeURL(options.developmentHttpApiBaseUrl);
      developmentHttp =
        ticketUrl.protocol === 'http:' &&
        apiBaseUrl.protocol === 'http:' &&
        ticketUrl.origin === apiBaseUrl.origin;
    } catch {
      developmentHttp = false;
    }
  }
  const secure =
    match &&
    match[2]?.split('.').every((label) => /^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(label)) &&
    (!match[3] || (+match[3] > 0 && +match[3] <= 65535)) &&
    !/%(?:0[0-9a-f]|1[0-9a-f]|7f|5c)/i.test(url);
  if (!mock && !(secure && (match[1]?.toLowerCase() === 'https' || developmentHttp)))
    throw new ProtectedFileError('INVALID_FILE_URL');
  return format;
}
export interface ProtectedFilePlatform {
  preview(auth: FileAuthorization): Promise<void>;
  save(auth: FileAuthorization): Promise<void>;
  clear(): void;
}
export interface ProtectedFileState {
  busy: boolean;
  action: FileAction | null;
  fileId: string | null;
  error: string | null;
  errorCode: ProtectedFileErrorCode | null;
}
export interface ProtectedFileControllerOptions extends ProtectedFileValidationOptions {
  api: Pick<LearningApi, 'authorize'>;
  session: Session;
  platform: ProtectedFilePlatform;
}
export function createProtectedFileController(options: ProtectedFileControllerOptions) {
  const state: ProtectedFileState = {
    busy: false,
    action: null,
    fileId: null,
    error: null,
    errorCode: null,
  };
  const now = options.now ?? Date.now;
  let generation = 0;
  let disposed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  function clear() {
    generation++;
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    try {
      options.platform.clear();
    } catch {
      /* Cleanup errors must never expose native URL text. */
    }
    Object.assign(state, { busy: false, action: null, fileId: null, error: null, errorCode: null });
  }
  const unsubscribe = options.session.subscribe(clear);
  function current(ticket: number, actor: number) {
    options.session.get();
    return !disposed && ticket === generation && actor === options.session.getGeneration();
  }
  function expireAt(deadline: number, ticket: number, actor: number) {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    const remaining = deadline - now();
    if (remaining <= 0) {
      if (current(ticket, actor)) clear();
      return;
    }
    timer = setTimeout(
      () => {
        if (current(ticket, actor)) expireAt(deadline, ticket, actor);
      },
      Math.min(remaining, 2147483647),
    );
  }
  async function open(paperId: string, file: AssetSummary, action: FileAction) {
    if (disposed || state.busy) return;
    clear();
    const user = options.session.get();
    const ticket = generation;
    const actor = options.session.getGeneration();
    state.busy = true;
    try {
      if (!user) throw new ProtectedFileError('UNAUTHORIZED');
      if (!paperId.trim() || (action !== 'PREVIEW' && action !== 'DOWNLOAD'))
        throw new ProtectedFileError('INVALID_FILE');
      protectedFileFormat(file);
      state.action = action;
      state.fileId = file.id;
      expireAt(Date.parse(user.expiresAt), ticket, actor);
      const authorization = await options.api.authorize(paperId, file.id, action);
      if (!current(ticket, actor)) return;
      validateFileAuthorization(authorization, options);
      if (authorization.file.id !== file.id) throw new ProtectedFileError('INVALID_FILE');
      expireAt(
        Math.min(Date.parse(authorization.expiresAt), Date.parse(user.expiresAt)),
        ticket,
        actor,
      );
      if (!current(ticket, actor)) return;
      await (action === 'DOWNLOAD'
        ? options.platform.save(authorization)
        : options.platform.preview(authorization));
    } catch (error) {
      if (!current(ticket, actor)) return;
      clear();
      const safe = protectedFileError(error, 'AUTHORIZATION_FAILED');
      state.error = safe.message;
      state.errorCode = safe.code;
    } finally {
      if (current(ticket, actor)) state.busy = false;
    }
  }
  return {
    state,
    open,
    clear,
    hide: clear,
    dispose() {
      clear();
      disposed = true;
      unsubscribe();
    },
  };
}
export type ProtectedFileController = ReturnType<typeof createProtectedFileController>;
