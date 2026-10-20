import type { OrderView, Page } from '../contracts';
import type { ApiClient } from '../http';
import type {
  AddressView,
  AddressWrite,
  CreateOrderRequest,
  OperationOutcome,
  PreviewRequest,
  QuoteView,
} from './types';

function idPath(id: string) {
  if (!id.trim()) throw new Error('ID不能为空');
  return encodeURIComponent(id);
}
export function createCommerceApi(client: ApiClient) {
  return {
    addresses(page = 1, pageSize = 20) {
      if (
        !Number.isInteger(page) ||
        page < 1 ||
        !Number.isInteger(pageSize) ||
        pageSize < 1 ||
        pageSize > 100
      )
        throw new Error('分页参数无效');
      return client.request<Page<AddressView>>('/addresses', { query: { page, pageSize } });
    },
    createAddress: (body: AddressWrite) =>
      client.request<AddressView>('/addresses', { method: 'POST', body }),
    updateAddress: (id: string, body: AddressWrite) =>
      client.request<AddressView>(`/addresses/${idPath(id)}`, { method: 'PATCH', body }),
    deleteAddress: (id: string) =>
      client.request<null>(`/addresses/${idPath(id)}`, { method: 'DELETE' }),
    preview: (body: PreviewRequest) =>
      client.request<QuoteView>('/orders/preview', { method: 'POST', body }),
    createOrder: (body: CreateOrderRequest, key: string) =>
      client.request<OrderView>('/orders', { method: 'POST', body, idempotencyKey: key }),
    operation: (key: string, scope = 'CREATE_ORDER') =>
      client.request<OperationOutcome>(`/operations/${idPath(key)}`, { query: { scope } }),
  };
}
export type CommerceApi = ReturnType<typeof createCommerceApi>;
