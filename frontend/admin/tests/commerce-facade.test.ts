// @vitest-environment jsdom
import { expect, it, vi } from 'vitest';
const request = vi.hoisted(() => vi.fn().mockResolvedValue({ items: [], total: 0 }));
vi.mock('../src/api/client', () => ({ client: { request } }));
vi.mock('../src/api/environment', () => ({
  environment: { mode: 'test', provider: 'http', apiBaseUrl: '/api' },
}));
import { commerceApi } from '../src/api/commerce';
import { operationNamespace, operationStorage, session } from '../src/api/session';
import { createOperationController } from '../../shared/src/commerce/operation';
it('reads BOOK inventory through the commerce boundary without requiring content permission', async () => {
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: ['ORDER_OPERATOR'],
    user: { id: 'warehouse', nickname: 'Warehouse', distributor: false },
  });
  await commerceApi.products({ keyword: '语文', page: 2, pageSize: 20 });
  expect(request).toHaveBeenCalledWith('/admin/inventory', {
    query: { keyword: '语文', page: 2, pageSize: 20 },
  });
});
it('clears the persisted operation journal on logout even after its page controller was disposed', async () => {
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: ['ORDER_OPERATOR'],
    user: { id: 'warehouse', nickname: 'Warehouse', distributor: false },
  });
  const controller = createOperationController({
    session,
    storage: operationStorage,
    namespace: operationNamespace,
    scope: 'inventory:test',
    execute: async () => ({ id: 'book' }),
    query: async () => ({ state: 'PROCESSING' }),
    keyGenerator: () => '00000000-0000-4000-8000-000000000001',
  });
  await controller.submit({ delta: 1 });
  controller.dispose();
  const journalKey = `jxsc.operation:${JSON.stringify([operationNamespace, 'warehouse', 'inventory:test'])}`;
  expect(operationStorage.get(journalKey)).not.toBeNull();
  session.clear();
  expect(operationStorage.get(journalKey)).toBeNull();
  expect(JSON.parse(operationNamespace)).toEqual(['admin', 'test', 'http', '/api']);
});

it('maps the dedicated inventory detail counters and its independent version without a content read', async () => {
  session.set({
    accessToken: 't',
    expiresAt: '2099-01-01T00:00:00Z',
    permissions: ['ORDER_OPERATE'],
    user: { id: 'warehouse', nickname: 'Warehouse', distributor: false },
  });
  request.mockClear();
  request.mockResolvedValueOnce({
    productId: 'book/1',
    onHand: 11,
    reserved: 2,
    availableStock: 9,
    version: 7,
  });
  const value = await commerceApi.product('book/1');
  expect(request).toHaveBeenCalledWith('/admin/products/book%2F1/inventory');
  expect(value).toMatchObject({
    id: 'book/1',
    type: 'BOOK',
    book: { physicalStock: 11, lockedStock: 2, availableStock: 9, inventoryVersion: 7 },
  });
  expect(request).toHaveBeenCalledTimes(1);
});
