import type { Transport, TransportRequest } from '../http';

export interface CommerceScenarioEnvironment {
  mode: string;
  provider: string;
}
export interface CommerceScenarioResult {
  shipmentOrderId: string;
  returnOrderId: string;
  returnCaseId: string;
  refundOrderId: string;
  refundCaseId: string;
}
interface Progress {
  steps: Map<string, { request: TransportRequest; result?: unknown; complete: boolean }>;
  pending?: Promise<CommerceScenarioResult>;
  result?: CommerceScenarioResult;
}
const progress = new WeakMap<Transport, Progress>();
function guard(environment: CommerceScenarioEnvironment) {
  if (environment.provider !== 'mock' || !['development', 'test'].includes(environment.mode))
    throw new Error('仅限本地 DEV mock 数据');
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
}

/** The caller must supply the local devTransport, never the ordinary API client. */
export function prepareCommerceScenarios(
  transport: Transport,
  environment: CommerceScenarioEnvironment,
): Promise<CommerceScenarioResult> {
  try {
    guard(environment);
  } catch (error) {
    return Promise.reject(error);
  }
  let state = progress.get(transport);
  if (!state) {
    state = { steps: new Map() };
    progress.set(transport, state);
  }
  if (state.pending) return state.pending;
  if (state.result) return Promise.resolve(state.result);
  const current = state;
  async function post<T>(step: string, path: string, body: unknown): Promise<T> {
    guard(environment);
    let operation = current.steps.get(step);
    if (!operation) {
      operation = {
        complete: false,
        request: freeze({
          url: `/api/v1${path}`,
          method: 'POST' as const,
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': globalThis.crypto.randomUUID(),
          },
          body,
        }),
      };
      current.steps.set(step, operation);
    }
    if (!operation.complete) {
      guard(environment);
      const response = await transport(operation.request);
      const envelope = response.body as { code?: string; message?: string; data?: unknown } | null;
      if (response.status < 200 || response.status >= 300 || envelope?.code !== 'OK')
        throw new Error(envelope?.message || '本地 DEV 数据准备失败');
      operation.result = envelope.data;
      operation.complete = true;
    }
    return operation.result as T;
  }
  async function run(): Promise<CommerceScenarioResult> {
    const address = await post<{ id: string }>('address', '/addresses', {
      version: 0,
      name: 'DEV虚构收货人',
      phone: '13800000000',
      provinceCode: '440000',
      cityCode: '440100',
      districtCode: '440106',
      detail: 'DEV虚构测试地址（请勿配送）',
      isDefault: false,
    });
    async function paid(slot: string) {
      const quote = await post<{ quoteId: string }>(`${slot}:preview`, '/orders/preview', {
        productId: 'DEV-book',
        quantity: 1,
        addressId: address.id,
      });
      const order = await post<{ id: string }>(`${slot}:create`, '/orders', {
        quoteId: quote.quoteId,
      });
      await post(`${slot}:pay`, `/dev/orders/${order.id}/pay`, { scenario: 'SUCCESS' });
      return order.id;
    }
    const shipmentOrderId = await paid('shipment');
    const returnOrderId = await paid('return');
    await post('return:ship', `/admin/orders/${returnOrderId}/ship`, {
      carrierCode: 'SF',
      trackingNo: 'DEV-FICTIONAL-RETURN',
      version: 2,
    });
    const returnCase = await post<{ id: string }>(
      'return:case',
      `/orders/${returnOrderId}/refund`,
      {
        type: 'RETURN_REFUND',
        reason: 'DEV虚构退货申请',
        evidenceAssetIds: [],
        requestedGoodsFen: 3990,
        requestedFreightFen: 0,
      },
    );
    const refundOrderId = await paid('refund');
    const refundCase = await post<{ id: string }>(
      'refund:case',
      `/orders/${refundOrderId}/refund`,
      {
        type: 'REFUND_ONLY',
        reason: 'DEV虚构退款申请',
        evidenceAssetIds: [],
        requestedGoodsFen: 3990,
        requestedFreightFen: 0,
      },
    );
    current.result = freeze({
      shipmentOrderId,
      returnOrderId,
      returnCaseId: returnCase.id,
      refundOrderId,
      refundCaseId: refundCase.id,
    });
    return current.result;
  }
  current.pending = run().finally(() => {
    current.pending = undefined;
  });
  return current.pending;
}
