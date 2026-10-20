import { createAftersaleApi } from '../../../shared/src/commerce/aftersale';
import { client } from './client';
export const aftersaleApi = createAftersaleApi(client);
