// @vitest-environment jsdom
import { expect, it, vi, afterEach } from 'vitest';
import { fetchPrivateExport } from '../src/api/export-download';
import type { ExportTask } from '@jxsc/shared';

afterEach(() => vi.unstubAllGlobals());
const task: ExportTask = {
  id: '23',
  type: 'ANALYTICS',
  status: 'SUCCEEDED',
  downloadUrl: '/api/v1/admin/exports/23/content',
  expiresAt: '2099-01-01T00:00:00Z',
};
it('fetches protected export bytes from the API origin with Bearer authentication', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue({ ok: true, blob: async () => new Blob(['date,total\n2026-10-08,3']) });
  vi.stubGlobal('fetch', fetcher);
  const blob = await fetchPrivateExport(task, {
    apiBaseUrl: 'https://api.example/api/v1',
    accessToken: 'admin-token',
  });
  expect(blob.size).toBeGreaterThan(0);
  expect(fetcher).toHaveBeenCalledWith(
    'https://api.example/api/v1/admin/exports/23/content',
    expect.objectContaining({
      headers: { Authorization: 'Bearer admin-token' },
      redirect: 'error',
    }),
  );
});
it.each([
  'https://other.example/api/v1/admin/exports/23/content',
  '/api/v1/admin/exports/other/content',
  'javascript:alert(1)',
])('never forwards a token to an unverified export URL: %s', async (url) => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  await expect(
    fetchPrivateExport(
      { ...task, downloadUrl: url },
      { apiBaseUrl: 'https://api.example/api/v1', accessToken: 'admin-token' },
    ),
  ).rejects.toThrow();
  expect(fetcher).not.toHaveBeenCalled();
});
it('rejects expired links before downloading', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  await expect(
    fetchPrivateExport(
      { ...task, expiresAt: '2000-01-01T00:00:00Z' },
      { apiBaseUrl: 'https://api.example/api/v1', accessToken: 'admin-token' },
    ),
  ).rejects.toThrow();
  expect(fetcher).not.toHaveBeenCalled();
});
