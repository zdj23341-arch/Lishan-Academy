import { defineConfig, loadEnv } from 'vite';
import { courseMediaPlugin } from '../dev-tools/course-media';
import { paperFilesPlugin } from '../dev-tools/paper-files';
import uni from '@dcloudio/vite-plugin-uni';
import { readEnvironment } from '../shared/src/environment';
import { backendProxy } from '../dev-tools/backend-proxy';
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  readEnvironment({
    mode,
    provider: env.VITE_PROVIDER ?? '',
    apiBaseUrl: env.VITE_API_BASE_URL ?? '',
  });
  const factory =
    typeof uni === 'function' ? uni : (uni as unknown as { default: typeof uni }).default;
  return {
    plugins: [
      ...(command === 'serve' &&
      env.VITE_PROVIDER === 'mock' &&
      ['development', 'test'].includes(mode)
        ? [courseMediaPlugin(), paperFilesPlugin()]
        : []),
      factory(),
    ],
    optimizeDeps: { exclude: ['@jxsc/shared'] },
    server: { fs: { cachedChecks: false }, proxy: backendProxy(env, mode) },
  };
});
