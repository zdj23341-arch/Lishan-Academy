import { computed, onUnmounted, ref, shallowRef } from 'vue';
import { hasPermission, type AdminUser } from '@jxsc/shared';
import { session } from '../api/session';
import { adminOperationsApi } from '../api/operations';

export function useUserDirectory(options: {
  askReason: (row: AdminUser, action: string) => Promise<string | null>;
}) {
  const account = shallowRef(session.get());
  const allowed = computed(() => hasPermission(account.value?.permissions, 'USER_MANAGE'));
  const loading = ref(false),
    updating = ref(false);
  const list = ref<AdminUser[]>([]),
    total = ref(0),
    page = ref(1),
    pageSize = ref(20);
  const keyword = ref(''),
    statusFilter = ref(''),
    error = ref(''),
    message = ref('');
  let sequence = 0,
    disposed = false;
  const unsubscribe = session.subscribe((value) => {
    sequence++;
    account.value = value;
    list.value = [];
    total.value = 0;
    page.value = 1;
    keyword.value = statusFilter.value = error.value = message.value = '';
    loading.value = updating.value = false;
  });
  function actorCurrent(actor: number) {
    session.get();
    return !disposed && actor === session.getGeneration() && allowed.value;
  }
  async function loadData() {
    session.get();
    if (disposed || !allowed.value) {
      list.value = [];
      total.value = 0;
      return;
    }
    const actor = session.getGeneration(),
      ticket = ++sequence;
    const active = () => actorCurrent(actor) && ticket === sequence;
    loading.value = true;
    error.value = '';
    try {
      const value = await adminOperationsApi.listUsers({
        keyword: keyword.value.trim() || undefined,
        status: statusFilter.value || undefined,
        page: page.value,
        pageSize: pageSize.value,
      });
      if (active()) {
        list.value = value.items;
        total.value = value.total;
      }
    } catch (cause) {
      if (active()) error.value = cause instanceof Error ? cause.message : '加载用户列表失败';
    } finally {
      if (active()) loading.value = false;
    }
  }
  function handleSearch() {
    page.value = 1;
    return loadData();
  }
  function handlePageChange(value: number) {
    page.value = value;
    return loadData();
  }
  function handleSizeChange(value: number) {
    pageSize.value = value;
    page.value = 1;
    return loadData();
  }
  async function handleToggleStatus(row: AdminUser) {
    session.get();
    if (disposed || !allowed.value || updating.value || !['NORMAL', 'BLOCKED'].includes(row.status))
      return;
    const actor = session.getGeneration();
    const target = row.status === 'NORMAL' ? 'BLOCKED' : 'NORMAL';
    const action = target === 'BLOCKED' ? '封禁' : '解封';
    // Snapshot before confirmation; its result must never act for another administrator.
    const id = row.id,
      version = row.version;
    updating.value = true;
    error.value = message.value = '';
    try {
      const input = await options.askReason(row, action);
      if (!actorCurrent(actor) || input === null) return;
      const reason = input.trim();
      if (!reason || reason.length > 500) throw new Error('原因必须为1～500个字符');
      if (!Number.isSafeInteger(version) || version < 0)
        throw new Error('服务器未提供有效版本，请刷新');
      const value = await adminOperationsApi.updateUserStatus(id, target, reason, version);
      if (!actorCurrent(actor)) return;
      if (value.id !== id || value.status !== target) throw new Error('状态响应不匹配，请刷新确认');
      list.value = list.value.map((item) => (item.id === id ? value : item));
      message.value = `用户${action}成功`;
      await loadData();
    } catch (cause) {
      if (actorCurrent(actor) && cause !== 'cancel' && cause !== 'close')
        error.value = cause instanceof Error ? cause.message : '用户状态操作失败，请刷新确认';
    } finally {
      if (actorCurrent(actor)) updating.value = false;
    }
  }
  onUnmounted(() => {
    disposed = true;
    sequence++;
    unsubscribe();
  });
  return {
    allowed,
    loading,
    updating,
    list,
    total,
    page,
    pageSize,
    keyword,
    statusFilter,
    error,
    message,
    loadData,
    handleSearch,
    handlePageChange,
    handleSizeChange,
    handleToggleStatus,
  };
}
