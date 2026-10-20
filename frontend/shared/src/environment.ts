import { RuntimeURL } from './runtime-url';

export interface EnvironmentInput {
  mode: string;
  provider: string;
  apiBaseUrl: string;
  uploadBaseUrl?: string;
  imageBaseUrl?: string;
  videoBaseUrl?: string;
  logEnabled?: string;
  developmentProxy?: string;
  browserOrigin?: string;
}
export interface Environment {
  mode: 'development' | 'test' | 'production';
  provider: 'mock' | 'http';
  apiBaseUrl: string;
  uploadBaseUrl: string;
  imageBaseUrl: string;
  videoBaseUrl: string;
  logEnabled: boolean;
}
function endpoint(value: string, production: boolean): string {
  if (!/^https?:\/\/[^\s/?#@]+(?::\d+)?(?:\/[^\s?#]*)?$/.test(value))
    throw new Error('无效的服务地址');
  if (production && !value.startsWith('https://')) throw new Error('生产环境必须使用HTTPS');
  return value.replace(/\/+$/, '');
}
export function readEnvironment(input: EnvironmentInput): Environment {
  if (!['development', 'test', 'production'].includes(input.mode)) throw new Error('未知环境');
  if (input.provider !== 'mock' && input.provider !== 'http')
    throw new Error('必须显式选择mock或http');
  const production = input.mode === 'production';
  if (production && input.provider === 'mock') throw new Error('生产环境禁止模拟能力');
  const apiBaseUrl = endpoint(input.apiBaseUrl, production);
  const backendOrigin = new RuntimeURL(apiBaseUrl).origin;
  const proxyOrigin =
    input.mode === 'development' &&
    input.provider === 'http' &&
    input.developmentProxy === 'true' &&
    input.browserOrigin
      ? new RuntimeURL(endpoint(input.browserOrigin, false)).origin
      : null;
  const serviceEndpoint = (value: string) => {
    const valid = endpoint(value, production);
    const url = new RuntimeURL(valid);
    return proxyOrigin && url.origin === backendOrigin
      ? `${proxyOrigin}${url.pathname === '/' ? '' : url.pathname}`
      : valid;
  };
  return {
    mode: input.mode as Environment['mode'],
    provider: input.provider,
    apiBaseUrl: serviceEndpoint(apiBaseUrl),
    uploadBaseUrl: serviceEndpoint(input.uploadBaseUrl || apiBaseUrl),
    imageBaseUrl: serviceEndpoint(input.imageBaseUrl || apiBaseUrl),
    videoBaseUrl: serviceEndpoint(input.videoBaseUrl || apiBaseUrl),
    logEnabled: !production && input.logEnabled === 'true',
  };
}

export function usesDevelopmentLogin(
  environment: Pick<EnvironmentInput, 'mode' | 'provider'>,
  enabled?: string,
): boolean {
  return (
    ['development', 'test'].includes(environment.mode) &&
    (environment.provider === 'mock' || enabled === 'true')
  );
}
