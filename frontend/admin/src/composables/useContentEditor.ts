import { onUnmounted, ref } from 'vue';
import { ApiError } from '@jxsc/shared';

export function useContentEditor() {
  const busy = ref(false),
    error = ref(''),
    message = ref('');
  let generation = 0;
  function invalidate() {
    generation++;
    busy.value = false;
    error.value = message.value = '';
  }
  onUnmounted(invalidate);
  async function run(action: (current: () => boolean) => Promise<void>, success = '') {
    if (busy.value) return;
    busy.value = true;
    const ticket = generation;
    const current = () => ticket === generation;
    error.value = '';
    message.value = '';
    try {
      await action(current);
      if (current()) message.value = success;
    } catch (cause) {
      if (current())
        error.value =
          cause instanceof ApiError
            ? [cause.message, ...Object.values(cause.fieldErrors)].join('；')
            : cause instanceof Error
              ? cause.message
              : '操作失败';
    } finally {
      if (current()) busy.value = false;
    }
  }
  return { busy, error, message, run, invalidate };
}
