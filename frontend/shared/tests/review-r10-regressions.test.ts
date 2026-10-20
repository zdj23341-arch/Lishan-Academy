import { expect, it } from 'vitest';
import * as shared from '../src/index';
import { createAdminCommerceApi } from '../src/commerce/admin-api';
import { adminOrderQuery, emptyOrderFilters } from '../src/commerce/order-query';

it.each([
  'all',
  'PENDING_PAYMENT',
  'PAID',
  'PENDING_SHIPMENT',
  'SHIPPED',
  'COMPLETED',
  'AFTERSALE',
  'CANCELLED',
])('sends no empty optional filters to the real order contract for tab %s', async (tab) => {
  let sent = '';
  const session = shared.createSession({ get: () => null, set: () => {}, remove: () => {} });
  const client = shared.createClient({
    session,
    baseUrl: '/api/v1',
    transport: async (request) => {
      sent = request.url;
      return {
        status: 200,
        body: { code: 'OK', message: '', requestId: 'r10', data: { items: [], total: 0 } },
      };
    },
  });
  await createAdminCommerceApi(client).orders(adminOrderQuery(emptyOrderFilters(), tab));
  const query = new URL(sent, 'https://local.test').searchParams;
  expect([...query.values()]).not.toContain('');
  expect(query.get('page')).toBe('1');
  expect(query.get('pageSize')).toBe('20');
});

it('also omits blank legacy order API arguments while preserving explicit filters', async () => {
  let sent = '';
  const client = shared.createClient({
    session: shared.createSession({ get: () => null, set: () => {}, remove: () => {} }),
    baseUrl: '/api/v1',
    transport: async (request) => {
      sent = request.url;
      return { status: 200, body: { code: 'OK', message: '', requestId: 'r10', data: {} } };
    },
  });
  await createAdminCommerceApi(client).orders({
    orderStatus: 'PAID',
    fulfillmentStatus: '',
    refundStatus: '',
    orderNo: '  ',
    page: 2,
  });
  const query = new URL(sent, 'https://local.test').searchParams;
  expect(query.get('orderStatus')).toBe('PAID');
  expect(query.get('page')).toBe('2');
  expect(query.has('fulfillmentStatus')).toBe(false);
  expect(query.has('refundStatus')).toBe(false);
  expect(query.has('orderNo')).toBe(false);
});

it('formats explicit instants as Shanghai wall time, including midnight and offsets', () => {
  expect(shared.formatShanghaiDateTime('2026-10-08T08:26:13.181715Z')).toBe('2026-10-08 16:26');
  expect(shared.formatShanghaiDateTime('2026-10-09T23:30:00Z')).toBe('2026-10-10 07:30');
  expect(shared.formatShanghaiDateTime('2026-10-10T01:30:00+02:00')).toBe('2026-10-10 07:30');
  expect(shared.formatShanghaiDateTime('2026-10-10T07:30:00+08:00')).toBe('2026-10-10 07:30');
});

it('does not interpret missing, invalid or timezone-less timestamps as known wall times', () => {
  expect(shared.formatShanghaiDateTime(null)).toBe('—');
  expect(shared.formatShanghaiDateTime('bad')).toBe('—');
  expect(shared.formatShanghaiDateTime('2026-10-08T08:26:00')).toBe('—');
  expect(shared.formatShanghaiDateTime('2026-10-08')).toBe('—');
});
it('remembers encoded resource IDs in an internal login-return query without accepting encoded route escapes', () => {
  const session = shared.createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.rememberReturnTo('/pages/order/confirm?productId=real%2Fsix&quantity=1');
  expect(session.takeReturnTo()).toBe('/pages/order/confirm?productId=real%2Fsix&quantity=1');
  for (const route of [
    '//evil.test',
    '/%2Fevil.test',
    '/pages/%2F%2Fescape',
    '/pages/path?bad=%00',
    '/pages/path?bad=%5c',
  ]) {
    expect(shared.isInternalRoute(route)).toBe(false);
  }
});
