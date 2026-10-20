import type { Page } from './contracts';
export interface PagerState<T> {
  items: T[];
  page: number;
  total: number;
  loading: boolean;
  error: Error | null;
  hasMore: boolean;
}
export function createPager<T, Filter>(loader: (filter: Filter, page: number) => Promise<Page<T>>) {
  const state: PagerState<T> = {
    items: [],
    page: 0,
    total: 0,
    loading: false,
    error: null,
    hasMore: true,
  };
  let version = 0;
  let filter: Filter;
  let initialized = false;
  let pending: Promise<void> | null = null;
  function load(page: number, epoch: number): Promise<void> {
    state.loading = true;
    state.error = null;
    const requestedFilter = filter;
    const request = (async () => {
      try {
        const result = await Promise.resolve().then(() => loader(requestedFilter, page));
        if (epoch !== version) return;
        if (
          !Array.isArray(result.items) ||
          result.page !== page ||
          !Number.isInteger(result.total) ||
          result.total < 0 ||
          !Number.isInteger(result.pageSize) ||
          result.pageSize < 1
        )
          throw new Error('分页响应无效');
        state.items = page === 1 ? result.items : [...state.items, ...result.items];
        state.page = page;
        state.total = result.total;
        state.hasMore = page * result.pageSize < result.total;
      } catch (error) {
        if (epoch === version) state.error = error instanceof Error ? error : new Error('加载失败');
      } finally {
        if (epoch === version) {
          state.loading = false;
          pending = null;
        }
      }
    })();
    pending = request;
    return request;
  }
  function reset(next: Filter) {
    filter = next;
    initialized = true;
    version++;
    state.page = 0;
    state.hasMore = true;
    return load(1, version);
  }
  return {
    state,
    load: reset,
    reset,
    loadMore() {
      if (!initialized || !state.hasMore) return Promise.resolve();
      if (pending) return pending;
      return load(state.page + 1, version);
    },
    refresh() {
      if (!initialized) return Promise.resolve();
      version++;
      return load(1, version);
    },
  };
}
