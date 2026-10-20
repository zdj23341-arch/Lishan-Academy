import { hasPermission, type Page } from '@jxsc/shared';
import {
  createAdminCommerceApi,
  type AdminInventoryProduct,
} from '../../../shared/src/commerce/admin-api';
import { client } from './client';
import { environment } from './environment';
import { session } from './session';
const originalApi = createAdminCommerceApi(client);
interface InventoryRow {
  productId: string;
  type?: AdminInventoryProduct['type'];
  title?: string;
  onHand: number;
  reserved: number;
  availableStock: number;
  version: number;
}
function inventoryProduct(value: InventoryRow): AdminInventoryProduct {
  return {
    id: value.productId,
    type: value.type ?? 'BOOK',
    title: value.title,
    book: {
      availableStock: value.availableStock,
      lockedStock: value.reserved,
      physicalStock: value.onHand,
      inventoryVersion: value.version,
    },
  };
}
function usesInventoryRead() {
  return (
    environment.provider === 'http' && hasPermission(session.get()?.permissions, 'ORDER_OPERATE')
  );
}
export const commerceApi = {
  ...originalApi,
  product: async (id: string) =>
    usesInventoryRead()
      ? inventoryProduct(
          await client.request<InventoryRow>(`/admin/products/${encodeURIComponent(id)}/inventory`),
        )
      : originalApi.product(id),
  products: async (query: { keyword?: string; page: number; pageSize: number }) => {
    if (usesInventoryRead()) {
      const result = await client.request<Page<InventoryRow>>('/admin/inventory', {
        query: { ...query },
      });
      return { ...result, items: result.items.map(inventoryProduct) };
    }
    return client.request<Page<AdminInventoryProduct>>('/admin/products', {
      query: { ...query, type: 'BOOK' },
    });
  },
};
