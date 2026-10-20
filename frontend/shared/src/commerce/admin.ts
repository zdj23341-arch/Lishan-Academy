import { ApiError } from '../http';
import { hasPermission } from '../contracts';
import type { Session, SessionStorage } from '../session';
import { validateReturnShipment } from './aftersale';
import { createOperationController } from './operation';
import { controllerLifetime } from './order';
import {
  ADMIN_ACTIONS,
  hasInventoryStock,
  ADMIN_OPERATION_SCOPES,
  type AdminCommerceApi,
  type AdminOrder,
  type AdminInventoryProduct,
  type AdminAftersale,
  type ShipmentInput,
  type InventoryInput,
  type DecisionInput,
  type DecisionPreview,
  type RejectInput,
  type ReceiveReturnInput,
} from './admin-api';

export interface AdminControllerOptions {
  session: Session;
  storage: SessionStorage;
  namespace: string;
  api: AdminCommerceApi;
  keyGenerator?: () => string;
}
function invalid(field: string, message: string): never {
  throw new ApiError('VALIDATION_ERROR', message, null, { [field]: message }, 422);
}
function reason(value: string) {
  if (!value.trim() || value.length > 500) invalid('reason', '原因须为1至500字');
}
function integer(value: number, field: string) {
  if (!Number.isSafeInteger(value) || value < 0) invalid(field, '必须为非负安全整数');
}
function permission(options: AdminControllerOptions, role: string) {
  if (!options.session.get()) throw new ApiError('UNAUTHORIZED', '请先登录', null, {}, 401);
  if (!hasPermission(options.session.get()?.permissions, role))
    throw new ApiError('FORBIDDEN', '没有此操作权限', null, {}, 403);
}
function allowed(
  value: { id: string; allowedActions: string[] } | null,
  id: string,
  action: string,
) {
  if (value?.id !== id || !value.allowedActions.includes(action))
    throw new ApiError('ACTION_NOT_ALLOWED', '当前状态不允许此操作', null, {}, 409);
}
function clone<T>(body: T): T {
  return JSON.parse(JSON.stringify(body)) as T;
}
function same(a: DecisionInput | null, b: DecisionInput) {
  return (
    !!a &&
    a.goodsRefundFen === b.goodsRefundFen &&
    a.freightRefundFen === b.freightRefundFen &&
    a.reason === b.reason
  );
}

