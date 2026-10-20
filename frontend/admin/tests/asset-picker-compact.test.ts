// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils';
import AssetPicker from '../src/components/AssetPicker.vue';

const api = vi.hoisted(() => ({ assets: vi.fn(), asset: vi.fn() }));
vi.mock('../src/api/content', () => ({ contentApi: api, uploadServices: {} }));
const wrappers: VueWrapper[] = [];
const image = (id: string) => ({
  id,
  name: `${id}.png`,
  purpose: 'PUBLIC_IMAGE',
  status: 'READY',
  publicUrl: `/${id}.png`,
});
function render(value: string | null = null) {
  const w = mount(AssetPicker, {
    attachTo: document.body,
    props: { modelValue: value },
    global: { stubs: { AssetUpload: true } },
  });
  wrappers.push(w);
  return w;
}
function button(_w: VueWrapper, label: string) {
  return new DOMWrapper(document.body).findAll('button').find((b) => b.text() === label)!;
}
beforeEach(() => {
  vi.resetAllMocks();
  api.asset.mockImplementation(async (id: string) => image(id));
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  document.body.innerHTML = '';
});

it('shows the current image without fetching a library until requested', async () => {
  const w = render('current');
  await flushPromises();
  expect(api.assets).not.toHaveBeenCalled();
  expect(w.get('img').attributes('src')).toBe('/current.png');
  expect(w.find('[role="dialog"]').exists()).toBe(false);
});

it('collects all source pages before paging only selectable images and selects through the dialog', async () => {
  api.assets.mockImplementation(async ({ page }) => ({
    page,
    pageSize: 12,
    total: 24,
    items:
      page === 1
        ? [
            { ...image('private'), purpose: 'PAPER_ATTACHMENT' },
            { ...image('disabled'), status: 'DISABLED' },
            ...Array.from({ length: 10 }, (_, n) => image(`first${n}`)),
          ]
        : Array.from({ length: 12 }, (_, n) => image(`last${n}`)),
  }));
  const w = render();
  await button(w, '选择图片').trigger('click');
  await flushPromises();
  expect(document.body.textContent).toContain('共 22 张可用图片');
  expect(document.body.textContent).not.toContain('private.png');
  expect(document.body.textContent).not.toContain('disabled.png');
  await button(w, '下一页图片').trigger('click');
  await button(w, '选择 last0.png').trigger('click');
  expect(w.emitted('update:modelValue')).toEqual([['last0']]);
});

it('discards a cancelled read and retries after a library failure', async () => {
  let finish!: (v: unknown) => void;
  api.assets.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const w = render();
  await button(w, '选择图片').trigger('click');
  await flushPromises();
  await button(w, '取消').trigger('click');
  api.assets.mockRejectedValueOnce(new Error('图库暂不可用'));
  await button(w, '选择图片').trigger('click');
  await flushPromises();
  finish({ page: 1, pageSize: 10, total: 1, items: [image('stale')] });
  await flushPromises();
  expect(document.body.textContent).toContain('图库暂不可用');
  expect(document.body.textContent).not.toContain('stale.png');
  api.assets.mockResolvedValue({ page: 1, pageSize: 10, total: 1, items: [image('retry')] });
  await button(w, '刷新图片').trigger('click');
  await flushPromises();
  expect(document.body.textContent).toContain('retry.png');
});

it('ignores metadata for a previous selected image and cancellation keeps the parent selection', async () => {
  let finish!: (value: unknown) => void;
  api.asset.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const w = render('old');
  await w.setProps({ modelValue: 'new' });
  await flushPromises();
  finish(image('old'));
  await flushPromises();
  expect(w.get('img').attributes('src')).toBe('/new.png');
  api.assets.mockResolvedValue({ items: [image('candidate')], page: 1, pageSize: 10, total: 1 });
  await button(w, '更换图片').trigger('click');
  await flushPromises();
  await button(w, '取消').trigger('click');
  expect(w.emitted('update:modelValue')).toBeUndefined();
  expect(w.get('img').attributes('src')).toBe('/new.png');
});
