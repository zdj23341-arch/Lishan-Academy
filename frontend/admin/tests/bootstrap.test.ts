// @vitest-environment jsdom
import { expect, it, vi } from 'vitest';
import { defineComponent, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createAdminApplication } from '../src/bootstrap';

it('actual admin bootstrap renders the form and table components used by operational pages', async () => {
  vi.useFakeTimers();
  const component = defineComponent({
    template:
      '<div><el-input model-value="31" aria-label="任务ID" /><el-select model-value="ORDERS"><el-option label="订单" value="ORDERS" /></el-select><el-table :data="[{ id: 31 }]"><el-table-column prop="id" label="ID" /></el-table><el-pagination :total="40" /></div>',
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }],
  });
  await router.push('/');
  const container = document.createElement('div');
  document.body.appendChild(container);
  const app = createAdminApplication(component, router);
  try {
    app.mount(container);
    await nextTick();
    await vi.runOnlyPendingTimersAsync();
    expect(container.querySelector('input')?.value).toBe('31');
    expect(container.querySelector('.el-select')).not.toBeNull();
    expect(container.querySelector('.el-table')).not.toBeNull();
    expect(container.querySelector('.el-pagination')).not.toBeNull();
    expect(container.querySelector('el-input, el-select, el-table, el-pagination')).toBeNull();
  } finally {
    app.unmount();
    container.remove();
    vi.clearAllTimers();
    vi.useRealTimers();
  }
});
