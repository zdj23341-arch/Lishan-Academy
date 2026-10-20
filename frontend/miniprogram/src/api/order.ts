import { createOrderApi } from '../../../shared/src/commerce/order';
import { createPaymentApi } from '../../../shared/src/commerce/payment';
import { client } from './client';
import { environment } from './environment';
export const orderApi = createOrderApi(client);
export const paymentApi = createPaymentApi(client, { mode: environment.mode });
