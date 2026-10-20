import { expect, it, vi } from 'vitest';
import { ApiError } from '../src/http';
import { createUploadController } from '../src/content/upload';
import type { AdminAsset } from '../src/content/admin-types';
const ready: AdminAsset = {
  id: 'file-1',
  version: 1,
  name: '试卷.pdf',
  extension: 'PDF',
  mime: 'application/pdf',
  bytes: 4,
  purpose: 'PAPER_ATTACHMENT',
  status: 'READY',
  scanStatus: 'CLEAN',
  publicUrl: null,
};
const file = {
  name: ready.name,
  mime: ready.mime,
  bytes: 4,
  purpose: 'PAPER_ATTACHMENT' as const,
  source: new Uint8Array(4),
};
function services() {
  return {
    presign: vi.fn(async () => ({
      id: 'file-1',
      uploadUrl: '/upload',
      uploadToken: 't',
      expiresAt: '2099-01-01T00:00:00Z',
    })),
    transfer: vi.fn(async () => {}),
    complete: vi.fn(async () => ready),
    read: vi.fn(async () => ready),
  };
}
it('keeps processing separate from failure and resumes the same object without reuploading', async () => {
  const api = services();
  api.complete.mockRejectedValueOnce(new ApiError('FILE_PROCESSING', '处理中', null, {}, 409));
  const controller = createUploadController(api);
  await expect(controller.start(file)).rejects.toMatchObject({ code: 'FILE_PROCESSING' });
  expect(controller.state.phase).toBe('PROCESSING');
  expect(controller.state.asset).toBeNull();
  await expect(controller.retry()).resolves.toMatchObject({ id: 'file-1', status: 'READY' });
  expect(api.read).toHaveBeenCalledWith('file-1');
  expect(api.presign).toHaveBeenCalledTimes(1);
  expect(api.transfer).toHaveBeenCalledTimes(1);
});
it('continues verification for an uploaded object after completion loses its response', async () => {
  const api = services();
  api.complete.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '断网'));
  api.read.mockResolvedValueOnce({
    ...ready,
    status: 'UPLOADED',
    scanStatus: 'VERIFICATION_ERROR',
  });
  const controller = createUploadController(api);
  await expect(controller.start(file)).rejects.toThrow();
  expect(controller.state.phase).toBe('PROCESSING');
  await controller.retry();
  expect(api.complete.mock.calls).toEqual([['file-1'], ['file-1']]);
  expect(api.transfer).toHaveBeenCalledTimes(1);
});
it('does not retry verification or bind a disabled object', async () => {
  const api = services();
  api.complete.mockRejectedValueOnce(new ApiError('FILE_PROCESSING', '处理中', null, {}, 409));
  api.read.mockResolvedValueOnce({ ...ready, status: 'DISABLED' });
  const controller = createUploadController(api);
  await expect(controller.start(file)).rejects.toThrow();
  await expect(controller.retry()).rejects.toThrow();
  expect(controller.state.phase).toBe('DISABLED');
  expect(controller.state.asset).toBeNull();
  expect(api.complete).toHaveBeenCalledTimes(1);
});
it('keeps an uploaded PROCESSING response pending and does not emit a usable asset', async () => {
  const api = services();
  api.complete.mockResolvedValueOnce({ ...ready, status: 'UPLOADED', scanStatus: 'PROCESSING' });
  const controller = createUploadController(api);
  await expect(controller.start(file)).rejects.toThrow();
  expect(controller.state.phase).toBe('PROCESSING');
  expect(controller.state.asset).toBeNull();
});
it('treats rejected files as failed rather than indefinitely processing', async () => {
  const api = services();
  api.complete.mockResolvedValueOnce({
    ...ready,
    status: 'REJECTED',
    scanStatus: 'FORMAT_REJECTED',
  });
  const controller = createUploadController(api);
  await expect(controller.start(file)).rejects.toThrow();
  expect(controller.state.phase).toBe('FAILED');
});
