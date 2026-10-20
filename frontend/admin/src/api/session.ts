import { createSession } from '@jxsc/shared';
import { environment } from './environment';
import { bindOperationSession } from '../../../shared/src/commerce/operation';
export const operationStorage = {
  get: (key: string) => sessionStorage.getItem(key),
  set: (key: string, value: string) => sessionStorage.setItem(key, value),
  remove: (key: string) => sessionStorage.removeItem(key),
};
export const session = createSession(
  {
    get: (key) => sessionStorage.getItem(key),
    set: (key, value) => sessionStorage.setItem(key, value),
    remove: (key) => sessionStorage.removeItem(key),
  },
  undefined,
  { app: 'admin', ...environment },
);
export const operationNamespace = JSON.stringify([
  'admin',
  environment.mode,
  environment.provider,
  environment.apiBaseUrl,
]);
bindOperationSession({ session, storage: operationStorage, namespace: operationNamespace });
