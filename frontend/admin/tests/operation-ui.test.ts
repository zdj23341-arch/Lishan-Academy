// @vitest-environment jsdom
import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import ContentSelectionPanel from '../src/components/ui/ContentSelectionPanel.vue';
import HomeDisplayMode from '../src/components/ui/HomeDisplayMode.vue';
import CourseFlowSteps from '../src/components/ui/CourseFlowSteps.vue';
import UploadFileStatus from '../src/components/ui/UploadFileStatus.vue';

it('keeps unavailable selections visible, permits removal, and blocks unavailable candidates', async () => {
  const item = {
    key: 'book:7',
    title: '阅读与表达',
    typeLabel: '图书',
    statusLabel: '已下架',
    available: false,
    unavailableReason: '已下架，请更换内容',
  };
  const w = mount(ContentSelectionPanel, { props: { items: [item], selected: [item], total: 1 } });
  expect(w.text()).toContain('已下架，请更换内容');
  expect(w.get('input[type=checkbox]').attributes('disabled')).toBeDefined();
  await w.get('button[aria-label="移除阅读与表达"]').trigger('click');
  expect(w.emitted('remove')?.[0]).toEqual(['book:7']);
  await w.setProps({ busy: true });
  await w.get('button[aria-label="移除阅读与表达"]').trigger('click');
  expect(w.emitted('remove')).toHaveLength(1);
  w.unmount();
});

it('emits explicit search, page and ordered-selection intents without changing parent data', async () => {
  const a = { key: 'course:1', title: '写作课', typeLabel: '课程', statusLabel: '已上架' };
  const b = { ...a, key: 'course:2', title: '阅读课' };
  const w = mount(ContentSelectionPanel, {
    props: { items: [a], selected: [a, b], total: 21, page: 1, pageSize: 10 },
  });
  await w.get('input[type=search]').setValue('语文');
  await w.get('form').trigger('submit');
  expect(w.emitted('search')?.[0]).toEqual(['语文']);
  await w.get('button[aria-label="下一页内容"]').trigger('click');
  expect(w.emitted('page')?.[0]).toEqual([2]);
  await w.get('button[aria-label="上移阅读课"]').trigger('click');
  expect(w.emitted('move')?.[0]).toEqual([1, 0]);
  expect(w.props('selected')).toEqual([a, b]);
  w.unmount();
});

it('shows a recoverable error separately from empty results and retains selected content', () => {
  const w = mount(ContentSelectionPanel, {
    props: {
      items: [],
      selected: [{ key: 'paper:1', title: '期末卷', typeLabel: '试卷', statusLabel: '已发布' }],
      total: 0,
      error: '网络断开，请重试',
    },
  });
  expect(w.get('[role=alert]').text()).toContain('网络断开');
  expect(w.text()).toContain('期末卷');
  expect(w.text()).not.toContain('没有找到内容');
  w.unmount();
});

it('keeps mode changes controlled and blocks them during a save', async () => {
  const w = mount(HomeDisplayMode, { props: { modelValue: 'manual', count: 4 } });
  await w.get('input[value=automatic]').setValue(true);
  expect(w.emitted('update:modelValue')?.[0]).toEqual(['automatic']);
  expect(w.props('modelValue')).toBe('manual');
  await w.setProps({ busy: true });
  expect(w.get('input[value=automatic]').attributes('disabled')).toBeDefined();
  expect(w.text()).toContain('发布首页后生效');
  w.unmount();
});

it('keeps each home section mode independently selected', async () => {
  const w = mount(
    defineComponent({
      components: { HomeDisplayMode },
      data: () => ({ first: 'manual', second: 'manual' }),
      template: `<div>
        <HomeDisplayMode v-model="first" :count="4" />
        <HomeDisplayMode v-model="second" :count="4" />
      </div>`,
    }),
    { attachTo: document.body },
  );
  try {
    const [first, second] = w.findAllComponents(HomeDisplayMode);
    expect((first!.get('input[value=manual]').element as HTMLInputElement).checked).toBe(true);
    expect((second!.get('input[value=manual]').element as HTMLInputElement).checked).toBe(true);
    await first!.get('input[value=automatic]').setValue(true);
    expect((first!.get('input[value=automatic]').element as HTMLInputElement).checked).toBe(true);
    expect((second!.get('input[value=manual]').element as HTMLInputElement).checked).toBe(true);
  } finally {
    w.unmount();
  }
});

it('distinguishes completed, current and locked course steps', async () => {
  const w = mount(CourseFlowSteps, {
    props: { current: 'chapters', completed: ['basics'], locked: ['publish'] },
  });
  expect(w.get('[aria-current=step]').text()).toContain('章节与视频');
  await w.get('button[data-step=basics]').trigger('click');
  expect(w.emitted('select')?.[0]).toEqual(['basics']);
  expect(w.get('button[data-step=publish]').attributes('disabled')).toBeDefined();
  w.unmount();
});

it('only offers upload retry for failures and exposes processing without claiming readiness', async () => {
  const w = mount(UploadFileStatus, { props: { name: '第一讲.mp4', state: 'processing' } });
  expect(w.text()).toContain('上传完成，处理中');
  expect(w.find('button').exists()).toBe(false);
  await w.setProps({ state: 'failed', reason: '网络中断', canRetry: true });
  await w.get('button').trigger('click');
  expect(w.emitted('retry')).toHaveLength(1);
  await w.setProps({ busy: true });
  expect(w.get('button').attributes('disabled')).toBeDefined();
  w.unmount();
});
