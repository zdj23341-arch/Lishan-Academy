import { computed, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ApiError, hasPermission } from '@jxsc/shared';
import type { UploadServices } from '@jxsc/shared/content/upload';
import { session } from '../api/session';

/** Invalidate every pending read/write and upload continuation on route/session replacement. */
export function useLearningPage(reset: () => void, permission = 'CONTENT_EDIT') {
  const busy = ref(false),
    error = ref(''),
    message = ref(''),
    conflict = ref(false),
    epoch = ref(0);
  const permissions = ref(session.get()?.permissions ?? []);
  const allowed = computed(() => hasPermission(permissions.value, permission));
  let disposed = false;
  function invalidate() {
    epoch.value++;
    busy.value = false;
    error.value = message.value = '';
    conflict.value = false;
    reset();
  }
  const route = useRoute();
  watch(() => route.fullPath, invalidate, { flush: 'sync' });
  const unsubscribe = session.subscribe((value) => {
    permissions.value = value?.permissions ?? [];
    invalidate();
  });
  onUnmounted(() => {
    disposed = true;
    invalidate();
    unsubscribe();
  });
  function capture() {
    const ticket = epoch.value;
    return () => !disposed && ticket === epoch.value && allowed.value;
  }
  async function run(action: (current: () => boolean) => Promise<void>, success = '') {
    if (busy.value || !allowed.value || disposed) return;
    const current = capture();
    busy.value = true;
    error.value = message.value = '';
    conflict.value = false;
    try {
      await action(current);
      if (current()) message.value = success;
    } catch (cause) {
      if (current()) {
        conflict.value = cause instanceof ApiError && cause.code === 'VERSION_CONFLICT';
        const detail =
          cause instanceof ApiError
            ? [cause.message, ...Object.values(cause.fieldErrors)].join('；')
            : cause instanceof Error
              ? cause.message
              : '操作失败，请重试';
        error.value = /(?:https?:\/\/|uploadToken|signature=)/i.test(detail)
          ? '请求失败，请重试或重新读取'
          : detail;
      }
    } finally {
      if (current()) busy.value = false;
    }
  }
  function validate(errors: Record<string, string>) {
    if (!Object.keys(errors).length) return;
    throw new ApiError('VALIDATION_ERROR', '请检查输入', null, errors);
  }
  function guardedUploads(services: UploadServices): UploadServices {
    const current = capture();
    function check() {
      if (!current()) throw new ApiError('FORBIDDEN', '页面或登录状态已变化');
    }
    return {
      ...(services.read
        ? {
            async read(id: string) {
              check();
              const value = await services.read!(id);
              check();
              return value;
            },
          }
        : {}),
      async presign(input) {
        check();
        const value = await services.presign(input);
        check();
        return value;
      },
      async transfer(ticket, file) {
        check();
        await services.transfer(ticket, file);
        check();
      },
      async complete(id) {
        check();
        const value = await services.complete(id);
        check();
        return value;
      },
    };
  }
  return { busy, error, message, conflict, epoch, allowed, run, validate, capture, guardedUploads };
}
