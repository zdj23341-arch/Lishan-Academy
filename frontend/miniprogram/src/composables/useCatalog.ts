import { customerMessage } from '../utils/customer-text';
import { shallowRef, ref } from 'vue';
import { createCatalogApi, createPager, targetToRoute, type Page, type Target } from '@jxsc/shared';
import { client } from '../api/client';
export const catalog = createCatalogApi(client);
export function navigate(target: Target) {
  try {
    uni.navigateTo({ url: targetToRoute(target) });
  } catch (error) {
    uni.showToast({ title: customerMessage(error, '跳转失败'), icon: 'none' });
  }
}
export function pendingAction(label: string) {
  uni.showModal({
    title: '功能待接入',
    content: `${label}将在后续阶段实现；当前没有创建订单、播放或文件授权。`,
    showCancel: false,
  });
}
export function useCatalogPager<T, F>(loader: (filter: F, page: number) => Promise<Page<T>>) {
  const pager = createPager(loader);
  const state = shallowRef({ ...pager.state });
  async function run(action: () => Promise<void>) {
    const promise = action();
    state.value = { ...pager.state };
    await promise;
    state.value = { ...pager.state };
  }
  return {
    state,
    reset: (filter: F) =>
      run(() => {
        pager.state.items = [];
        return pager.reset(filter);
      }),
    more: () => run(pager.loadMore),
    refresh: () => run(pager.refresh),
  };
}
export function useCatalogDetail<T>(loader: (id: string) => Promise<T>) {
  const data = shallowRef<T | null>(null),
    error = ref(''),
    loading = ref(false);
  let currentId = '',
    generation = 0;
  async function load(id = currentId) {
    currentId = id;
    const epoch = ++generation;
    loading.value = true;
    error.value = '';
    data.value = null;
    try {
      const result = await loader(id);
      if (epoch === generation) data.value = result;
    } catch (cause) {
      if (epoch === generation) error.value = customerMessage(cause, '加载失败');
    } finally {
      if (epoch === generation) loading.value = false;
    }
  }
  return { data, error, loading, load };
}
