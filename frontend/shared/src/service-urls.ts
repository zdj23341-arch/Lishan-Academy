import { RuntimeURL } from './runtime-url';

/** Resolve backend URL fields for clients that have no browser document origin. */
export function resolveServiceUrls<T>(data: T, apiBaseUrl: string): T {
  const origin = new RuntimeURL(apiBaseUrl).origin;
  const resolve = (value: unknown, key = ''): unknown => {
    if (typeof value === 'string')
      return (key === 'url' || key.endsWith('Url')) && value.startsWith('/api/')
        ? `${origin}${value}`
        : value;
    if (Array.isArray(value)) return value.map((item) => resolve(item));
    if (value !== null && typeof value === 'object')
      return Object.fromEntries(
        Object.entries(value).map(([name, item]) => [name, resolve(item, name)]),
      );
    return value;
  };
  return resolve(data) as T;
}
