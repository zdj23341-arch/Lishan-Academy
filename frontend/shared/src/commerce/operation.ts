import { ApiError } from '../http';
import type { Session, SessionStorage } from '../session';
import type { LoginView } from '../contracts';
import type { OperationOutcome, OperationStatus } from './types';

export interface OperationState<T> {
  status: OperationStatus;
  key: string | null;
  requestSnapshot: Readonly<T> | null;
  resourceId: string | null;
  error: Error | null;
}
function frozen<T>(value: T): T {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(frozen);
    Object.freeze(value);
  }
  return value;
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object')
    return `{${Object.entries(value)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`)
      .join(',')}}`;
  return JSON.stringify(value);
}
export interface OperationOptions<T> {
  session: Session;
  storage: SessionStorage;
  /** Include app, environment, provider and API identity, plus the action slot. */
  namespace: string;
  scope: string;
  execute: (body: T, key: string) => Promise<{ id: string }>;
  query: (key: string) => Promise<OperationOutcome>;
  keyGenerator?: () => string;
}
type SessionBinding = Pick<OperationOptions<unknown>, 'session' | 'storage' | 'namespace'>;
interface JournalIndex {
  actor: string;
  scopes: string[];
}
const bindings = new WeakMap<Session, SessionBinding[]>();
function operationActor(login: LoginView | null): string | null {
  return login?.admin?.id ?? login?.user?.id ?? null;
}
function indexKey(namespace: string) {
  return `jxsc.operation-index:${namespace}`;
}
function journalKey(namespace: string, actor: string, scope: string) {
  return `jxsc.operation:${JSON.stringify([namespace, actor, scope])}`;
}
function readIndex(options: SessionBinding): JournalIndex | null {
  const raw = options.storage.get(indexKey(options.namespace));
  if (!raw) return null;
  try {
    const value = JSON.parse(raw);
    if (
      typeof value.actor === 'string' &&
      Array.isArray(value.scopes) &&
      value.scopes.every((scope: unknown) => typeof scope === 'string')
    )
      return value;
  } catch {
    /* A malformed index cannot be used to remove arbitrary storage keys. */
  }
  options.storage.remove(indexKey(options.namespace));
  return null;
}
/** Bind once at application startup, even if no checkout page is opened this session. */
export function bindOperationSession(options: SessionBinding): void {
  const known = bindings.get(options.session) ?? [];
  if (
    known.some(
      (binding) => binding.storage === options.storage && binding.namespace === options.namespace,
    )
  )
    return;
  const clearOtherActor = (actor: string | null) => {
    const index = readIndex(options);
    if (index && index.actor !== actor) {
      for (const scope of index.scopes)
        options.storage.remove(journalKey(options.namespace, index.actor, scope));
      options.storage.remove(indexKey(options.namespace));
    }
  };
  clearOtherActor(operationActor(options.session.get()));
  options.session.subscribe((login) => clearOtherActor(operationActor(login)));
  known.push(options);
  bindings.set(options.session, known);
}
export function createOperationController<T>(options: OperationOptions<T>) {
  bindOperationSession(options);
  const state: OperationState<T> = {
    status: 'IDLE',
    key: null,
    requestSnapshot: null,
    resourceId: null,
    error: null,
  };
  let actor = operationActor(options.session.get());
  let epoch = 0;
  let pending: Promise<void> | null = null;
  let pendingKind: 'WRITE' | 'QUERY' | null = null;
  let disposed = false;
  const actorKey = (id: string) => journalKey(options.namespace, id, options.scope);
  function clearState() {
    Object.assign(state, {
      status: 'IDLE',
      key: null,
      requestSnapshot: null,
      resourceId: null,
      error: null,
    });
    pending = null;
    pendingKind = null;
    epoch++;
  }
  function readCurrent(): Omit<OperationState<T>, 'error'> | null {
    if (!actor) return null;
    const raw = options.storage.get(actorKey(actor));
    if (!raw) return null;
    const saved = JSON.parse(raw);
    if (
      !saved ||
      typeof saved.key !== 'string' ||
      !saved.requestSnapshot ||
      !['SUBMITTING', 'UNKNOWN', 'SUCCEEDED', 'FAILED'].includes(saved.status) ||
      !(saved.resourceId === null || typeof saved.resourceId === 'string')
    )
      throw new Error('恢复记录无效');
    return {
      status: saved.status,
      key: saved.key,
      requestSnapshot: frozen(saved.requestSnapshot),
      resourceId: saved.resourceId,
    };
  }
  function synchronize() {
    const saved = readCurrent();
    if (!saved) {
      if (state.key) clearState();
      return;
    }
    if (saved.key !== state.key) clearState();
    Object.assign(state, saved, {
      status: saved.status === 'SUBMITTING' && pendingKind !== 'WRITE' ? 'UNKNOWN' : saved.status,
    });
  }
  function save(claim = false) {
    if (!actor || disposed) return;
    const current = readCurrent();
    if (claim ? current !== null : current?.key !== state.key)
      throw new ApiError('IDEMPOTENCY_CONFLICT', '原操作槽已变更，请恢复当前动作');
    const index = readIndex(options);
    const scopes = index?.actor === actor ? index.scopes : [];
    options.storage.set(
      indexKey(options.namespace),
      JSON.stringify({ actor, scopes: [...new Set([...scopes, options.scope])] }),
    );
    options.storage.set(
      actorKey(actor),
      JSON.stringify({
        status: state.status,
        key: state.key,
        requestSnapshot: state.requestSnapshot,
        resourceId: state.resourceId,
      }),
    );
  }
  if (actor) synchronize();
  const unsubscribe = options.session.subscribe((login) => {
    if (operationActor(login) === actor) return;
    if (actor) options.storage.remove(actorKey(actor));
    actor = operationActor(login);
    clearState();
    // Account changes never inherit a previous account's operation slot.
    if (actor) options.storage.remove(actorKey(actor));
  });
  function authenticate() {
    if (disposed) throw new Error('操作控制器已释放');
    if (!options.session.get() || !actor) throw new ApiError('UNAUTHORIZED', '请先登录');
  }
  function run(kind: 'WRITE' | 'QUERY', action: () => Promise<OperationOutcome>): Promise<void> {
    const ticket = epoch;
    const key = state.key;
    function currentOperation() {
      if (ticket !== epoch || disposed) return false;
      const current = readCurrent();
      if (current?.key !== key || current.status === 'SUCCEEDED' || current.status === 'FAILED') {
        synchronize();
        return false;
      }
      return true;
    }
    state.error = null;
    const task = Promise.resolve()
      .then(async () => {
        if (!currentOperation()) return;
        const outcome = await action();
        if (!currentOperation()) return;
        if (
          !['PROCESSING', 'SUCCEEDED', 'FAILED'].includes(outcome.state) ||
          (outcome.state === 'SUCCEEDED' && !outcome.resourceId)
        )
          throw new ApiError('INVALID_RESPONSE', '原操作结果无效');
        state.status = outcome.state === 'PROCESSING' ? 'UNKNOWN' : outcome.state;
        state.resourceId = outcome.resourceId ?? null;
        save();
      })
      .catch((error) => {
        if (!currentOperation()) return;
        state.error = error instanceof Error ? error : new Error('操作结果未知');
        // Classify the actual request, never a status synchronized from another controller.
        // A failed query is not proof that its original write failed.
        state.status =
          kind === 'WRITE' &&
          error instanceof ApiError &&
          error.code !== 'INVALID_RESPONSE' &&
          error.status >= 400 &&
          error.status < 500 &&
          ![408, 429].includes(error.status)
            ? 'FAILED'
            : 'UNKNOWN';
        save();
        throw state.error;
      })
      .finally(() => {
        if (ticket === epoch) {
          pending = null;
          pendingKind = null;
        }
      });
    pending = task;
    pendingKind = kind;
    return task;
  }
  function recover(): Promise<void> {
    try {
      authenticate();
      synchronize();
    } catch (error) {
      return Promise.reject(error);
    }
    if (pending) return pending;
    if (!state.key || state.status === 'IDLE') return Promise.resolve();
    if (state.status === 'SUCCEEDED' || state.status === 'FAILED') return Promise.resolve();
    const key = state.key;
    return run('QUERY', () => options.query(key));
  }
  return {
    state,
    submit(body: T): Promise<void> {
      try {
        authenticate();
        synchronize();
        const snapshot = JSON.parse(JSON.stringify(body)) as T;
        if (state.key) {
          if (canonical(snapshot) !== canonical(state.requestSnapshot))
            throw new ApiError('IDEMPOTENCY_CONFLICT', '原动作请求不可修改，请先确认原结果');
          return recover();
        }
        const key = (options.keyGenerator ?? (() => globalThis.crypto.randomUUID()))();
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key))
          throw new Error('幂等键必须为UUID');
        Object.assign(state, {
          status: 'SUBMITTING',
          key,
          requestSnapshot: frozen(snapshot),
          resourceId: null,
          error: null,
        });
        try {
          save(true);
        } catch (error) {
          clearState();
          throw error;
        }
        return run('WRITE', async () => ({
          state: 'SUCCEEDED',
          resourceId: (await options.execute(snapshot, key)).id,
        }));
      } catch (error) {
        return Promise.reject(error);
      }
    },
    recover,
    reset() {
      if (disposed) throw new Error('操作控制器已释放');
      const current = readCurrent();
      if (current && current.key !== state.key)
        throw new ApiError('IDEMPOTENCY_CONFLICT', '当前操作不属于此控制器，请先恢复');
      synchronize();
      if (state.status === 'SUBMITTING' || state.status === 'UNKNOWN')
        throw new Error('原动作结果未确认');
      if (actor) options.storage.remove(actorKey(actor));
      clearState();
    },
    dispose() {
      disposed = true;
      epoch++;
      pending = null;
      pendingKind = null;
      unsubscribe();
    },
  };
}
