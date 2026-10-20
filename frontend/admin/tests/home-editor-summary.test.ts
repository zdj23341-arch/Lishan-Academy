// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import HomeEditor from '../src/views/content/HomeEditor.vue';
const api = vi.hoisted(() => ({ homeDraft: vi.fn(), saveHome: vi.fn() }));
vi.mock('../src/api/content', () => ({ contentApi: api }));
afterEach(() => vi.resetAllMocks());
it('opens a summary for editing, keeps its identity after sorting, and saves unchanged fields', async () => {
  const draft = {
    revision: 2,
    banners: ['a', 'b'].map((id, sortNo) => ({
      id,
      title: `轮播${id}`,
      assetId: `image-${id}`,
      enabled: true,
      sortNo,
      target: { type: 'PRODUCT_LIST', id: null, filters: {} },
    })),
    shortcuts: [
      {
        id: 'a',
        label: '课程入口',
        assetId: 'shortcut-image',
        enabled: true,
        sortNo: 0,
        target: { type: 'LEARNING', id: null, filters: {} },
      },
    ],
    sections: [],
  };
  api.homeDraft.mockResolvedValue(structuredClone(draft));
  api.saveHome.mockImplementation(async (value) => value);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: HomeEditor }],
  });
  await router.push('/');
  const w = mount(
    { template: '<router-view />' },
    {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: { AssetPicker: { template: '<p>图片选择器</p>' }, InternalTargetEditor: true },
      },
    },
  );
  await flushPromises();
  try {
    expect(w.findAll('.draggable-item-card').every((f) => !f.isVisible())).toBe(true);
    await w
      .findAll('button')
      .find((b) => b.text() === '配置轮播')!
      .trigger('click');
    expect(w.findAll('.item-fields').every((f) => !f.isVisible())).toBe(true);
    await w
      .findAll('button')
      .find((b) => b.text() === '编辑配置')!
      .trigger('click');
    const input = w.findAll('.item-fields')[0]!.get('input');
    await input.setValue('修改后的标题');
    await w
      .findAll('button')
      .find((b) => b.text() === '配置入口')!
      .trigger('click');
    const shortcut = w.get('#home-shortcut-config');
    expect(shortcut.get('button[aria-expanded]').attributes('aria-expanded')).toBe('false');
    expect(shortcut.get('.item-fields').isVisible()).toBe(false);
    await w
      .findAll('button')
      .find((b) => b.text() === '收起轮播')!
      .trigger('click');
    await w
      .findAll('button')
      .find((b) => b.text() === '配置轮播')!
      .trigger('click');
    expect((w.findAll('.item-fields')[0]!.get('input').element as HTMLInputElement).value).toBe(
      '修改后的标题',
    );
    await w
      .findAll('.draggable-item-card')[0]!
      .findAll('button')
      .find((b) => b.text() === '↓ 下移')!
      .trigger('click');
    expect(w.findAll('.draggable-item-card')[1]!.get('.item-fields').isVisible()).toBe(true);
    await w
      .findAll('button')
      .find((b) => b.text() === '保存草稿')!
      .trigger('click');
    await flushPromises();
    expect(api.saveHome).toHaveBeenCalledWith(
      expect.objectContaining({
        revision: 2,
        banners: [
          expect.objectContaining({ id: 'b', assetId: 'image-b', sortNo: 0 }),
          expect.objectContaining({
            id: 'a',
            title: '修改后的标题',
            assetId: 'image-a',
            sortNo: 1,
          }),
        ],
      }),
    );
  } finally {
    w.unmount();
  }
});
