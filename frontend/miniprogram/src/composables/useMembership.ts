import { computed, onUnmounted, ref, shallowRef } from 'vue';
import { onHide, onShow, onUnload } from '@dcloudio/uni-app';
import type { MembershipView } from '@jxsc/shared';
import { learningApi } from '../api/learning';
import { session } from '../api/session';

type MembershipPlanOption = {
  productId: string;
  durationMonths: number;
  priceFen: number;
  projectedExpiresAt?: string;
};

/** Page-local projections; dates and prices always come from the membership API. */
export function useMembership() {
  const membership = shallowRef<MembershipView | null>(null);
  const publicPlans = shallowRef<MembershipPlanOption[]>([]);
  const authenticated = ref(!!session.get());
  const plans = computed(() => membership.value?.plans ?? publicPlans.value);
  const loading = ref(false);
  const error = ref('');
  let generation = 0;
  let disposed = false;
  function clear() {
    generation++;
    membership.value = null;
    publicPlans.value = [];
    authenticated.value = !!session.get();
    loading.value = false;
    error.value = '';
  }
  const unsubscribe = session.subscribe(clear);
  async function refresh() {
    if (disposed) return;
    session.get();
    const actor = session.getGeneration();
    clear();
    const ticket = generation;
    loading.value = true;
    const current = () => {
      session.get();
      return !disposed && ticket === generation && actor === session.getGeneration();
    };
    try {
      if (authenticated.value) {
        const result = await learningApi.membershipMe();
        if (current()) membership.value = result;
      } else {
        const available: MembershipPlanOption[] = [];
        for (let page = 1; ; page++) {
          const result = await learningApi.membershipPlans(page);
          if (!current()) return;
          available.push(...result.items);
          if (!result.items.length || result.page * result.pageSize >= result.total) break;
        }
        if (current()) publicPlans.value = available;
      }
    } catch {
      if (current())
        error.value = authenticated.value ? '会员信息加载失败，请重试' : '会员套餐加载失败，请重试';
    } finally {
      if (current()) loading.value = false;
    }
  }
  function dispose() {
    disposed = true;
    clear();
    unsubscribe();
  }
  const stateLabel = computed(() => {
    if (!authenticated.value) return '登录后查看会员状态';
    switch (membership.value?.state) {
      case 'ACTIVE':
        return '已生效';
      case 'EXPIRED':
        return '会员已到期';
      case 'NONE':
        return '未开通会员';
      default:
        return '会员状态待确认';
    }
  });
  const actionLabel = computed(() =>
    membership.value?.state === 'ACTIVE' || membership.value?.state === 'EXPIRED'
      ? '续费会员'
      : '开通会员',
  );
  onShow(() => {
    void refresh();
  });
  onHide(clear);
  onUnload(dispose);
  onUnmounted(dispose);
  return { membership, plans, loading, error, refresh, stateLabel, actionLabel };
}
