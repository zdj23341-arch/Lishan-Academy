import { createCommerceApi } from '../../../shared/src/commerce/api';
import { client } from './client';
export const commerceApi = createCommerceApi(client);

// Unpredictability is not an authorization boundary: these UUIDs identify retries only.
export function operationKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === 'x' ? value : (value & 3) | 8).toString(16);
  });
}
