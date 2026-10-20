// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RegionPicker from '../src/components/RegionPicker.vue';
import { regionChildren } from '../src/utils/regions';

describe('combined address region picker', () => {
  it('confirms all three codes together after a province change and does not save draft changes', async () => {
    const w = mount(RegionPicker, { props: { modelValue: ['110000', '110100', '110108'] } });
    expect(w.findAll('picker')).toHaveLength(1);
    const picker = w.get('picker');
    // Province index 8 is Shanghai in the authoritative area table.
    await picker.trigger('columnchange', { detail: { column: 0, value: 8 } });
    expect(w.emitted('update:modelValue')).toBeUndefined();
    await picker.trigger('change', { detail: { value: [8, 0, 0] } });
    expect(w.emitted('update:modelValue')?.[0]).toEqual([['310000', '310100', '310101']]);
    w.unmount();
  });
  it('cancels draft edits and retains the current district when reopened', async () => {
    const w = mount(RegionPicker, { props: { modelValue: ['110000', '110100', '110108'] } });
    const picker = w.get('picker');
    await picker.trigger('columnchange', { detail: { column: 0, value: 8 } });
    await picker.trigger('cancel');
    expect(w.emitted('update:modelValue')).toBeUndefined();
    const district = regionChildren('district', '110100').findIndex(
      (item) => item.code === '110108',
    );
    await picker.trigger('change', { detail: { value: [0, 0, district] } });
    expect(w.emitted('update:modelValue')?.[0]).toEqual([['110000', '110100', '110108']]);
    w.unmount();
  });
  it('keeps empty and disabled addresses unselected', async () => {
    const w = mount(RegionPicker, { props: { modelValue: ['', '', ''], disabled: true } });
    expect(w.text()).toContain('请选择省 / 市 / 区');
    await w.get('picker').trigger('change', { detail: { value: [0, 0, 0] } });
    expect(w.emitted('update:modelValue')).toBeUndefined();
    w.unmount();
  });
});
