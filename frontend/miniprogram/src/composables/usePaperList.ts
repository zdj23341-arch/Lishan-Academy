import { onUnmounted, shallowRef } from 'vue';
import { onHide, onShow, onUnload } from '@dcloudio/uni-app';
import { createPager, type PaperCard, type PaperFilters } from '@jxsc/shared';
import { catalog } from './useCatalog';
import { session } from '../api/session';

/** Paper permissions are account-specific and must be refreshed whenever this page returns. */
export function usePaperList() {
  function create() {
    return createPager<PaperCard, PaperFilters>(async (filters, page) => {
      try {
        return await catalog.papers({ ...filters, page, pageSize: 6 });
      } catch {
        throw new Error('试卷列表加载失败，请重试');
      }
    });
  }
  let pager = create();
  const state = shallowRef({ ...pager.state });
  let filters: PaperFilters = {};
  let generation = 0;
  let visible = false;
  let disposed = false;

  function invalidate(clearRows: boolean) {
    generation++;
    // Detached pagers may finish, but can never publish into this page again.
    pager = create();
    state.value = clearRows ? { ...pager.state } : { ...state.value, loading: false, error: null };
  }
  const unsubscribe = session.subscribe(() => invalidate(true));

  async function run(source: typeof pager, action: () => Promise<void>) {
    const ticket = generation;
    const actor = session.getGeneration();
    const pending = action();
    state.value = { ...source.state };
    await pending;
    session.get();
    if (!disposed && visible && ticket === generation && actor === session.getGeneration())
      state.value = { ...source.state };
  }

  function refresh() {
    session.get();
    if (disposed || !visible) return;
    const previous = state.value;
    generation++;
    const source = (pager = create());
    source.state.items = previous.items;
    source.state.total = previous.total;
    // Reset requests page one while retaining the last successful same-account rows.
    // A failed refresh keeps page zero, so retry cannot accidentally request page two.
    return run(source, () => source.reset(filters));
  }

  function reset(next: PaperFilters) {
    filters = { ...next };
    invalidate(true);
    return refresh();
  }

  function more() {
    session.get();
    if (disposed || !visible || state.value.loading) return;
    if (state.value.page === 0) return refresh();
    const source = pager;
    return run(source, () => source.loadMore());
  }

  function dispose() {
    disposed = true;
    visible = false;
    invalidate(true);
    unsubscribe();
  }
  onShow(() => {
    visible = true;
    void refresh();
  });
  onHide(() => {
    visible = false;
    invalidate(false);
  });
  onUnload(dispose);
  onUnmounted(dispose);
  return { state, reset, more, retry: more };
}
