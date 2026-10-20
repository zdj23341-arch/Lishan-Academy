import { client } from './client';
export interface CarrierOption {
  code: string;
  name: string;
}
export const logisticsApi = {
  /** Public catalog; the server rejects carrier codes outside this list. */
  carriers: () => client.request<CarrierOption[]>('/logistics/carriers'),
};
