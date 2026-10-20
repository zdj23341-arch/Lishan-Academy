import { createCourseWorkflowApi } from '@jxsc/shared/learning/course-workflow';
import { client } from './client';
import { session } from './session';
export const courseWorkflowApi = createCourseWorkflowApi(
  client,
  () => session.get()?.permissions ?? [],
);
