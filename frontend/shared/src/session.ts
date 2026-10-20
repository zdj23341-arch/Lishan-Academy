import type { LoginView } from './contracts';
import type { Environment } from './environment';
export interface SessionScope extends Pick<Environment, 'mode' | 'provider' | 'apiBaseUrl'> {
  app: string;
}
export interface SessionStorage {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}
function isLogin(value: unknown): value is LoginView {
  if (!value || typeof value !== 'object') return false;
  const login = value as Partial<LoginView>;
  const hasToken =
    typeof login.accessToken === 'string' &&
    login.accessToken.length > 0 &&
    typeof login.expiresAt === 'string' &&
    /(?:Z|[+-]\d{2}:\d{2})$/.test(login.expiresAt) &&
    Number.isFinite(Date.parse(login.expiresAt));
  if (!hasToken) return false;

  const hasCustomerUser =
    !!login.user &&
    typeof login.user.id === 'string' &&
    typeof login.user.nickname === 'string' &&
    typeof login.user.distributor === 'boolean';

  const hasAdminPermissions =
    Array.isArray(login.permissions) && login.permissions.every((item) => typeof item === 'string');

  const hasAdminUser =
    !!login.admin &&
    typeof login.admin.id === 'string' &&
    typeof login.admin.loginName === 'string';

  return (
    (hasCustomerUser || hasAdminPermissions || hasAdminUser) &&
    (login.permissions === undefined || hasAdminPermissions)
  );
}
export function isInternalRoute(value: string): boolean {
  return (
    /^\/(?!\/)/.test(value) &&
    !value.includes('\\') &&
    ![...value].some((character) => character.charCodeAt(0) <= 32) &&
    !/%2f/i.test(value.split(/[?#]/, 1)[0]!) &&
    !/%(?:5c|0[0-9a-f])/i.test(value)
  );
}
export function createSession(
  storage: SessionStorage,
  onClear: () => void = () => {},
  scope?: SessionScope,
) {
  // Unscoped storage remains available to standalone consumers; apps never restore it.
  const storageKey = scope
    ? `jxsc.session:${JSON.stringify([scope.app, scope.mode, scope.provider, scope.apiBaseUrl])}`
    : 'jxsc.session';
  let generation = 0;
  let current: LoginView | null = null;
  const listeners = new Set<(value: LoginView | null) => void>();
  function notify() {
    for (const listener of listeners) listener(current);
  }
  try {
    const raw = storage.get(storageKey);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (isLogin(parsed)) current = parsed;
      else storage.remove(storageKey);
    }
  } catch {
    storage.remove(storageKey);
  }
  let returnTo: string | null = null;
  function clear() {
    generation++;
    current = null;
    storage.remove(storageKey);
    onClear();
    notify();
  }
  function get(): LoginView | null {
    if (current && Date.parse(current.expiresAt) <= Date.now()) clear();
    return current;
  }
  get();
  return {
    get,
    getGeneration: () => generation,
    clear,
    set(value: LoginView) {
      if (!isLogin(value)) throw new Error('无效登录响应');
      generation++;
      current = value;
      storage.set(storageKey, JSON.stringify(value));
      if (Date.parse(value.expiresAt) <= Date.now()) clear();
      else notify();
    },
    subscribe(listener: (value: LoginView | null) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    rememberReturnTo(route: string) {
      if (isInternalRoute(route) && !returnTo) returnTo = route;
    },
    takeReturnTo() {
      const route = returnTo;
      returnTo = null;
      return route;
    },
  };
}
export type Session = ReturnType<typeof createSession>;
