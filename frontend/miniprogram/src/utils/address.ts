import type { AddressView } from '../../../shared/src/commerce/types';
import { commerceApi } from '../api/commerce';
import { regionLabel } from './regions';
export function addressLabel(address: AddressView) {
  return `${regionLabel([address.provinceCode, address.cityCode, address.districtCode])} ${address.detail}`;
}
export async function findAddress(id?: string | null): Promise<AddressView | null> {
  let page = 1;
  while (true) {
    const result = await commerceApi.addresses(page);
    const match = result.items.find((item) => (id ? item.id === id : item.isDefault));
    if (match) return match;
    if (!result.items.length || result.page * result.pageSize >= result.total) return null;
    page++;
  }
}
