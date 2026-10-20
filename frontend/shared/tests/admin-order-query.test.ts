import { expect, it } from 'vitest';
import {
  adminOrderQuery,
  emptyOrderFilters,
  refundFilterOptions,
} from '../src/commerce/order-query';

it('keeps the three independent states and converts local date inputs to explicit ISO bounds', () => {
  expect(
    adminOrderQuery(
      {
        ...emptyOrderFilters(),
        orderNo: '  ORDER-1  ',
        orderStatus: 'PAID',
        fulfillmentStatus: 'SHIPPED',
        refundStatus: 'REQUESTED',
        createdFrom: '2026-10-01T10:00',
        createdTo: '2026-10-02T10:00',
      },
      'all',
      2,
    ),
  ).toEqual({
    orderNo: 'ORDER-1',
    orderStatus: 'PAID',
    fulfillmentStatus: 'SHIPPED',
    refundStatus: 'REQUESTED',
    from: new Date('2026-10-01T10:00').toISOString(),
    to: new Date('2026-10-02T10:00').toISOString(),
    page: 2,
    pageSize: 20,
  });
});
it('uses backend refund states instead of the unsupported PENDING value', () => {
  expect(refundFilterOptions.map((option) => option.value)).toEqual([
    'NONE',
    'REQUESTED',
    'RETURN_REQUIRED',
    'RETURNING',
    'PROCESSING',
    'PARTIAL',
    'FULL',
    'REJECTED',
    'FAILED',
  ]);
  expect(() => adminOrderQuery({ ...emptyOrderFilters(), refundStatus: 'PENDING' }, 'all')).toThrow(
    '退款状态',
  );
  expect(adminOrderQuery(emptyOrderFilters(), 'AFTERSALE').refundStatus).toBe('REQUESTED');
});
it('rejects equal/reversed time bounds and invalid dates before sending a request', () => {
  expect(() =>
    adminOrderQuery(
      { ...emptyOrderFilters(), createdFrom: '2026-10-01T10:00', createdTo: '2026-10-01T10:00' },
      'all',
    ),
  ).toThrow('开始时间');
  expect(() => adminOrderQuery({ ...emptyOrderFilters(), createdFrom: 'invalid' }, 'all')).toThrow(
    '有效时间',
  );
});
it('maps each tab to its own dimension and preserves other conditions', () => {
  const filters = { ...emptyOrderFilters(), orderStatus: 'PAID', refundStatus: 'NONE' };
  expect(adminOrderQuery(filters, 'PENDING_SHIPMENT')).toMatchObject({
    orderStatus: 'PAID',
    fulfillmentStatus: 'PENDING_SHIPMENT',
    refundStatus: 'NONE',
  });
  expect(adminOrderQuery(filters, 'COMPLETED')).toMatchObject({
    orderStatus: 'COMPLETED',
    refundStatus: 'NONE',
  });
  expect(() => adminOrderQuery(filters, 'invented')).toThrow('订单筛选');
});
it('creates independent reset values and enforces the backend order number limit', () => {
  const a = emptyOrderFilters();
  a.orderNo = 'old';
  expect(emptyOrderFilters().orderNo).toBe('');
  expect(() => adminOrderQuery({ ...emptyOrderFilters(), orderNo: 'x'.repeat(65) }, 'all')).toThrow(
    '64',
  );
});
