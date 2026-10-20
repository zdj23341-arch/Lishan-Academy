import { ApiError, type ApiClient } from '../http';
import { createOperationController } from './operation';
import {
  controllerLifetime,
  ORDER_OPERATION_SCOPES,
  requireOrderAction,
  resourcePath,
  type CustomerOrder,
  type OrderApi,
  type OrderControllerOptions,
} from './order';

export interface RefundRequest {
  type: 'REFUND_ONLY' | 'RETURN_REFUND';
  reason: string;
  evidenceAssetIds: string[];
  requestedGoodsFen: number;
  requestedFreightFen: number;
}
export interface ReturnShipmentRequest {
  carrierCode: string;
  trackingNo: string;
}
export interface AftersaleView extends RefundRequest {
  id: string;
  orderId: string;
  status: string;
  approvedGoodsFen: number | null;
  approvedFreightFen: number | null;
  returnedQuantity: number | null;
  receivedQuantity: number | null;
  returnAddress: string | Record<string, unknown> | null;
  returnCarrierCode: string | null;
  returnTrackingNo: string | null;
  allowedActions: string[];
  failureReason?: string | null;
  rejectionReason?: string | null;
}
export function aftersaleStatusLabel(status: string): string {
  return (
    (
      {
        __proto__: null,
        NONE: '无售后',
        REQUESTED: '申请中',
        RETURN_REQUIRED: '待寄回',
        RETURNING: '退货中',
        RETURN_RECEIVED: '待退款审核',
        PROCESSING: '退款处理中',
        PARTIAL: '部分退款',
        FULL: '全部退款',
        SUCCEEDED: '退款成功',
        REJECTED: '已拒绝',
        FAILED: '退款失败',
        CLOSED: '已关闭',
      } as Record<string, string | null>
    )[status] ?? `未知状态（${status}）`
  );
}
export function createAftersaleApi(client: ApiClient) {
  return {
    refund: (orderId: string, body: RefundRequest, key: string) =>
      client.request<AftersaleView>(`/orders/${resourcePath(orderId)}/refund`, {
        method: 'POST',
        body,
        idempotencyKey: key,
      }),
    aftersale: (id: string) => client.request<AftersaleView>(`/aftersales/${resourcePath(id)}`),
    returnShipment: (id: string, body: ReturnShipmentRequest, key: string) =>
      client.request<AftersaleView>(`/aftersales/${resourcePath(id)}/return-shipment`, {
        method: 'POST',
        body,
        idempotencyKey: key,
      }),
  };
}
export type AftersaleApi = ReturnType<typeof createAftersaleApi>;
function invalid(fields: Record<string, string>) {
  if (Object.keys(fields).length)
    throw new ApiError('VALIDATION_ERROR', '请检查输入内容', null, fields, 422);
}
export function validateRefund(body: RefundRequest, order: CustomerOrder | null): RefundRequest {
  const fields: Record<string, string> = {};
  const reason = body.reason.trim();
  if (!['REFUND_ONLY', 'RETURN_REFUND'].includes(body.type)) fields.type = '请选择售后类型';
  if (!reason || reason.length > 500) fields.reason = '原因须为1至500字';
  if (
    !Array.isArray(body.evidenceAssetIds) ||
    body.evidenceAssetIds.length > 10 ||
    new Set(body.evidenceAssetIds).size !== body.evidenceAssetIds.length ||
    body.evidenceAssetIds.some((id) => typeof id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(id))
  )
    fields.evidenceAssetIds = '最多10个不重复的已上传凭证ID';
  for (const field of ['requestedGoodsFen', 'requestedFreightFen'] as const)
    if (!Number.isSafeInteger(body[field]) || body[field] < 0)
      fields[field] = '金额必须为非负整数分';
  if (
    !Number.isSafeInteger(body.requestedGoodsFen + body.requestedFreightFen) ||
    body.requestedGoodsFen + body.requestedFreightFen <= 0
  )
    fields.requestedGoodsFen = '申请总额必须大于0且为有效整数分';
  if (order) {
    const goodsBudget = order.goodsAmountFen - (order.refundedGoodsFen ?? 0);
    const freightBudget = order.freightAmountFen - (order.refundedFreightFen ?? 0);
    // Original server amounts always provide an upper bound; only explicit component refunds reduce it.
    if (body.requestedGoodsFen > goodsBudget) fields.requestedGoodsFen = '超出可申请商品金额';
    if (body.requestedFreightFen > freightBudget) fields.requestedFreightFen = '超出可申请运费';
    if (
      body.requestedGoodsFen + body.requestedFreightFen >
      (order.paidAmountFen ?? order.payableAmountFen) -
        (order.refundedAmountFen ?? (order.refundedGoodsFen ?? 0) + (order.refundedFreightFen ?? 0))
    )
      fields.requestedGoodsFen = '超出订单剩余可申请金额';
  }
  invalid(fields);
  return { ...body, reason, evidenceAssetIds: [...body.evidenceAssetIds] };
}
export function validateReturnShipment(body: ReturnShipmentRequest): ReturnShipmentRequest {
  const carrierCode = body.carrierCode.trim();
  const trackingNo = body.trackingNo.trim();
  const fields: Record<string, string> = {};
  if (!carrierCode || carrierCode.length > 40) fields.carrierCode = '物流公司须为1至40字';
  if (!/^[A-Za-z0-9-]{1,100}$/.test(trackingNo))
    fields.trackingNo = '请填写1至100位字母、数字或短横线运单号';
  invalid(fields);
  return { carrierCode, trackingNo };
}
export function createAftersaleController(
  options: OrderControllerOptions & {
    api: AftersaleApi;
    orderApi: OrderApi;
    getAftersale?: () => AftersaleView | null;
    onAftersale?: (value: AftersaleView) => void;
    refundScope?: string;
    returnScope?: string;
  },
) {
  const state = {
    aftersale: null as AftersaleView | null,
    order: null as CustomerOrder | null,
    error: null as Error | null,
    fieldErrors: {} as Record<string, string>,
    refundInput: null as RefundRequest | null,
    returnInput: null as ReturnShipmentRequest | null,
  };
  const life = controllerLifetime(options.session, () => {
    Object.assign(state, {
      aftersale: null,
      order: null,
      error: null,
      fieldErrors: {},
      refundInput: null,
      returnInput: null,
    });
  });
  const refundScope = options.refundScope ?? ORDER_OPERATION_SCOPES.REFUND;
  const refundOperation = createOperationController({
    ...options,
    scope: `${refundScope}:REFUND:${options.orderId}`,
    execute: (body: RefundRequest, key) => {
      requireOrderAction(options, 'APPLY_AFTERSALE');
      return options.api.refund(options.orderId, validateRefund(body, options.getOrder()), key);
    },
    query: (key) => options.orderApi.operation(key, refundScope),
  });
  const returns = new Map<
    string,
    ReturnType<typeof createOperationController<ReturnShipmentRequest>>
  >();
  function requireReturn(id: string) {
    if (!options.session.get()) throw new ApiError('UNAUTHORIZED', '请先登录');
    const current = options.getAftersale ? options.getAftersale() : state.aftersale;
    if (
      current?.id !== id ||
      current.orderId !== options.orderId ||
      current.status !== 'RETURN_REQUIRED' ||
      !current.allowedActions.includes('SUBMIT_RETURN_SHIPMENT')
    )
      throw new ApiError('ACTION_NOT_ALLOWED', '当前状态不允许提交退货物流');
  }
  function returnOperation(id: string) {
    let operation = returns.get(id);
    if (!operation) {
      const scope = options.returnScope ?? ORDER_OPERATION_SCOPES.RETURN_SHIPMENT;
      operation = createOperationController({
        ...options,
        scope: `${scope}:RETURN_SHIPMENT:${id}`,
        execute: (body: ReturnShipmentRequest, key) => {
          requireReturn(id);
          return options.api.returnShipment(id, validateReturnShipment(body), key);
        },
        query: (key) => options.orderApi.operation(key, scope),
      });
      returns.set(id, operation);
    }
    return operation;
  }
  async function read(id: string, ticket: number) {
    const [aftersale, order] = await Promise.all([
      options.api.aftersale(id),
      options.orderApi.order(options.orderId),
    ]);
    if (!life.current(ticket)) return;
    state.aftersale = aftersale;
    state.order = order;
    options.onAftersale?.(aftersale);
    options.onOrder?.(order);
  }
  async function run(action: (ticket: number) => Promise<void>) {
    const ticket = life.ticket();
    state.error = null;
    state.fieldErrors = {};
    try {
      await action(ticket);
    } catch (error) {
      if (life.current(ticket)) {
        state.error = error as Error;
        state.fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
      }
      throw error;
    }
  }
  return {
    state,
    refundOperation,
    refund: (body: RefundRequest) =>
      run(async (ticket) => {
        state.refundInput = JSON.parse(JSON.stringify(body)) as RefundRequest;
        requireOrderAction(options, 'APPLY_AFTERSALE');
        const normalized = validateRefund(body, options.getOrder());
        if (refundOperation.state.status === 'FAILED') refundOperation.reset();
        await refundOperation.submit(normalized);
        if (
          life.current(ticket) &&
          refundOperation.state.status === 'SUCCEEDED' &&
          refundOperation.state.resourceId
        )
          await read(refundOperation.state.resourceId, ticket);
      }),
    recoverRefund: () =>
      run(async (ticket) => {
        await refundOperation.recover();
        if (
          life.current(ticket) &&
          refundOperation.state.status === 'SUCCEEDED' &&
          refundOperation.state.resourceId
        )
          await read(refundOperation.state.resourceId, ticket);
      }),
    returnShipment: (id: string, body: ReturnShipmentRequest) =>
      run(async (ticket) => {
        state.returnInput = { ...body };
        requireReturn(id);
        const operation = returnOperation(id);
        const normalized = validateReturnShipment(body);
        if (operation.state.status === 'FAILED') operation.reset();
        await operation.submit(normalized);
        if (life.current(ticket) && operation.state.status === 'SUCCEEDED') await read(id, ticket);
      }),
    recoverReturn: (id: string) =>
      run(async (ticket) => {
        const operation = returnOperation(id);
        await operation.recover();
        if (life.current(ticket) && operation.state.status === 'SUCCEEDED') await read(id, ticket);
      }),
    getReturnStatus(id: string) {
      life.ticket();
      return returnOperation(id).state.status;
    },
    dispose() {
      life.dispose();
      refundOperation.dispose();
      returns.forEach((operation) => operation.dispose());
    },
  };
}
