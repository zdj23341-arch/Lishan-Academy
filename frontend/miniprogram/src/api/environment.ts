import { readEnvironment } from '@jxsc/shared';

export const environment = readEnvironment({
  mode: import.meta.env.MODE,
  provider: import.meta.env.VITE_PROVIDER,
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  uploadBaseUrl: import.meta.env.VITE_UPLOAD_BASE_URL,
  imageBaseUrl: import.meta.env.VITE_IMAGE_BASE_URL,
  videoBaseUrl: import.meta.env.VITE_VIDEO_BASE_URL,
  developmentProxy: import.meta.env.VITE_DEV_PROXY,
  // #ifdef H5
  browserOrigin: typeof window !== 'undefined' ? window.location.origin : undefined,
  // #endif
});
