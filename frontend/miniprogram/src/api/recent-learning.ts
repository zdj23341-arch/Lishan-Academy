import { createRecentLearning } from '../../../shared/src/learning/recent';
import { learningApi } from './learning';
import { session, sessionStorage } from './session';
import { environment } from './environment';

export const recentLearning = createRecentLearning({
  session,
  storage: sessionStorage,
  api: learningApi,
  namespace: JSON.stringify([
    'miniprogram',
    environment.mode,
    environment.provider,
    environment.apiBaseUrl,
  ]),
});
