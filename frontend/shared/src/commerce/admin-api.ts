import type { Page } from '../contracts';
import type { AdminProduct } from '../content/admin-types';
import { ApiError, type ApiClient } from '../http';
import type { AftersaleView } from './aftersale';
import { resourcePath, type CustomerOrder, type ShipmentView } from './order';
import type { OperationOutcome } from './types';

/** List query names and added view fields are frontend fixture DTOs pending live agreement. */
export interface AdminOrder extends CustomerOrder {
  shipment?: ShipmentView | null;
}
export interface AdminAftersale extends AftersaleView {
  permittedReturnQuantity?: number;
}
export interface AdminInventoryProduct {
  id: string;
  type: AdminProduct['type'];
  title?: string;
  book?: {
    availableStock?: number;
    lockedStock?: number;
    physicalStock?: number;
    inventoryVersion?: number;
  };
}
export function hasInventoryVersion(product: AdminInventoryProduct | null): boolean {
  const version = product?.book?.inventoryVersion;
  return typeof version === 'number' && Number.isSafeInteger(version) && version >= 0;
}
export function hasInventoryStock(product: AdminInventoryProduct | null): boolean {
  return (
    product?.type === 'BOOK' &&
    [product.book?.availableStock, product.book?.lockedStock, product.book?.physicalStock].every(
      (value) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0,
    )
  );
}
export interface AdminOrderQuery {
  orderNo?: string;
  from?: string;
  to?: string;
  createdFrom?: string;
  createdTo?: string;
  orderStatus?: string;
  fulfillmentStatus?: string;
  refundStatus?: string;
  page?: number;
  pageSize?: number;
}
/** id is a fixture-only exact filter used to reread a case without inventing a detail endpoint. */
export interface AdminAftersaleQuery {
  id?: string;
  orderNo?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}
export interface ShipmentInput {
  carrierCode: string;
  trackingNo: string;
  version: number;
  reason?: string;
}
export interface InventoryInput {
  delta: number;
  reason: string;
  version?: number;
}
export interface DecisionInput {
  goodsRefundFen: number;
  freightRefundFen: number;
  reason: string;
}
export interface ReceiveReturnInput {
  returnedQuantity: number;
}
export interface RejectInput {
  reason: string;
}
/** Authoritative component budgets and impact descriptions; no invented preview token. */
export interface DecisionPreview {
  remainingGoodsFen: number;
  remainingFreightFen: number;
  entitlementImpact: string;
  commissionImpact: string;
}
/** Wire scope names and action literals require live backend confirmation. */
export const ADMIN_OPERATION_SCOPES = {
  SHIP: 'SHIP_ORDER',
  INVENTORY: 'INVENTORY_ADJUST',
  APPROVE: 'APPROVE_REFUND',
  REJECT: 'REJECT_REFUND',
  RECEIVE_RETURN: 'RECEIVE_RETURN',
  INVENTORY_ADJUSTMENT: 'INVENTORY_ADJUST',
  APPROVE_AFTERSALE: 'APPROVE_REFUND',
  REJECT_AFTERSALE: 'REJECT_REFUND',
} as const;
export const ADMIN_ACTIONS = {
  SHIP: 'SHIP',
  UPDATE_SHIPMENT: 'UPDATE_SHIPMENT',
  APPROVE: 'APPROVE',
  REJECT: 'REJECT',
  RECEIVE_RETURN: 'RECEIVE_RETURN',
} as const;
export function createAdminCommerceApi(client: ApiClient) {
  const post = <T>(path: string, body: unknown, key?: string) =>
    client.request<T>(path, { method: 'POST', body, idempotencyKey: key });
  return {
    orders: (query: AdminOrderQuery = {}) => {
      const { createdFrom, createdTo, ...current } = query;
      return client.request<Page<AdminOrder>>('/admin/orders', {
        query: Object.fromEntries(
          Object.entries({
            ...current,
            from: query.from ?? createdFrom,
            to: query.to ?? createdTo,
          }).filter(([, value]) => typeof value !== 'string' || value.trim() !== ''),
        ),
      });
    },
    order: (id: string) => client.request<AdminOrder>(`/admin/orders/${resourcePath(id)}`),
    product: (id: string) =>
      client.request<AdminInventoryProduct>(`/admin/products/${resourcePath(id)}`),
    inventory: async (id: string, body: InventoryInput, key: string) => {
      const result = await post<{ productId?: string; id?: string }>(
        `/admin/products/${resourcePath(id)}/inventory-adjustments`,
        body,
        key,
      );
      const resourceId = result?.productId ?? result?.id;
      if (resourceId !== id)
        throw new ApiError('INVALID_RESPONSE', '库存调整结果无效，请查询原操作');
      return { id: resourceId };
    },
    ship: (id: string, body: ShipmentInput, key: string) =>
      post<AdminOrder>(`/admin/orders/${resourcePath(id)}/ship`, body, key),
    aftersales: (query: AdminAftersaleQuery = {}) =>
      client.request<Page<AdminAftersale>>('/admin/aftersales', { query: { ...query } }),
    aftersale: (id: string) =>
      client.request<AdminAftersale>(`/admin/aftersales/${resourcePath(id)}`),
    previewDecision: (id: string, body: DecisionInput) =>
      post<DecisionPreview>(`/admin/aftersales/${resourcePath(id)}/preview-decision`, body),
    approve: (id: string, body: DecisionInput, key: string) =>
      post<AdminAftersale>(`/admin/aftersales/${resourcePath(id)}/approve`, body, key),
    reject: (id: string, body: RejectInput, key: string) =>
      post<AdminAftersale>(`/admin/aftersales/${resourcePath(id)}/reject`, body, key),
    receiveReturn: (id: string, body: ReceiveReturnInput, key: string) =>
      post<AdminAftersale>(`/admin/aftersales/${resourcePath(id)}/receive-return`, body, key),
    operation: (key: string, scope: string) =>
      client.request<OperationOutcome>(`/operations/${resourcePath(key)}`, { query: { scope } }),
  };
}
export type AdminCommerceApi = ReturnType<typeof createAdminCommerceApi>;
