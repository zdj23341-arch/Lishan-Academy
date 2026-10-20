// @vitest-environment jsdom
import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import UiButton from '../src/components/ui/UiButton.vue';
import BookCover from '../src/components/ui/BookCover.vue';

it('blocks clicks while disabled or loading and resumes when ready', async () => {
  const wrapper = mount(UiButton, { props: { loading: true }, slots: { default: '保存' } });
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('click')).toBeUndefined();
  await wrapper.setProps({ loading: false, disabled: true });
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('click')).toBeUndefined();
  await wrapper.setProps({ disabled: false });
  await wrapper.get('button').trigger('click');
  expect(wrapper.emitted('click')).toHaveLength(1);
  wrapper.unmount();
});

it('prefers real artwork, falls back after failure and retries a replacement image', async () => {
  const wrapper = mount(BookCover, { props: { title: '阅读与表达', imageUrl: '/real.png' } });
  expect(wrapper.get('img').attributes('src')).toBe('/real.png');
  await wrapper.get('img').trigger('error');
  expect(wrapper.find('img').exists()).toBe(false);
  expect(wrapper.text()).toContain('阅读与表达');
  await wrapper.setProps({ imageUrl: '/replacement.png' });
  expect(wrapper.get('img').attributes('src')).toBe('/replacement.png');
  wrapper.unmount();
});
