import { createAdminLearningApi } from '@jxsc/shared/learning/admin-api';
import { client } from './client';
import { session } from './session';

export const learningApi = createAdminLearningApi(
  client,
  () => session.get()?.permissions ?? [],
  () => session.getGeneration(),
);
