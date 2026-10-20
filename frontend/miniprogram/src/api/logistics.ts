import { client } from './client';
export interface CarrierOption {
  code: string;
  name: string;
}
export const logisticsApi = {
  /** Public catalog; the server enforces the same list when a shipment is recorded. */
  carriers: () => client.request<CarrierOption[]>('/logistics/carriers'),
};
