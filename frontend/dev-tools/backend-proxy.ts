export function backendProxy(env: Record<string, string>, mode: string) {
  if (mode !== 'development' || env.VITE_PROVIDER !== 'http' || env.VITE_DEV_PROXY !== 'true')
    return undefined;
  const apiBaseUrl = env.VITE_API_BASE_URL;
  if (!apiBaseUrl) throw new Error('开发代理需要配置后端API地址');
  return {
    '^/api/': {
      target: new URL(apiBaseUrl).origin,
      changeOrigin: true,
    },
  };
}
