import type { OrderView, ProductType } from '../contracts';

export interface AddressWrite {
  version: number;
  name: string;
  phone: string;
  provinceCode: string;
  cityCode: string;
  districtCode: string;
  detail: string;
  isDefault: boolean;
}
export interface AddressView extends AddressWrite {
  id: string;
}
export interface PreviewRequest {
  productId: string;
  quantity: number;
  addressId?: string;
}
export interface CreateOrderRequest {
  quoteId: string;
  attributionToken?: string | null;
}
export interface QuoteView {
  quoteId: string;
  expiresAt: string;
  item: Omit<OrderView['item'], 'coverUrl'> & { coverUrl?: string | null };
  availableStock: number | null;
  goodsAmountFen: number;
  freightAmountFen: number;
  payableAmountFen: number;
  currency: string;
  warnings: string[];
}
export interface CheckoutProduct {
  id: string;
  type: ProductType;
  availableStock?: number | null;
  quantityLimit?: number;
}
export interface CheckoutDraft {
  productId: string;
  quantity: number;
  addressId?: string | null;
}
export interface OperationOutcome {
  state: 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
  resourceId: string | null;
  response?: unknown;
}
export type OperationStatus = 'IDLE' | 'SUBMITTING' | 'UNKNOWN' | 'SUCCEEDED' | 'FAILED';
