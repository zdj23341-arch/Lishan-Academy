import { expect, it, vi } from 'vitest';
import { createAdminOperationsApi } from '../src/operations/admin-api';

it('requires explicit valid export dates and refuses unsupported filters instead of silently exporting all records', async () => {
  const request = vi.fn().mockResolvedValue({ id: '31', type: 'ORDERS', status: 'REQUESTED' });
  const api = createAdminOperationsApi({ request });
  await expect(api.createExport('ORDERS')).rejects.toThrow();
  await expect(api.createExport('ORDERS', '2025-01-01', '2026-10-08')).rejects.toThrow();
  await expect(
    api.createExport({
      type: 'ORDERS',
      startDate: '2026-10-01',
      endDate: '2026-10-08',
      filters: { status: 'PAID' },
    }),
  ).rejects.toThrow();
  expect(request).not.toHaveBeenCalled();
  expect(await api.createExport('ORDERS', '2026-10-01', '2026-10-08')).toMatchObject({
    status: 'REQUESTED',
  });
  expect(request).toHaveBeenCalledWith('/admin/exports', {
    method: 'POST',
    body: { type: 'ORDERS', startDate: '2026-10-01', endDate: '2026-10-08' },
  });
});
