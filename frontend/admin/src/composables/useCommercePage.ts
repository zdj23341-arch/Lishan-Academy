import { computed, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { hasPermission } from '@jxsc/shared';
import { session, operationNamespace, operationStorage } from '../api/session';
import { commerceApi } from '../api/commerce';
export const controllerOptions = {
  session,
  storage: operationStorage,
  namespace: operationNamespace,
  api: commerceApi,
};
/** One generation covers selection, route disposal and any session replacement, including the same actor. */
export function useCommercePage(reset: () => void) {
  const busy = ref(false),
    error = ref(''),
    revision = ref(0),
    permissions = ref(session.get()?.permissions ?? []);
  let generation = 0,
    disposed = false;
  function invalidate() {
    generation++;
    busy.value = false;
    error.value = '';
    reset();
    revision.value++;
  }
  const route = useRoute();
  watch(() => route.fullPath, invalidate, { flush: 'sync' });
  const unsubscribe = session.subscribe((login) => {
    permissions.value = login?.permissions ?? [];
    invalidate();
  });
  onUnmounted(() => {
    disposed = true;
    invalidate();
    unsubscribe();
  });
  async function run(action: (current: () => boolean) => Promise<void> | void) {
    if (busy.value || disposed) return;
    const ticket = generation;
    const current = () => !disposed && ticket === generation;
    busy.value = true;
    error.value = '';
    revision.value++;
    try {
      await action(current);
    } catch (cause) {
      if (current()) error.value = cause instanceof Error ? cause.message : '请求失败';
    } finally {
      if (current()) {
        busy.value = false;
        revision.value++;
      }
    }
  }
  function requireRole(...roles: string[]) {
    if (!roles.some((role) => hasPermission(session.get()?.permissions, role)))
      throw new Error('没有此操作权限');
  }
  function capture() {
    const ticket = generation;
    return () => !disposed && ticket === generation;
  }
  return {
    busy,
    error,
    revision,
    invalidate,
    run,
    requireRole,
    capture,
    orderRole: computed(() => hasPermission(permissions.value, 'ORDER_OPERATE')),
    financeRole: computed(() => hasPermission(permissions.value, 'FINANCE_OPERATE')),
    contentRole: computed(() => hasPermission(permissions.value, 'CONTENT_EDIT')),
  };
}
export function orderLink(id: string) {
  return `/commerce/orders/${encodeURIComponent(id)}`;
}
export function isoBound(value: string) {
  if (!value) return undefined;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error('请输入有效时间');
  return date.toISOString();
}
export function display(value: unknown) {
  return value == null
    ? '不可用'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);
}
