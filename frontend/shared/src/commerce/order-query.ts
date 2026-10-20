import type { FulfillmentStatus, OrderStatus, RefundStatus } from '../contracts';
import type { AdminOrderQuery } from './admin-api';

export interface OrderFilters {
  orderNo: string;
  createdFrom: string;
  createdTo: string;
  orderStatus: string;
  fulfillmentStatus: string;
  refundStatus: string;
}
export const refundFilterOptions: Array<{ value: RefundStatus; label: string }> = [
  { value: 'NONE', label: '无售后' },
  { value: 'REQUESTED', label: '售后申请' },
  { value: 'RETURN_REQUIRED', label: '待退货' },
  { value: 'RETURNING', label: '退货中' },
  { value: 'PROCESSING', label: '退款处理中' },
  { value: 'PARTIAL', label: '部分退款' },
  { value: 'FULL', label: '全额退款' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'FAILED', label: '退款失败' },
];
const orderStates: OrderStatus[] = ['PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED'];
const fulfillmentStates: FulfillmentStatus[] = [
  'NOT_REQUIRED',
  'PENDING_SHIPMENT',
  'SHIPPED',
  'RECEIVED',
];
export function emptyOrderFilters(): OrderFilters {
  return {
    orderNo: '',
    createdFrom: '',
    createdTo: '',
    orderStatus: '',
    fulfillmentStatus: '',
    refundStatus: '',
  };
}
function iso(value: string) {
  if (!value) return undefined;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error('请输入有效时间');
  return date.toISOString();
}
export function adminOrderQuery(filters: OrderFilters, tab = 'all', page = 1): AdminOrderQuery {
  const orderNo = filters.orderNo.trim();
  if (orderNo.length > 64) throw new Error('订单号最多64个字符');
  const from = iso(filters.createdFrom),
    to = iso(filters.createdTo);
  if (from && to && from >= to) throw new Error('开始时间必须早于结束时间');
  let { orderStatus, fulfillmentStatus, refundStatus } = filters;
  if (orderStates.includes(tab as OrderStatus)) orderStatus = tab;
  else if (['PENDING_SHIPMENT', 'SHIPPED'].includes(tab)) fulfillmentStatus = tab;
  else if (tab === 'AFTERSALE') refundStatus = 'REQUESTED';
  else if (tab !== 'all') throw new Error('无效订单筛选');
  if (orderStatus && !orderStates.includes(orderStatus as OrderStatus))
    throw new Error('无效订单状态');
  if (fulfillmentStatus && !fulfillmentStates.includes(fulfillmentStatus as FulfillmentStatus))
    throw new Error('无效履约状态');
  if (refundStatus && !refundFilterOptions.some((option) => option.value === refundStatus))
    throw new Error('无效退款状态');
  if (!Number.isSafeInteger(page) || page < 1) throw new Error('无效页码');
  return {
    orderNo: orderNo || undefined,
    orderStatus: orderStatus || undefined,
    fulfillmentStatus: fulfillmentStatus || undefined,
    refundStatus: refundStatus || undefined,
    from,
    to,
    page,
    pageSize: 20,
  };
}
