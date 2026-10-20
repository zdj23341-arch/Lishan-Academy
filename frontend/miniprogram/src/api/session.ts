import { createSession, referralManager, type SessionStorage } from '@jxsc/shared';
import { bindOperationSession } from '../../../shared/src/commerce/operation';
import { environment } from './environment';
export const sessionStorage: SessionStorage = {
  get: (key) => {
    const value: unknown = uni.getStorageSync(key);
    return typeof value === 'string' ? value : null;
  },
  set: (key, value) => uni.setStorageSync(key, value),
  remove: (key) => uni.removeStorageSync(key),
};
export const session = createSession(sessionStorage, undefined, {
  app: 'miniprogram',
  ...environment,
});
export const operationNamespace = JSON.stringify([
  'miniprogram',
  environment.mode,
  environment.provider,
  environment.apiBaseUrl,
]);
bindOperationSession({ session, storage: sessionStorage, namespace: operationNamespace });
referralManager.bind({ session, storage: sessionStorage, namespace: operationNamespace });
