import {
  ProtectedFileError,
  protectedFileError,
  validateFileAuthorization,
  type FileAuthorization,
  type ProtectedFileErrorCode,
  type ProtectedFilePlatform,
  type ProtectedFileValidationOptions,
} from '@jxsc/shared';

interface Callbacks {
  success(): void;
  fail(error: unknown): void;
}
export interface ProtectedFileNativeRuntime {
  downloadFile?(options: {
    url: string;
    success(result: { statusCode: number; tempFilePath: string }): void;
    fail(error: unknown): void;
  }): { abort(): void } | void;
  openDocument?(
    options: Callbacks & { filePath: string; fileType: string; showMenu: boolean },
  ): unknown;
  previewImage?(
    options: Callbacks & { urls: string[]; current: string; showmenu: boolean },
  ): unknown;
  saveImageToPhotosAlbum?(options: Callbacks & { filePath: string }): unknown;
  shareFileMessage?(options: Callbacks & { filePath: string; fileName: string }): unknown;
  saveFileToDisk?(options: Callbacks & { filePath: string }): unknown;
  openSetting?(options: Callbacks): unknown;
  closePreviewImage?(options: { success(): void; fail(): void }): unknown;
  getFileSystemManager?(): {
    unlink(options: { filePath: string; success(): void; fail(): void }): unknown;
  };
}
function nativeFailure(error: unknown, fallback: ProtectedFileErrorCode, album = false) {
  const message =
    error && typeof error === 'object' && 'errMsg' in error && typeof error.errMsg === 'string'
      ? error.errMsg
      : '';
  if (/cancel/i.test(message)) return new ProtectedFileError('CANCELLED');
  if (album && /auth|denied|deny|permission/i.test(message))
    return new ProtectedFileError('ALBUM_PERMISSION_DENIED');
  return new ProtectedFileError(fallback);
}
export function createNativeProtectedFilePlatform(
  runtime: ProtectedFileNativeRuntime = uni,
  options: ProtectedFileValidationOptions = {},
): ProtectedFilePlatform & { openAlbumSettings(): Promise<void> } {
  let generation = 0;
  let imagePreviewActive = false;
  const temporary = new Set<string>();
  const cancellations = new Set<() => void>();
  function remove(path: string) {
    temporary.delete(path);
    try {
      runtime
        .getFileSystemManager?.()
        .unlink({ filePath: path, success: () => {}, fail: () => {} });
    } catch {
      /* Best effort for downloaded temp files only. */
    }
  }
  function clear() {
    generation++;
    for (const cancel of [...cancellations]) cancel();
    for (const path of [...temporary]) remove(path);
    if (imagePreviewActive) {
      imagePreviewActive = false;
      try {
        runtime.closePreviewImage?.({ success: () => {}, fail: () => {} });
      } catch {
        /* No close-document API exists on all platforms. */
      }
    }
  }
  function check(ticket: number, auth: FileAuthorization) {
    if (ticket !== generation) throw new ProtectedFileError('CANCELLED');
    validateFileAuthorization(auth, options);
  }
  function call(
    invoke: (callbacks: Callbacks) => unknown,
    code: ProtectedFileErrorCode,
    album = false,
  ) {
    return new Promise<void>((resolve, reject) => {
      const finish = (error?: ProtectedFileError) => {
        cancellations.delete(cancel);
        if (error) reject(error);
        else resolve();
      };
      const cancel = () => finish(new ProtectedFileError('CANCELLED'));
      cancellations.add(cancel);
      try {
        invoke({
          success: () => finish(),
          fail: (error) => finish(nativeFailure(error, code, album)),
        });
      } catch (error) {
        finish(nativeFailure(error, code, album));
      }
    });
  }
  function download(auth: FileAuthorization, ticket: number): Promise<string> {
    return new Promise((resolve, reject) => {
      let task: { abort(): void } | void;
      let settled = false;
      const finish = (error?: ProtectedFileError, path?: string) => {
        settled = true;
        cancellations.delete(cancel);
        if (error) reject(error);
        else resolve(path!);
      };
      const cancel = () => {
        finish(new ProtectedFileError('CANCELLED'));
        try {
          task?.abort();
        } catch {
          /* Native abort can throw after completion. */
        }
      };
      cancellations.add(cancel);
      try {
        task = runtime.downloadFile!({
          url: auth.url,
          success(response) {
            const path = response.tempFilePath;
            if (path) temporary.add(path);
            if (settled || ticket !== generation) {
              if (path) remove(path);
              finish(new ProtectedFileError('CANCELLED'));
            } else if (response.statusCode !== 200 || !path) {
              if (path) remove(path);
              finish(new ProtectedFileError('TRANSFER_FAILED'));
            } else finish(undefined, path);
          },
          fail: (error) => finish(nativeFailure(error, 'TRANSFER_FAILED')),
        });
      } catch (error) {
        finish(nativeFailure(error, 'TRANSFER_FAILED'));
      }
    });
  }
  async function perform(auth: FileAuthorization, save: boolean) {
    const format = validateFileAuthorization(auth, options);
    const image = auth.file.mime.toLowerCase().startsWith('image/');
    const documentSave = runtime.shareFileMessage ?? runtime.saveFileToDisk;
    const supported = save
      ? image
        ? runtime.saveImageToPhotosAlbum
        : documentSave
      : image
        ? runtime.previewImage
        : runtime.openDocument;
    if (!supported) throw new ProtectedFileError(save ? 'SAVE_UNSUPPORTED' : 'PREVIEW_UNSUPPORTED');
    if (!runtime.downloadFile) throw new ProtectedFileError('TRANSFER_FAILED');
    clear();
    const ticket = generation;
    let path: string | undefined;
    try {
      path = await download(auth, ticket);
      check(ticket, auth);
      if (save && image)
        await call(
          (callbacks) => runtime.saveImageToPhotosAlbum!({ ...callbacks, filePath: path! }),
          'SAVE_FAILED',
          true,
        );
      else if (save && runtime.shareFileMessage)
        await call(
          (callbacks) =>
            runtime.shareFileMessage!({
              ...callbacks,
              filePath: path!,
              fileName: safeFileName(auth),
            }),
          'SAVE_FAILED',
        );
      else if (save)
        await call(
          (callbacks) => runtime.saveFileToDisk!({ ...callbacks, filePath: path! }),
          'SAVE_FAILED',
        );
      else if (image) {
        imagePreviewActive = true;
        await call(
          (callbacks) =>
            runtime.previewImage!({ ...callbacks, urls: [path!], current: path!, showmenu: false }),
          'PREVIEW_FAILED',
        );
      } else
        await call(
          (callbacks) =>
            runtime.openDocument!({
              ...callbacks,
              filePath: path!,
              fileType: format.toLowerCase(),
              showMenu: true,
            }),
          'PREVIEW_FAILED',
        );
      check(ticket, auth);
    } catch (error) {
      if (path && temporary.has(path)) remove(path);
      throw protectedFileError(error, save ? 'SAVE_FAILED' : 'PREVIEW_FAILED');
    }
  }
  return {
    preview: (auth) => perform(auth, false),
    save: (auth) => perform(auth, true),
    clear,
    async openAlbumSettings() {
      if (!runtime.openSetting) throw new ProtectedFileError('SETTINGS_UNSUPPORTED');
      await call((callbacks) => runtime.openSetting!(callbacks), 'SETTINGS_UNSUPPORTED');
    },
  };
}
function safeFileName(auth: FileAuthorization) {
  const name = [...auth.file.name]
    .filter((character) => character.charCodeAt(0) >= 32 && !'/\\:*?"<>|'.includes(character))
    .join('')
    .slice(0, 160);
  return name || `file.${auth.file.extension.toLowerCase()}`;
}
interface PreviewWindow {
  location: { href: string };
  close(): void;
  opener?: unknown;
}
export interface ProtectedFileBrowserRuntime {
  document?: Document;
  fetch?: typeof fetch;
  open?(url: string, target: string): PreviewWindow | null;
  createObjectURL?(blob: Blob): string;
  revokeObjectURL?(url: string): void;
}
export function createH5ProtectedFilePlatform(
  runtime: ProtectedFileBrowserRuntime = {
    document: globalThis.document,
    fetch: globalThis.fetch?.bind(globalThis),
    open: globalThis.open?.bind(globalThis),
    createObjectURL: globalThis.URL?.createObjectURL?.bind(globalThis.URL),
    revokeObjectURL: globalThis.URL?.revokeObjectURL?.bind(globalThis.URL),
  },
  options: ProtectedFileValidationOptions = {},
): ProtectedFilePlatform {
  let generation = 0;
  const requests = new Set<AbortController>();
  const urls = new Set<string>();
  const windows = new Set<PreviewWindow>();
  function clear() {
    generation++;
    for (const request of requests) request.abort();
    requests.clear();
    for (const popup of windows) {
      try {
        popup.close();
      } catch {
        /* Already closed by user. */
      }
    }
    windows.clear();
    for (const url of urls) {
      try {
        runtime.revokeObjectURL?.(url);
      } catch {
        /* Best effort browser cleanup. */
      }
    }
    urls.clear();
  }
  function check(ticket: number, auth: FileAuthorization) {
    if (ticket !== generation) throw new ProtectedFileError('CANCELLED');
    validateFileAuthorization(auth, options);
  }
  async function perform(auth: FileAuthorization, save: boolean) {
    const format = validateFileAuthorization(auth, options);
    const code = save ? 'SAVE_UNSUPPORTED' : 'PREVIEW_UNSUPPORTED';
    if (
      !runtime.fetch ||
      !runtime.createObjectURL ||
      !runtime.revokeObjectURL ||
      typeof AbortController === 'undefined'
    )
      throw new ProtectedFileError(code);
    if (!save && (format === 'DOC' || format === 'DOCX' || !runtime.open))
      throw new ProtectedFileError(code);
    const anchor = save ? runtime.document?.createElement('a') : undefined;
    if (save && (!anchor || !('download' in anchor))) throw new ProtectedFileError(code);
    clear();
    const ticket = generation;
    let popup: PreviewWindow | null = null;
    let url: string | undefined;
    const request = new AbortController();
    requests.add(request);
    try {
      if (!save) {
        popup = runtime.open!('', '_blank');
        if (!popup) throw new ProtectedFileError('PREVIEW_UNSUPPORTED');
        popup.opener = null;
        windows.add(popup);
      }
      const response = await runtime.fetch(auth.url, {
        signal: request.signal,
        cache: 'no-store',
        credentials: 'omit',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
      });
      check(ticket, auth);
      if (!response.ok) throw new ProtectedFileError('TRANSFER_FAILED');
      const blob = await response.blob();
      check(ticket, auth);
      if (!blob.size || blob.type.toLowerCase() !== auth.file.mime.toLowerCase())
        throw new ProtectedFileError('INVALID_FILE');
      url = runtime.createObjectURL(blob);
      urls.add(url);
      if (save) {
        anchor!.href = url;
        anchor!.download = safeFileName(auth);
        anchor!.rel = 'noopener noreferrer';
        runtime.document!.body.appendChild(anchor!);
        try {
          anchor!.click();
        } finally {
          anchor!.remove();
        }
      } else popup!.location.href = url;
    } catch (error) {
      if (popup && windows.delete(popup)) {
        try {
          popup.close();
        } catch {
          /* Closed window. */
        }
      }
      if (url && urls.delete(url)) {
        try {
          runtime.revokeObjectURL(url);
        } catch {
          /* Browser cleanup failure. */
        }
      }
      throw protectedFileError(error, request.signal.aborted ? 'CANCELLED' : 'TRANSFER_FAILED');
    } finally {
      requests.delete(request);
    }
  }
  return { preview: (auth) => perform(auth, false), save: (auth) => perform(auth, true), clear };
}

/** One instance per controller/page; caller must clear on hide and dispose on unload. */
export function createProtectedFilePlatform(
  options: ProtectedFileValidationOptions = {},
): ProtectedFilePlatform & { openAlbumSettings?: () => Promise<void> } {
  // #ifdef H5
  return createH5ProtectedFilePlatform(undefined, options);
  // #endif
  // #ifndef H5
  return createNativeProtectedFilePlatform(undefined, options);
  // #endif
}
