// Optional local UI review configuration. The normal app build is unchanged.
import { defineConfig, mergeConfig } from 'vite';
import baseConfig from './vite.config';
export default defineConfig(async (context) => {
  const base = typeof baseConfig === 'function' ? await baseConfig(context) : await baseConfig;
  return mergeConfig(base, {
    optimizeDeps: { include: ['core-js-pure/actual/url/index.js'] },
  });
});
