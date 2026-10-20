import type { OrderView, Page } from '../contracts';
import { ApiError, type ApiClient } from '../http';
import type { Session, SessionStorage } from '../session';
import { createOperationController } from './operation';
import type { OperationOutcome } from './types';

export type OrderTab = 'ALL' | 'UNPAID' | 'UNSHIPPED' | 'SHIPPING' | 'COMPLETED' | 'AFTERSALE';
export interface CustomerOrder extends Omit<
  OrderView,
  | 'orderStatus'
  | 'fulfillmentStatus'
  | 'refundStatus'
  | 'allowedActions'
  | 'paidAmountFen'
  | 'refundedAmountFen'
  | 'item'
> {
  orderStatus: string;
  fulfillmentStatus: string;
  refundStatus: string;
  allowedActions: string[];
  paidAmountFen?: number;
  refundedAmountFen?: number;
  refundedGoodsFen?: number;
  refundedFreightFen?: number;
  version?: number;
  address?: unknown;
  financialHold?: boolean;
  reconciliation?: unknown;
  item: Omit<OrderView['item'], 'coverUrl'> & {
    coverUrl?: string | null;
    courseId?: string | null;
    restoredQuantity?: number;
    fulfillableQuantity?: number;
    shippedQuantity?: number;
  };
}
export interface ShipmentView {
  shippedQuantity?: number;
  carrierCode: string | null;
  trackingNo: string | null;
  status: string | null;
  simulated?: boolean;
  shippedAt: string | null;
  receivedAt: string | null;
  events: { occurredAt: string | null; description: string | null }[];
}
/** Frontend fixture contract only. Confirm actual backend scope names before integration. */
export const ORDER_OPERATION_SCOPES = {
  CANCEL: 'CANCEL_ORDER',
  CONFIRM_RECEIPT: 'CONFIRM_RECEIPT',
  PREPAY: 'PREPAY',
  DEV_PAY: 'DEV_PAY',
  REFUND: 'APPLY_REFUND',
  RETURN_SHIPMENT: 'RETURN_SHIPMENT',
  WECHAT_PREPAY: 'PREPAY',
  REQUEST_REFUND: 'APPLY_REFUND',
} as const;
export function resourcePath(id: string) {
  if (!id.trim()) throw new Error('ID不能为空');
  return encodeURIComponent(id);
}
export function orderStatusLabel(status: string): string {
  return (
    (
      {
        __proto__: null,
        PENDING_PAYMENT: '待付款',
        PAID: '已付款',
        COMPLETED: '已完成',
        CANCELLED: '已取消',
      } as Record<string, string | null>
    )[status] ?? `未知状态（${status}）`
  );
}
export function fulfillmentStatusLabel(status: string): string {
  return (
    (
      {
        __proto__: null,
        NOT_REQUIRED: '无需物流',
        PENDING_SHIPMENT: '待发货',
        SHIPPED: '已发货',
        RECEIVED: '已收货',
      } as Record<string, string | null>
    )[status] ?? `未知状态（${status}）`
  );
}
export function shipmentLabel(
  shipment: Pick<ShipmentView, 'trackingNo' | 'events'> | null,
): string {
  if (!shipment?.trackingNo) return '等待发货';
  return shipment.events.length ? '物流动态' : '物流同步中';
}
export function createOrderApi(client: ApiClient) {
  return {
    orders(tab: OrderTab = 'ALL', page = 1, pageSize = 20) {
      if (
        !Number.isInteger(page) ||
        page < 1 ||
        !Number.isInteger(pageSize) ||
        pageSize < 1 ||
        pageSize > 100
      )
        throw new Error('分页参数无效');
      return client.request<Page<CustomerOrder>>('/orders', { query: { tab, page, pageSize } });
    },
    order: (id: string) => client.request<CustomerOrder>(`/orders/${resourcePath(id)}`),
    cancel: (id: string, key: string) =>
      client.request<CustomerOrder>(`/orders/${resourcePath(id)}/cancel`, {
        method: 'POST',
        body: {},
        idempotencyKey: key,
      }),
    confirmReceipt: (id: string, key: string) =>
      client.request<CustomerOrder>(`/orders/${resourcePath(id)}/confirm-receipt`, {
        method: 'POST',
        body: {},
        idempotencyKey: key,
      }),
    shipment: (id: string) => client.request<ShipmentView | null>(`/shipments/${resourcePath(id)}`),
    operation: (key: string, scope: string) =>
      client.request<OperationOutcome>(`/operations/${resourcePath(key)}`, { query: { scope } }),
  };
}
export type OrderApi = ReturnType<typeof createOrderApi>;
export interface OrderControllerOptions {
  session: Session;
  storage: SessionStorage;
  namespace: string;
  orderId: string;
  getOrder: () => CustomerOrder | null;
  onOrder?: (order: CustomerOrder) => void;
  keyGenerator?: () => string;
}
export function requireOrderAction(options: OrderControllerOptions, action: string) {
  if (!options.session.get()) throw new ApiError('UNAUTHORIZED', '请先登录');
  const order = options.getOrder();
  if (order?.id !== options.orderId || !order.allowedActions.includes(action))
    throw new ApiError('ACTION_NOT_ALLOWED', '当前状态不允许此操作');
}
/** Session generation also rejects token/session replacement during an in-flight read. */
export function controllerLifetime(session: Session, clear: () => void) {
  let epoch = 0;
  let disposed = false;
  const unsubscribe = session.subscribe(() => {
    epoch++;
    clear();
  });
  return {
    ticket: () => {
      if (disposed) throw new Error('控制器已释放');
      return epoch;
    },
    current: (ticket: number) => !disposed && ticket === epoch,
    dispose: () => {
      disposed = true;
      epoch++;
      unsubscribe();
    },
  };
}
export function createOrderActionController(
  options: OrderControllerOptions & {
    api: OrderApi;
    action: 'CANCEL' | 'CONFIRM_RECEIPT';
    scope?: string;
  },
) {
  const state = { order: null as CustomerOrder | null, error: null as Error | null };
  const life = controllerLifetime(options.session, () => {
    state.order = null;
    state.error = null;
  });
  const operation = createOperationController({
    ...options,
    scope: `${options.scope ?? ORDER_OPERATION_SCOPES[options.action]}:${options.action}:${options.orderId}`,
    execute: async (_body: { orderId: string }, key) => {
      requireOrderAction(options, options.action);
      return options.action === 'CANCEL'
        ? options.api.cancel(options.orderId, key)
        : options.api.confirmReceipt(options.orderId, key);
    },
    query: (key) =>
      options.api.operation(key, options.scope ?? ORDER_OPERATION_SCOPES[options.action]),
  });
  async function run(recover: boolean) {
    const ticket = life.ticket();
    try {
      if (!recover) requireOrderAction(options, options.action);
      await (recover ? operation.recover() : operation.submit({ orderId: options.orderId }));
      if (!life.current(ticket) || operation.state.status !== 'SUCCEEDED') return;
      const order = await options.api.order(options.orderId);
      if (!life.current(ticket)) return;
      state.order = order;
      state.error = null;
      options.onOrder?.(order);
    } catch (error) {
      if (life.current(ticket)) state.error = error as Error;
      throw error;
    }
  }
  return {
    state,
    operation,
    submit: () => run(false),
    recover: () => run(true),
    dispose() {
      life.dispose();
      operation.dispose();
    },
  };
}
