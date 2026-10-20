import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readEnvironment } from '../shared/src/environment';
import { backendProxy } from '../dev-tools/backend-proxy';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  readEnvironment({
    mode,
    provider: env.VITE_PROVIDER ?? '',
    apiBaseUrl: env.VITE_API_BASE_URL ?? '',
  });
  return {
    plugins: [vue()],
    optimizeDeps: { exclude: ['@jxsc/shared'] },
    // New sibling-workspace modules must resolve while this server is running.
    server: {
      fs: { cachedChecks: false },
      proxy: backendProxy(env, mode),
    },
  };
});
