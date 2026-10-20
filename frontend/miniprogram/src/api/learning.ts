import { createLearningApi } from '@jxsc/shared';
import { client } from './client';

export const learningApi = createLearningApi(client);
