import { ApiError, type ApiClient } from '../http';
import { createOperationController } from './operation';
import type { OperationOutcome } from './types';
import {
  controllerLifetime,
  ORDER_OPERATION_SCOPES,
  requireOrderAction,
  resourcePath,
  type CustomerOrder,
  type OrderApi,
  type OrderControllerOptions,
} from './order';

export type PaymentMode = 'development' | 'test' | 'production';
export interface PrepayView {
  provider: string;
  paymentId: string;
  state: string;
  params?: Record<string, unknown> | null;
  simulated?: boolean;
}
export type PaymentCallback = 'success' | 'cancel' | 'failure';
export type PaymentAdapter = (prepay: PrepayView) => Promise<PaymentCallback>;
export function createPaymentApi(client: ApiClient, options: { mode: PaymentMode }) {
  return {
    prepay: (orderId: string, key: string) =>
      client.request<PrepayView>('/payments/wechat/prepay', {
        method: 'POST',
        body: { orderId },
        idempotencyKey: key,
      }),
    simulate(orderId: string, scenario: string, key: string) {
      if (options.mode === 'production') throw new ApiError('DEV_DISABLED', '生产环境禁止模拟支付');
      return client.request<{ id?: string; simulated?: boolean }>(
        `/dev/orders/${resourcePath(orderId)}/pay`,
        { method: 'POST', body: { scenario }, idempotencyKey: key },
      );
    },
  };
}
export type PaymentApi = ReturnType<typeof createPaymentApi>;
export interface PaymentState {
  status:
    | 'IDLE'
    | 'PREPAY'
    | 'UNKNOWN'
    | 'PARAMS_UNAVAILABLE'
    | 'PENDING'
    | 'PAID'
    | 'CANCELLED'
    | 'FAILED';
  order: CustomerOrder | null;
  error: Error | null;
  callback: PaymentCallback | null;
  simulated: boolean;
  automaticQueries: number;
}
export function createPaymentController(
  options: OrderControllerOptions & {
    api: PaymentApi;
    orderApi: OrderApi;
    adapter: PaymentAdapter;
    mode: PaymentMode;
    scope?: string;
    simulatorScope?: string;
    delay?: () => Promise<void>;
  },
) {
  const state: PaymentState = {
    status: 'IDLE',
    order: null,
    error: null,
    callback: null,
    simulated: false,
    automaticQueries: 0,
  };
  let prepay: PrepayView | null = null;
  let queriedParamsKey: string | null = null;
  let confirmedPaymentId: string | null = null;
  let dispatchedKey: string | null = null;
  let pending: Promise<void> | null = null;
  const life = controllerLifetime(options.session, () => {
    Object.assign(state, {
      status: 'IDLE',
      order: null,
      error: null,
      callback: null,
      simulated: false,
      automaticQueries: 0,
    });
    prepay = null;
    queriedParamsKey = null;
    confirmedPaymentId = null;
    dispatchedKey = null;
    pending = null;
  });
  const scope = options.scope ?? ORDER_OPERATION_SCOPES.PREPAY;
  function rememberOutcome(key: string, result: OperationOutcome) {
    queriedParamsKey = key;
    confirmedPaymentId = result.state === 'SUCCEEDED' ? result.resourceId : null;
    if (result.state === 'SUCCEEDED' && isPrepay(result.response)) {
      if (result.response.paymentId !== result.resourceId)
        throw new ApiError('INVALID_RESPONSE', '原操作中的支付标识不一致');
      prepay = result.response;
    }
  }
  const operation = createOperationController({
    ...options,
    scope: `${scope}:PREPAY:${options.orderId}`,
    execute: async (body: { orderId: string }, key) => {
      requireOrderAction(options, 'PAY');
      const ticket = life.ticket();
      const response = await options.api.prepay(body.orderId, key);
      if (life.current(ticket)) prepay = response;
      return { id: response.paymentId };
    },
    query: async (key) => {
      const ticket = life.ticket();
      const result = await options.orderApi.operation(key, scope);
      if (life.current(ticket)) rememberOutcome(key, result);
      return result;
    },
  });
  const simulatorScope = options.simulatorScope ?? ORDER_OPERATION_SCOPES.DEV_PAY;
  const simulator = createOperationController({
    ...options,
    scope: `${simulatorScope}:DEV_PAY:${options.orderId}`,
    execute: async (body: { orderId: string; scenario: string }, key) => {
      requireOrderAction(options, 'PAY');
      await options.api.simulate(body.orderId, body.scenario, key);
      return { id: body.orderId };
    },
    query: (key) => options.orderApi.operation(key, simulatorScope),
  });
  async function queryOrder(ticket: number) {
    if (!life.current(ticket)) return;
    const order = await options.orderApi.order(options.orderId);
    if (!life.current(ticket)) return;
    state.order = order;
    state.status = ['PAID', 'COMPLETED'].includes(order.orderStatus)
      ? 'PAID'
      : order.orderStatus === 'CANCELLED'
        ? 'CANCELLED'
        : 'PENDING';
    options.onOrder?.(order);
  }
  async function poll(ticket: number) {
    state.automaticQueries = 0;
    for (let i = 0; i < 5 && life.current(ticket); i++) {
      if (i)
        await (
          options.delay ?? (() => new Promise<void>((resolve) => setTimeout(resolve, 1000)))
        )();
      if (!life.current(ticket)) return;
      state.automaticQueries++;
      await queryOrder(ticket);
      if (state.status === 'PAID' || state.status === 'CANCELLED') return;
    }
  }
  async function finishPrepay(ticket: number, explicitPay = false) {
    if (!life.current(ticket)) return;
    if (operation.state.status !== 'SUCCEEDED') {
      state.status = operation.state.status === 'FAILED' ? 'FAILED' : 'UNKNOWN';
      return;
    }
    if (
      dispatchedKey === operation.state.key &&
      !(explicitPay && (state.callback === 'cancel' || state.callback === 'failure'))
    ) {
      await queryOrder(ticket);
      return;
    }
    if (!prepay?.params && operation.state.key && queriedParamsKey !== operation.state.key) {
      const key = operation.state.key;
      const result = await options.orderApi.operation(key, scope);
      if (!life.current(ticket)) return;
      rememberOutcome(key, result);
    }
    if (!prepay?.params) {
      // PREPAY's authoritative operation response contains only { id }.
      // Replaying the original body/key retrieves that intent's current view;
      // it must never allocate a fresh operation or infer a payment outcome.
      await queryOrder(ticket);
      if (!life.current(ticket) || state.status === 'PAID' || state.status === 'CANCELLED') return;
      state.status = 'PARAMS_UNAVAILABLE';
      if (!explicitPay) return;
      const key = operation.state.key;
      if (
        !key ||
        queriedParamsKey !== key ||
        !confirmedPaymentId ||
        confirmedPaymentId !== operation.state.resourceId
      )
        throw new ApiError('INVALID_RESPONSE', '原预支付尚未获得权威确认，请查询原付款操作');
      requireOrderAction(options, 'PAY');
      const restored = await options.api.prepay(options.orderId, key);
      if (!life.current(ticket)) return;
      if (restored.paymentId !== confirmedPaymentId)
        throw new ApiError('INVALID_RESPONSE', '恢复的支付标识与原操作不一致');
      prepay = restored;
      if (!prepay.params) return;
    }
    if (
      options.mode === 'production' &&
      (prepay.provider !== 'WECHAT_PAY' || prepay.simulated === true)
    )
      throw new ApiError('PROVIDER_DISABLED', '生产环境禁止模拟或未知支付渠道');
    if (!explicitPay) {
      await queryOrder(ticket);
      return;
    }
    requireOrderAction(options, 'PAY');
    dispatchedKey = operation.state.key;
    state.simulated = prepay.provider === 'DEV' || prepay.simulated === true;
    try {
      const callback = await options.adapter(prepay);
      if (!life.current(ticket)) return;
      state.callback = callback;
    } catch (error) {
      if (!life.current(ticket)) return;
      state.callback = 'failure';
      state.error = error as Error;
    }
    if (life.current(ticket)) await poll(ticket);
  }
  function run(action: (ticket: number) => Promise<void>) {
    if (pending) return pending;
    const ticket = life.ticket();
    const task = action(ticket)
      .catch((error) => {
        if (life.current(ticket)) {
          state.error = error as Error;
          state.status =
            operation.state.status === 'UNKNOWN' || simulator.state.status === 'UNKNOWN'
              ? 'UNKNOWN'
              : 'FAILED';
        }
        throw error;
      })
      .finally(() => {
        if (life.current(ticket)) pending = null;
      });
    pending = task;
    return task;
  }
  return {
    state,
    operation,
    simulator,
    pay: () =>
      run(async (ticket) => {
        requireOrderAction(options, 'PAY');
        if (operation.state.status === 'FAILED') {
          operation.reset();
          prepay = null;
          queriedParamsKey = null;
          confirmedPaymentId = null;
          dispatchedKey = null;
          state.callback = null;
          state.simulated = false;
        }
        state.error = null;
        state.status = 'PREPAY';
        await operation.submit({ orderId: options.orderId });
        await finishPrepay(ticket, true);
      }),
    recover: () =>
      run(async (ticket) => {
        await operation.recover();
        await finishPrepay(ticket);
      }),
    refresh: () =>
      run(async (ticket) => {
        await queryOrder(ticket);
      }),
    simulate: (scenario: string) =>
      run(async (ticket) => {
        if (options.mode === 'production')
          throw new ApiError('DEV_DISABLED', '生产环境禁止模拟支付');
        requireOrderAction(options, 'PAY');
        if (!scenario.trim()) throw new Error('请选择模拟场景');
        if (
          simulator.state.requestSnapshot?.scenario !== scenario &&
          ['SUCCEEDED', 'FAILED'].includes(simulator.state.status)
        )
          simulator.reset();
        await simulator.submit({ orderId: options.orderId, scenario });
        if (!life.current(ticket)) return;
        state.simulated = true;
        if (simulator.state.status === 'SUCCEEDED') await poll(ticket);
        else state.status = simulator.state.status === 'FAILED' ? 'FAILED' : 'UNKNOWN';
      }),
    dispose() {
      life.dispose();
      operation.dispose();
      simulator.dispose();
    },
  };
}
function isPrepay(value: unknown): value is PrepayView {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<PrepayView>;
  return (
    typeof candidate.provider === 'string' &&
    typeof candidate.paymentId === 'string' &&
    typeof candidate.state === 'string' &&
    !!candidate.params &&
    typeof candidate.params === 'object' &&
    !Array.isArray(candidate.params)
  );
}
