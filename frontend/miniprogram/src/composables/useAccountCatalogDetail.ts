import { onUnmounted, ref, shallowRef } from 'vue';
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app';
import { session } from '../api/session';
import { customerMessage } from '../utils/customer-text';

/** Public catalog responses can contain account-specific purchase and file permissions. */
export function useAccountCatalogDetail<T>(
  loader: (id: string) => Promise<T>,
  options: { id?: string; retainOnRefresh?: boolean; missingIdMessage?: string } = {},
) {
  const data = shallowRef<T | null>(null);
  const loading = ref(false);
  const error = ref('');
  let id = options.id ?? '';
  let sequence = 0;
  let visible = false;
  let disposed = false;

  function invalidate(clearData: boolean) {
    sequence++;
    loading.value = false;
    error.value = '';
    if (clearData) data.value = null;
  }
  const unsubscribe = session.subscribe(() => invalidate(true));
  async function load() {
    if (disposed || !visible) return;
    session.get();
    invalidate(!options.retainOnRefresh);
    if (!id.trim()) {
      error.value = options.missingIdMessage ?? '缺少内容编号';
      return;
    }
    const ticket = sequence;
    const actor = session.getGeneration();
    const current = () => {
      session.get();
      return !disposed && visible && ticket === sequence && actor === session.getGeneration();
    };
    loading.value = true;
    try {
      const result = await loader(id);
      if (current()) data.value = result;
    } catch (cause) {
      if (current()) error.value = customerMessage(cause, '加载失败，请重试');
    } finally {
      if (current()) loading.value = false;
    }
  }
  function dispose() {
    disposed = true;
    visible = false;
    invalidate(true);
    unsubscribe();
  }
  onLoad((route) => {
    if (options.id === undefined) {
      id = route?.id ?? '';
      invalidate(true);
    }
  });
  onShow(() => {
    visible = true;
    void load();
  });
  onHide(() => {
    visible = false;
    invalidate(!options.retainOnRefresh);
  });
  onUnload(dispose);
  onUnmounted(dispose);
  return { data, loading, error, load };
}
