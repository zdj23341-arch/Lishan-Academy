import { reactive } from 'vue';
import {
  createProtectedFileController,
  type ProtectedFileState,
  type ProtectedFileValidationOptions,
} from '@jxsc/shared';
import { learningApi } from '../api/learning';
import { environment } from '../api/environment';
import { createProtectedFilePlatform } from '../api/protected-files';
import { session } from '../api/session';

/** Shared runtime limits for every screen that opens a paper attachment or course video. */
export function protectedFileValidation(): ProtectedFileValidationOptions {
  // Local files are permitted only by explicit DEV/test mock-provider selection.
  const developmentHttpApiBaseUrl =
    environment.provider === 'http' &&
    ['development', 'test'].includes(environment.mode) &&
    environment.apiBaseUrl.startsWith('http://')
      ? environment.apiBaseUrl
      : undefined;
  const mockAssetUrls =
    import.meta.env.VITE_PROVIDER === 'mock' &&
    ['development', 'test'].includes(import.meta.env.MODE)
      ? [
          '/dev-files/paper-sample.docx',
          '/dev-files/paper-sample.pdf',
          '/dev-files/paper-sample.png',
          '/dev-files/paper-sample.jpg',
          '/dev-files/paper-sample.webp',
        ]
      : undefined;
  return {
    ...(developmentHttpApiBaseUrl ? { developmentHttpApiBaseUrl } : {}),
    ...(mockAssetUrls ? { mockAssetUrls } : {}),
  };
}

/**
 * One controller per page instance. Callers own disposal, because only the page knows when it is
 * being destroyed rather than merely hidden.
 */
export function useProtectedFiles() {
  const validation = protectedFileValidation();
  const platform = createProtectedFilePlatform(validation);
  const controller = createProtectedFileController({
    api: learningApi,
    session,
    platform,
    ...validation,
  });
  const files = reactive<ProtectedFileState>({ ...controller.state });
  // Core callbacks mutate a plain object; forwarding also observes session/timer cleanup.
  for (const key of Object.keys(controller.state) as Array<keyof ProtectedFileState>) {
    Object.defineProperty(controller.state, key, {
      configurable: true,
      enumerable: true,
      get: () => files[key],
      set: (value: ProtectedFileState[typeof key]) => {
        Reflect.set(files, key, value);
      },
    });
  }
  return { platform, controller, files };
}