/** A stable resource/action slot prevents an evolving version from bypassing an UNKNOWN write. */
function actionController<B, V>(
  options: AdminControllerOptions & {
    id: string;
    scope: string;
    action: string;
    role: string;
    validate: (body: B) => B;
    guard: () => void;
    execute: (body: B, key: string) => Promise<{ id: string }>;
    read: () => Promise<V>;
    publish?: (value: V) => void;
    publication?: { generation: number; next: number };
  },
) {
  const state = {
    input: null as B | null,
    value: null as V | null,
    error: null as Error | null,
    fieldErrors: {} as Record<string, string>,
  };
  const life = controllerLifetime(options.session, () => {
    state.input = null;
    state.value = null;
    state.error = null;
    state.fieldErrors = {};
  });
  let dispatchTicket = life.ticket();
  let queryTicket = life.ticket();
  let operationTask: Promise<void> | null = null;
  const publication = options.publication ?? { generation: 0, next: 0 };
  let operationPublicationTicket = 0;
  function acceptPublication(ticket: number) {
    publication.generation = Math.max(publication.generation, ticket);
  }
  function guard() {
    permission(options, options.role);
    options.guard();
  }
  const operation = createOperationController<B>({
    ...options,
    scope: `${options.scope}:${options.action}:${options.id}`,
    execute: (body, key) => {
      if (!life.current(dispatchTicket))
        throw new ApiError('SESSION_CHANGED', '登录状态已变化', null, {}, 409);
      guard();
      const validated = options.validate(body);
      acceptPublication(operationPublicationTicket);
      return options.execute(validated, key);
    },
    query: async (key) => {
      const ticket = queryTicket;
      if (!life.current(ticket)) throw new ApiError('SESSION_CHANGED', '登录状态已变化');
      permission(options, options.role);
      acceptPublication(operationPublicationTicket);
      const outcome = await options.api.operation(key, options.scope);
      if (!life.current(ticket)) throw new ApiError('SESSION_CHANGED', '登录状态已变化');
      permission(options, options.role);
      return outcome;
    },
  });
  function invoke(action: () => Promise<void>, ticket: number, publicationTicket: number) {
    const pending = operationTask;
    if (!pending) {
      queryTicket = ticket;
      operationPublicationTicket = publicationTicket;
    }
    const task = action();
    if (pending) return task;
    operationTask = task;
    return task.finally(() => {
      if (operationTask === task) operationTask = null;
    });
  }
  async function run(body?: B, retry = false) {
    const ticket = life.ticket();
    const publicationTicket = ++publication.next;
    const current = () => life.current(ticket) && publicationTicket === publication.generation;
    state.error = null;
    state.fieldErrors = {};
    try {
      permission(options, options.role);
      if (body !== undefined) {
        state.input = clone(body);
        guard();
        const validated = options.validate(body);
        if (retry) {
          if (operation.state.status !== 'FAILED') throw new Error('仅明确失败的操作可以更正重试');
          operation.reset();
        }
        if (operation.state.status === 'IDLE') dispatchTicket = ticket;
        await invoke(() => operation.submit(validated), ticket, publicationTicket);
      } else await invoke(operation.recover, ticket, publicationTicket);
      if (!life.current(ticket) || operation.state.status !== 'SUCCEEDED') return;
      acceptPublication(publicationTicket);
      if (!current()) return;
      permission(options, options.role);
      const value = await options.read();
      if (!current()) return;
      permission(options, options.role);
      state.value = value;
      options.publish?.(value);
    } catch (error) {
      if (life.current(ticket) && publicationTicket === publication.next) {
        state.error = error as Error;
        state.fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
      }
      throw error;
    }
  }
  return {
    state,
    operation,
    submit: (body: B) => run(body),
    retry: (body: B) => run(body, true),
    recover: () => run(),
    beginNew() {
      life.ticket();
      permission(options, options.role);
      if (operation.state.status !== 'SUCCEEDED' && operation.state.status !== 'IDLE')
        throw new Error('请先恢复原结果或更正明确失败的操作');
      operation.reset();
      publication.generation = ++publication.next;
      state.input = null;
      state.error = null;
      state.fieldErrors = {};
    },
    dispose() {
      life.dispose();
      operation.dispose();
    },
  };
}
export function createAdminShipmentController(
  options: AdminControllerOptions & {
    orderId: string;
    getOrder: () => AdminOrder | null;
    onOrder?: (value: AdminOrder) => void;
    scope?: string;
    actions?: { SHIP: string; UPDATE_SHIPMENT: string };
  },
) {
  const editing = () =>
    !!options.getOrder()?.shipment?.trackingNo ||
    options.getOrder()?.fulfillmentStatus === 'SHIPPED';
  return actionController<ShipmentInput, AdminOrder>({
    ...options,
    id: options.orderId,
    scope: options.scope ?? ADMIN_OPERATION_SCOPES.SHIP,
    action: 'SHIP',
    role: 'ORDER_OPERATE',
    guard: () =>
      allowed(
        options.getOrder(),
        options.orderId,
        (options.actions ?? ADMIN_ACTIONS)[editing() ? 'UPDATE_SHIPMENT' : 'SHIP'],
      ),
    validate: (body) => {
      const shipment = validateReturnShipment(body);
      integer(body.version, 'version');
      if (editing()) reason(body.reason ?? '');
      return {
        ...shipment,
        version: body.version,
        ...(body.reason !== undefined ? { reason: body.reason } : {}),
      };
    },
    execute: (body, key) => options.api.ship(options.orderId, body, key),
    read: () => options.api.order(options.orderId),
    publish: options.onOrder,
  });
}
export function createAdminInventoryController(
  options: AdminControllerOptions & {
    productId: string;
    getProduct: () => AdminInventoryProduct | null;
    onProduct?: (value: AdminInventoryProduct) => void;
    scope?: string;
  },
) {
  return actionController<InventoryInput, AdminInventoryProduct>({
    ...options,
    id: options.productId,
    scope: options.scope ?? ADMIN_OPERATION_SCOPES.INVENTORY,
    action: 'INVENTORY',
    role: 'ORDER_OPERATE',
    guard: () => {
      if (options.getProduct()?.id !== options.productId || options.getProduct()?.type !== 'BOOK')
        throw new ApiError('ACTION_NOT_ALLOWED', '仅图书可调整库存');
      if (!hasInventoryStock(options.getProduct()))
        throw new ApiError('INVENTORY_UNAVAILABLE', '库存数据暂不可用，请刷新后重试');
    },
    validate: (body) => {
      if (!Number.isSafeInteger(body.delta) || body.delta === 0)
        invalid('delta', '库存调整必须为非零安全整数');
      reason(body.reason);
      if (body.version !== undefined) integer(body.version, 'version');
      return {
        delta: body.delta,
        reason: body.reason,
        ...(body.version !== undefined ? { version: body.version } : {}),
      };
    },
    execute: (body, key) => options.api.inventory(options.productId, body, key),
    read: () => options.api.product(options.productId),
    publish: options.onProduct,
  });
}
function validateDecision(body: DecisionInput) {
  integer(body.goodsRefundFen, 'goodsRefundFen');
  integer(body.freightRefundFen, 'freightRefundFen');
  if (
    !Number.isSafeInteger(body.goodsRefundFen + body.freightRefundFen) ||
    body.goodsRefundFen + body.freightRefundFen <= 0
  )
    invalid('goodsRefundFen', '退款总额必须大于0且为安全整数分');
  reason(body.reason);
  return {
    goodsRefundFen: body.goodsRefundFen,
    freightRefundFen: body.freightRefundFen,
    reason: body.reason,
  };
}
export function createAdminAftersaleController(
  options: AdminControllerOptions & {
    aftersaleId: string;
    getAftersale: () => AdminAftersale | null;
    onAftersale?: (value: AdminAftersale) => void;
    scopes?: Partial<Record<'APPROVE' | 'REJECT' | 'RECEIVE_RETURN', string>>;
    actions?: Partial<Record<'APPROVE' | 'REJECT' | 'RECEIVE_RETURN', string>>;
  },
) {
  const state = {
    aftersale: null as AdminAftersale | null,
    decisionInput: null as DecisionInput | null,
    preview: null as DecisionPreview | null,
    reviewedInput: null as DecisionInput | null,
    previewLoading: false,
    error: null as Error | null,
    fieldErrors: {} as Record<string, string>,
  };
  let previewSequence = 0;
  const life = controllerLifetime(options.session, () => {
    previewSequence++;
    Object.assign(state, {
      aftersale: null,
      decisionInput: null,
      preview: null,
      reviewedInput: null,
      previewLoading: false,
      error: null,
      fieldErrors: {},
    });
  });
  const requireAction = (action: 'APPROVE' | 'REJECT' | 'RECEIVE_RETURN') =>
    allowed(
      options.getAftersale(),
      options.aftersaleId,
      options.actions?.[action] ?? ADMIN_ACTIONS[action],
    );
  async function read() {
    const page = await options.api.aftersales({ id: options.aftersaleId, page: 1, pageSize: 20 });
    const value = page?.items?.find((item) => item.id === options.aftersaleId);
    if (value) return value;
    if (typeof options.api.aftersale === 'function') {
      try {
        const detail = await options.api.aftersale(options.aftersaleId);
        if (detail && detail.id === options.aftersaleId) return detail;
      } catch (err) {
        if (!(err instanceof ApiError) || err.code !== 'NOT_FOUND') throw err;
      }
    }
    throw new ApiError('NOT_FOUND', '权威售后列表未返回当前售后单');
  }
  function publish(value: AdminAftersale) {
    state.aftersale = value;
    options.onAftersale?.(value);
  }
  function reviewed(body: DecisionInput) {
    const valid = validateDecision(body);
    if (!state.preview || !same(state.reviewedInput, valid))
      throw new ApiError('PREVIEW_REQUIRED', '输入已变化，请重新预览并确认');
    if (valid.goodsRefundFen > state.preview.remainingGoodsFen)
      invalid('goodsRefundFen', '超出剩余商品退款预算');
    if (valid.freightRefundFen > state.preview.remainingFreightFen)
      invalid('freightRefundFen', '超出剩余运费退款预算');
    return valid;
  }
  const common = {
    ...options,
    id: options.aftersaleId,
    read,
    publish,
    publication: { generation: 0, next: 0 },
  };
  const approval = actionController<DecisionInput, AdminAftersale>({
    ...common,
    scope: options.scopes?.APPROVE ?? ADMIN_OPERATION_SCOPES.APPROVE,
    action: 'APPROVE',
    role: 'FINANCE_OPERATE',
    guard: () => requireAction('APPROVE'),
    validate: reviewed,
    execute: (body, key) => options.api.approve(options.aftersaleId, body, key),
  });
  const rejection = actionController<RejectInput, AdminAftersale>({
    ...common,
    scope: options.scopes?.REJECT ?? ADMIN_OPERATION_SCOPES.REJECT,
    action: 'REJECT',
    role: 'FINANCE_OPERATE',
    guard: () => requireAction('REJECT'),
    validate: (body) => {
      reason(body.reason);
      return { reason: body.reason };
    },
    execute: (body, key) => options.api.reject(options.aftersaleId, body, key),
  });
  const receiving = actionController<ReceiveReturnInput, AdminAftersale>({
    ...common,
    scope: options.scopes?.RECEIVE_RETURN ?? ADMIN_OPERATION_SCOPES.RECEIVE_RETURN,
    action: 'RECEIVE_RETURN',
    role: 'ORDER_OPERATE',
    guard: () => requireAction('RECEIVE_RETURN'),
    validate: (body) => {
      integer(body.returnedQuantity, 'returnedQuantity');
      const max = options.getAftersale()?.permittedReturnQuantity;
      if (
        max !== undefined &&
        (!Number.isSafeInteger(max) || max < 0 || body.returnedQuantity > max)
      )
        invalid('returnedQuantity', '超出允许退货数量');
      return { returnedQuantity: body.returnedQuantity };
    },
    execute: (body, key) => options.api.receiveReturn(options.aftersaleId, body, key),
  });
  function setDecisionInput(body: DecisionInput) {
    life.ticket();
    if (!same(state.decisionInput, body)) {
      previewSequence++;
      state.preview = null;
      state.reviewedInput = null;
      state.previewLoading = false;
    }
    state.decisionInput = clone(body);
  }
  async function run(action: () => Promise<void>) {
    const ticket = life.ticket();
    state.error = null;
    state.fieldErrors = {};
    try {
      await action();
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
    approval,
    rejection,
    receiving,
    approveOperation: approval.operation,
    rejectOperation: rejection.operation,
    receiveOperation: receiving.operation,
    setDecisionInput,
    preview: (body: DecisionInput) =>
      run(async () => {
        const ticket = life.ticket();
        setDecisionInput(body);
        const sequence = ++previewSequence;
        state.preview = null;
        state.reviewedInput = null;
        permission(options, 'FINANCE_OPERATE');
        requireAction('APPROVE');
        const input = validateDecision(body);
        state.previewLoading = true;
        try {
          const result = await options.api.previewDecision(options.aftersaleId, input);
          if (!life.current(ticket) || sequence !== previewSequence) return;
          permission(options, 'FINANCE_OPERATE');
          integer(result.remainingGoodsFen, 'remainingGoodsFen');
          integer(result.remainingFreightFen, 'remainingFreightFen');
          if (
            typeof result.entitlementImpact !== 'string' ||
            typeof result.commissionImpact !== 'string'
          )
            throw new ApiError('INVALID_RESPONSE', '预览影响信息无效');
          state.preview = clone(result);
          state.reviewedInput = clone(input);
        } catch (error) {
          if (life.current(ticket) && sequence === previewSequence) throw error;
        } finally {
          if (life.current(ticket) && sequence === previewSequence) state.previewLoading = false;
        }
      }),
    approve: (body: DecisionInput) =>
      run(async () => {
        setDecisionInput(body);
        await approval.submit(body);
      }),
    reject: (body: RejectInput) => run(() => rejection.submit(body)),
    receiveReturn: (body: ReceiveReturnInput) => run(() => receiving.submit(body)),
    retryApprove: (body: DecisionInput) =>
      run(async () => {
        setDecisionInput(body);
        await approval.retry(body);
      }),
    retryReject: (body: RejectInput) => run(() => rejection.retry(body)),
    retryReceiveReturn: (body: ReceiveReturnInput) => run(() => receiving.retry(body)),
    recoverApprove: () => run(approval.recover),
    recoverReject: () => run(rejection.recover),
    recoverReceiveReturn: () => run(receiving.recover),
    dispose() {
      life.dispose();
      approval.dispose();
      rejection.dispose();
      receiving.dispose();
    },
  };
}
