// @vitest-environment jsdom
import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import PriceText from '../src/components/PriceText.vue';

it('renders zero as a numeric currency amount when explicitly used for a monetary ledger', () => {
  const wrapper = mount(PriceText, { props: { fen: 0, mode: 'amount' } });
  expect(wrapper.text()).toBe('¥0.00');
  wrapper.unmount();
});

it('preserves the free catalog price by default and normal nonzero currency formatting', async () => {
  const wrapper = mount(PriceText, { props: { fen: 0 } });
  expect(wrapper.text()).toBe('免费');
  await wrapper.setProps({ fen: 105 });
  expect(wrapper.text()).toBe('¥1.05');
  wrapper.unmount();
});
