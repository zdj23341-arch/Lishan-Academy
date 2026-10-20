// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSession } from '../../shared/src/session';
import { createProtectedFileController } from '../../shared/src/learning/files';
import type { FileAuthorization } from '../../shared/src/learning/types';
import {
  createH5ProtectedFilePlatform,
  createNativeProtectedFilePlatform,
  type ProtectedFileNativeRuntime,
} from '../src/api/protected-files';

const pdf: FileAuthorization = {
  file: { id: 'pdf', name: 'Paper.pdf', extension: 'PDF', mime: 'application/pdf', bytes: 20 },
  url: 'https://files.test/paper?signature=secret',
  expiresAt: '2026-10-07T00:01:00Z',
};
const png: FileAuthorization = {
  ...pdf,
  file: { id: 'png', name: 'Paper.png', extension: 'PNG', mime: 'image/png', bytes: 20 },
};
function native() {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-07T00:00:00Z'));
  const abort = vi.fn();
  const unlink = vi.fn((options: { filePath: string; success?: () => void }) =>
    options.success?.(),
  );
  const runtime = {
    downloadFile: vi.fn<NonNullable<ProtectedFileNativeRuntime['downloadFile']>>((options) => {
      options.success({ statusCode: 200, tempFilePath: '/tmp/paper' });
      return { abort };
    }),
    openDocument: vi.fn<NonNullable<ProtectedFileNativeRuntime['openDocument']>>((options) =>
      options.success(),
    ),
    previewImage: vi.fn<NonNullable<ProtectedFileNativeRuntime['previewImage']>>((options) =>
      options.success(),
    ),
    saveImageToPhotosAlbum: vi.fn<
      NonNullable<ProtectedFileNativeRuntime['saveImageToPhotosAlbum']>
    >((options) => options.success()),
    shareFileMessage: vi.fn<NonNullable<ProtectedFileNativeRuntime['shareFileMessage']>>(
      (options) => options.success(),
    ),
    openSetting: vi.fn<NonNullable<ProtectedFileNativeRuntime['openSetting']>>((options) =>
      options.success(),
    ),
    closePreviewImage: vi.fn(),
    getFileSystemManager: () => ({ unlink }),
  };
  return { runtime, abort, unlink, platform: createNativeProtectedFilePlatform(runtime) };
}
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('real native protected file adapter', () => {
  it('downloads development file tickets only with the configured API origin', async () => {
    const { runtime } = native();
    const localAuthorization = {
      ...pdf,
      url: 'http://localhost:8080/api/v1/protected-content/token',
      expiresAt: '2099-01-01T00:00:00Z',
    };
    const developmentOptions = {
      developmentHttpApiBaseUrl: 'http://localhost:8080/api/v1',
    };
    const platform = createNativeProtectedFilePlatform(runtime, developmentOptions);
    await platform.preview(localAuthorization);
    expect(runtime.downloadFile).toHaveBeenCalledWith(
      expect.objectContaining({ url: localAuthorization.url }),
    );
    expect(runtime.openDocument).toHaveBeenCalledOnce();
  });
  it.each(['PDF', 'DOC', 'DOCX'] as const)(
    'downloads %s then opens it with an enabled document menu',
    async (extension) => {
      const { platform, runtime } = native();
      const mime = {
        PDF: 'application/pdf',
        DOC: 'application/msword',
        DOCX: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      }[extension];
      await platform.preview({ ...pdf, file: { ...pdf.file, extension, mime } });
      expect(runtime.downloadFile).toHaveBeenCalledWith(expect.objectContaining({ url: pdf.url }));
      expect(runtime.openDocument).toHaveBeenCalledWith(
        expect.objectContaining({
          filePath: '/tmp/paper',
          fileType: extension.toLowerCase(),
          showMenu: true,
        }),
      );
      expect(runtime.shareFileMessage).not.toHaveBeenCalled();
      expect(runtime.saveImageToPhotosAlbum).not.toHaveBeenCalled();
    },
  );
  it('maps DOWNLOAD to actual document sharing through the controller and real adapter', async () => {
    const { platform, runtime } = native();
    const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
    session.set({
      accessToken: 'one',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 'one', nickname: 'One', distributor: false },
    });
    const authorize = vi.fn(async () => pdf);
    const controller = createProtectedFileController({ api: { authorize }, session, platform });
    await controller.open('paper', pdf.file, 'DOWNLOAD');
    expect(authorize).toHaveBeenCalledWith('paper', 'pdf', 'DOWNLOAD');
    expect(runtime.shareFileMessage).toHaveBeenCalledWith(
      expect.objectContaining({ filePath: '/tmp/paper', fileName: 'Paper.pdf' }),
    );
    expect(runtime.openDocument).not.toHaveBeenCalled();
    controller.dispose();
  });
  it('previews local image handles without album permission and saves only on explicit request', async () => {
    const { platform, runtime } = native();
    await platform.preview(png);
    expect(runtime.previewImage).toHaveBeenCalledWith(
      expect.objectContaining({ urls: ['/tmp/paper'], showmenu: false }),
    );
    expect(runtime.saveImageToPhotosAlbum).not.toHaveBeenCalled();
    expect(runtime.openSetting).not.toHaveBeenCalled();
    await platform.save(png);
    expect(runtime.saveImageToPhotosAlbum).toHaveBeenCalledWith(
      expect.objectContaining({ filePath: '/tmp/paper' }),
    );
  });
  it('keeps preview usable after album denial and opens settings only on explicit action', async () => {
    const { platform, runtime } = native();
    runtime.saveImageToPhotosAlbum.mockImplementationOnce((options) =>
      options.fail({ errMsg: `auth deny ${png.url}` }),
    );
    await expect(platform.save(png)).rejects.toMatchObject({ code: 'ALBUM_PERMISSION_DENIED' });
    expect(runtime.openSetting).not.toHaveBeenCalled();
    await platform.preview(png);
    expect(runtime.previewImage).toHaveBeenCalledOnce();
    await platform.openAlbumSettings();
    expect(runtime.openSetting).toHaveBeenCalledOnce();
  });
  it('does not misdirect document sharing failures to album settings', async () => {
    const { platform, runtime } = native();
    runtime.shareFileMessage.mockImplementationOnce((options) =>
      options.fail({ errMsg: `permission denied ${pdf.url}` }),
    );
    await expect(platform.save(pdf)).rejects.toMatchObject({ code: 'SAVE_FAILED' });
    expect(runtime.openSetting).not.toHaveBeenCalled();
  });
  it('does not use document preview as a substitute for unavailable save/share support', async () => {
    const { runtime } = native();
    const platform = createNativeProtectedFilePlatform({ ...runtime, shareFileMessage: undefined });
    await expect(platform.save(pdf)).rejects.toMatchObject({ code: 'SAVE_UNSUPPORTED' });
    expect(runtime.downloadFile).not.toHaveBeenCalled();
    expect(runtime.openDocument).not.toHaveBeenCalled();
  });
  it('uses an explicit save-to-disk capability when document sharing is absent', async () => {
    const { runtime } = native();
    const saveFileToDisk = vi.fn<NonNullable<ProtectedFileNativeRuntime['saveFileToDisk']>>(
      (options) => options.success(),
    );
    await createNativeProtectedFilePlatform({
      ...runtime,
      shareFileMessage: undefined,
      saveFileToDisk,
    }).save(pdf);
    expect(saveFileToDisk).toHaveBeenCalledWith(
      expect.objectContaining({ filePath: '/tmp/paper' }),
    );
    expect(runtime.openDocument).not.toHaveBeenCalled();
  });
  it('fails before download when preview support is missing', async () => {
    const { runtime } = native();
    await expect(
      createNativeProtectedFilePlatform({ ...runtime, openDocument: undefined }).preview(pdf),
    ).rejects.toMatchObject({ code: 'PREVIEW_UNSUPPORTED' });
    expect(runtime.downloadFile).not.toHaveBeenCalled();
  });
  it('rejects URL expiry before transfer and after download before opening', async () => {
    const { platform, runtime, unlink } = native();
    vi.setSystemTime(new Date(pdf.expiresAt));
    await expect(platform.preview(pdf)).rejects.toMatchObject({ code: 'AUTHORIZATION_EXPIRED' });
    expect(runtime.downloadFile).not.toHaveBeenCalled();
    vi.setSystemTime(new Date('2026-10-07T00:00:00Z'));
    runtime.downloadFile.mockImplementationOnce((options) => {
      vi.setSystemTime(new Date(pdf.expiresAt));
      options.success({ statusCode: 200, tempFilePath: '/tmp/expired' });
    });
    await expect(platform.preview(pdf)).rejects.toMatchObject({ code: 'AUTHORIZATION_EXPIRED' });
    expect(runtime.openDocument).not.toHaveBeenCalled();
    expect(unlink).toHaveBeenCalledWith(expect.objectContaining({ filePath: '/tmp/expired' }));
  });
  it('aborts on clear and removes late temporary handles without opening a file', async () => {
    const { platform, runtime, abort, unlink } = native();
    let pending!: Parameters<NonNullable<ProtectedFileNativeRuntime['downloadFile']>>[0];
    runtime.downloadFile.mockImplementationOnce((options) => {
      pending = options;
      return { abort };
    });
    const result = platform.preview(pdf);
    platform.clear();
    pending.success({ statusCode: 200, tempFilePath: '/tmp/late' });
    await expect(result).rejects.toMatchObject({ code: 'CANCELLED' });
    expect(abort).toHaveBeenCalledOnce();
    expect(runtime.openDocument).not.toHaveBeenCalled();
    expect(unlink).toHaveBeenCalledWith(expect.objectContaining({ filePath: '/tmp/late' }));
  });
  it('cleans only downloaded temporary handles after user saving and never deletes a saved destination', async () => {
    const { platform, unlink } = native();
    await platform.save(png);
    platform.clear();
    expect(unlink.mock.calls.map(([options]) => options.filePath)).toEqual(['/tmp/paper']);
  });
  it.each([401, 403, 404, 500])('does not open a failed HTTP download %s', async (statusCode) => {
    const { platform, runtime } = native();
    runtime.downloadFile.mockImplementationOnce((options) =>
      options.success({ statusCode, tempFilePath: '/tmp/error' }),
    );
    await expect(platform.preview(pdf)).rejects.toMatchObject({ code: 'TRANSFER_FAILED' });
    expect(runtime.openDocument).not.toHaveBeenCalled();
  });
  it('rethrows only fixed safe diagnostics on native errors', async () => {
    const { platform, runtime } = native();
    runtime.openDocument.mockImplementationOnce((options) => options.fail({ errMsg: pdf.url }));
    const error = await platform.preview(pdf).catch((value: unknown) => value);
    expect(String(error)).not.toContain('signature');
    expect(error).toMatchObject({ code: 'PREVIEW_FAILED' });
  });
});

