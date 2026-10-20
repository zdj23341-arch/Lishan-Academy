import { afterEach, describe, expect, it, vi } from 'vitest';
import { createUploadTransfer } from '../src/content/upload-transfer';
import { validateUpload } from '../src/content/upload';

const ticket = {
  id: 'a',
  uploadUrl: 'https://api.example/upload/a',
  uploadToken: 'ticket',
  expiresAt: '2099-01-01T00:00:00Z',
};
const source = new Blob(['file']);
const file = {
  name: 'a.pdf',
  mime: 'application/pdf',
  bytes: 4,
  purpose: 'PAPER_ATTACHMENT' as const,
  source,
};
afterEach(() => vi.useRealTimers());

describe('browser authorized byte transfer', () => {
  it('sends raw bytes with the local actor and single-use ticket, without redirects or cookies', async () => {
    const transfer = createUploadTransfer({
      apiBaseUrl: 'https://api.example/api/v1',
      accessToken: () => 'actor',
      fetch: async (url, options) => {
        expect(String(url)).toBe(ticket.uploadUrl);
        expect(options?.body).toBe(source);
        expect(options?.method).toBe('PUT');
        expect(options?.headers).toEqual({
          'Content-Type': 'application/octet-stream',
          'X-Upload-Token': 'ticket',
          Authorization: 'Bearer actor',
        });
        expect(options?.redirect).toBe('error');
        expect(options?.credentials).toBe('omit');
        return new Response(null, { status: 204 });
      },
    });
    await transfer(ticket, file);
  });
  it('does not send application credentials to external storage', async () => {
    const transfer = createUploadTransfer({
      apiBaseUrl: 'https://api.example/api/v1',
      accessToken: () => {
        throw new Error('Must not read actor');
      },
      fetch: async (_url, options) => {
        expect(options?.headers).not.toHaveProperty('Authorization');
        expect(options?.credentials).toBe('omit');
        return new Response(null, { status: 200 });
      },
    });
    await transfer({ ...ticket, uploadUrl: 'https://objects.example/a?signature=temporary' }, file);
  });
  it('rejects invalid destinations and unavailable local credentials before sending bytes', async () => {
    let sent = false;
    const transfer = createUploadTransfer({
      apiBaseUrl: 'https://api.example/api/v1',
      accessToken: () => null,
      fetch: async () => {
        sent = true;
        return new Response();
      },
    });
    for (const uploadUrl of [
      'file:///secret',
      'https://user:pass@api.example/upload',
      'http://objects.example/a',
    ]) {
      await expect(transfer({ ...ticket, uploadUrl }, file)).rejects.toMatchObject({
        code: 'INVALID_UPLOAD_URL',
      });
    }
    await expect(transfer(ticket, file)).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(sent).toBe(false);
  });
  it('surfaces HTTP failures instead of allowing completion', async () => {
    const transfer = createUploadTransfer({
      apiBaseUrl: 'https://api.example',
      accessToken: () => 'actor',
      fetch: async () => new Response(null, { status: 413 }),
    });
    await expect(transfer(ticket, file)).rejects.toMatchObject({
      code: 'UPLOAD_FAILED',
      status: 413,
    });
  });
  it('aborts a stalled transfer and reports timeout', async () => {
    vi.useFakeTimers();
    const transfer = createUploadTransfer({
      apiBaseUrl: 'https://api.example',
      accessToken: () => 'actor',
      timeoutMs: 100,
      fetch: async (_url, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        }),
    });
    const assertion = expect(transfer(ticket, file)).rejects.toMatchObject({ code: 'TIMEOUT' });
    await Promise.all([assertion, vi.advanceTimersByTimeAsync(100)]);
  });
});

it('enforces the per-format limits and permits a valid 2 GiB course video', () => {
  expect(() => validateUpload({ ...file, bytes: 50 * 1024 * 1024 + 1 })).toThrow();
  expect(() =>
    validateUpload({
      name: 'a.png',
      mime: 'image/png',
      purpose: 'PUBLIC_IMAGE',
      bytes: 10 * 1024 * 1024 + 1,
    }),
  ).toThrow();
  expect(() =>
    validateUpload({
      name: 'a.mp4',
      mime: 'video/mp4',
      purpose: 'COURSE_VIDEO',
      bytes: 2 * 1024 * 1024 * 1024,
    }),
  ).not.toThrow();
});
