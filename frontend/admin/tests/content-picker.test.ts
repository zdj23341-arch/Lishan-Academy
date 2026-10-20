// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ContentPicker from '../src/components/ContentPicker.vue';

const wrappers: VueWrapper[] = [];
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
});
const option = (id: string) => ({
  id,
  title: `课程${id}`,
  kind: 'COURSE',
  status: '已上架',
  available: true,
  coverUrl: null,
});
function openPicker() {
  const source = {
    search: vi.fn(async (_kind: string, query: { page: number }) => ({
      items: [option(String(query.page))],
      total: 2,
      page: query.page,
      pageSize: 1,
    })),
    resolve: vi.fn(async (_kind: string, id: string) => option(id)),
  };
  const w = mount(ContentPicker, {
    props: { modelValue: [], kind: 'COURSE', source },
    global: {
      stubs: {
        ElDialog: {
          template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>',
          props: ['modelValue'],
        },
      },
    },
  });
  wrappers.push(w);
  return { w, source };
}
async function click(w: VueWrapper, text: string) {
  await w
    .findAll('button')
    .find((b) => b.text() === text)!
    .trigger('click');
  await flushPromises();
}
it('keeps cross-page selection local until confirm and preserves its chosen order', async () => {
  const { w } = openPicker();
  await click(w, '选择内容');
  await w.get('input[type=checkbox]').setValue(true);
  await click(w, '下一页');
  await w.get('input[type=checkbox]').setValue(true);
  expect(w.emitted('update:modelValue')).toBeUndefined();
  await w.get('button[aria-label="上移课程2"]').trigger('click');
  await click(w, '确认选择');
  expect(w.emitted('update:modelValue')?.[0]).toEqual([['2', '1']]);
});
it('discards cancelled selection and searches on the server with a reset page', async () => {
  const { w, source } = openPicker();
  await click(w, '选择内容');
  await w.get('input[type=checkbox]').setValue(true);
  await click(w, '取消');
  await click(w, '选择内容');
  expect((w.get('input[type=checkbox]').element as HTMLInputElement).checked).toBe(false);
  await click(w, '下一页');
  await w.get('input[type=search]').setValue('语文');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(source.search).toHaveBeenLastCalledWith('COURSE', {
    keyword: '语文',
    page: 1,
    pageSize: 10,
  });
});

it('preserves a previously bound item after a read failure and allows removing it explicitly', async () => {
  const { w, source } = openPicker();
  source.resolve.mockRejectedValue(new Error('offline'));
  await w.setProps({ modelValue: ['legacy'] });
  await flushPromises();
  expect(w.text()).toContain('原关联内容暂不可读');
  await click(w, '选择内容');
  await click(w, '确认选择');
  expect(w.emitted('update:modelValue')?.[0]).toEqual([['legacy']]);
  await click(w, '选择内容');
  await w.get('button[aria-label="移除原关联内容暂不可读"]').trigger('click');
  await click(w, '确认选择');
  expect(w.emitted('update:modelValue')?.[1]).toEqual([[]]);
});

it('shows query failure without losing the selection and retries the same page', async () => {
  const { w, source } = openPicker();
  await click(w, '选择内容');
  await w.get('input[type=checkbox]').setValue(true);
  source.search.mockRejectedValueOnce(new Error('网络失败'));
  await click(w, '下一页');
  expect(w.get('[role=alert]').text()).toContain('网络失败');
  await click(w, '重试');
  await click(w, '确认选择');
  expect(w.emitted('update:modelValue')?.[0]).toEqual([['1']]);
});