function h5() {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-07T00:00:00Z'));
  const popup = { location: { href: '' }, close: vi.fn(), closed: false };
  const fetchFile = vi.fn(
    async () =>
      ({ ok: true, blob: async () => new Blob(['test'], { type: 'application/pdf' }) }) as Response,
  );
  const createObjectURL = vi.fn(() => 'blob:protected-test');
  const revokeObjectURL = vi.fn();
  const open = vi.fn(() => popup);
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  const platform = createH5ProtectedFilePlatform({
    document,
    fetch: fetchFile,
    open,
    createObjectURL,
    revokeObjectURL,
  });
  return { platform, popup, fetchFile, createObjectURL, revokeObjectURL, open, click };
}
describe('real H5 protected file adapter', () => {
  it('saves via a blob download without opening a preview and revokes the URL on clear', async () => {
    const { platform, fetchFile, open, click, revokeObjectURL } = h5();
    await platform.save(pdf);
    expect(fetchFile).toHaveBeenCalledWith(
      pdf.url,
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'omit',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
      }),
    );
    expect(click).toHaveBeenCalledOnce();
    const anchor = click.mock.instances[0] as HTMLAnchorElement;
    expect(anchor.download).toBe('Paper.pdf');
    expect(anchor.href).toBe('blob:protected-test');
    expect(open).not.toHaveBeenCalled();
    platform.clear();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:protected-test');
  });
  it('uses only an object URL in preview windows and closes it on hide cleanup', async () => {
    const { platform, open, popup, revokeObjectURL, click } = h5();
    await platform.preview(pdf);
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(popup.location.href).toBe('blob:protected-test');
    expect(click).not.toHaveBeenCalled();
    platform.clear();
    expect(popup.close).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:protected-test');
  });
  it('rejects blocked popups and unsupported Word preview with actionable capability errors', async () => {
    const { platform, open, fetchFile } = h5();
    open.mockReturnValueOnce(null as unknown as ReturnType<typeof open>);
    await expect(platform.preview(pdf)).rejects.toMatchObject({ code: 'PREVIEW_UNSUPPORTED' });
    await expect(
      platform.preview({
        ...pdf,
        file: { ...pdf.file, extension: 'DOC', mime: 'application/msword' },
      }),
    ).rejects.toMatchObject({ code: 'PREVIEW_UNSUPPORTED' });
    expect(fetchFile).not.toHaveBeenCalled();
  });
  it('rejects a failed or mismatched file response without initiating a download', async () => {
    const { platform, fetchFile, click } = h5();
    fetchFile.mockResolvedValueOnce({ ok: false } as Response);
    await expect(platform.save(pdf)).rejects.toMatchObject({ code: 'TRANSFER_FAILED' });
    fetchFile.mockResolvedValueOnce({
      ok: true,
      blob: async () => new Blob(['html'], { type: 'text/html' }),
    } as Response);
    await expect(platform.save(pdf)).rejects.toMatchObject({ code: 'INVALID_FILE' });
    expect(click).not.toHaveBeenCalled();
  });
  it('aborts a pending response on clear and never starts a late browser download', async () => {
    const { platform, fetchFile, click, createObjectURL } = h5();
    let resolve!: (value: Response) => void;
    fetchFile.mockReturnValueOnce(
      new Promise<Response>((done) => {
        resolve = done;
      }),
    );
    const pending = platform.save(pdf);
    platform.clear();
    resolve({
      ok: true,
      blob: async () => new Blob(['test'], { type: 'application/pdf' }),
    } as Response);
    await expect(pending).rejects.toMatchObject({ code: 'CANCELLED' });
    expect(click).not.toHaveBeenCalled();
    expect(createObjectURL).not.toHaveBeenCalled();
  });
});
