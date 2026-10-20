import { createAdminDistributionApi } from '@jxsc/shared';
import { client } from './client';

export const adminDistributionApi = createAdminDistributionApi(client);
