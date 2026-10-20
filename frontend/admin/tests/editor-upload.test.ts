// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { ref } from 'vue';
import ProductEditor from '../src/views/content/ProductEditor.vue';
import ContentBlockEditor from '../src/components/ContentBlockEditor.vue';
import AssetPicker from '../src/components/AssetPicker.vue';
import AssetUpload from '../src/components/AssetUpload.vue';
import { ApiError } from '../../shared/src/http';

const network = vi.hoisted(() => ({
  product: vi.fn(),
  categories: vi.fn(),
  assets: vi.fn(),
  asset: vi.fn(),
  saveProduct: vi.fn(),
  presign: vi.fn(),
  transfer: vi.fn(),
  complete: vi.fn(),
}));
vi.mock('../src/api/content', () => ({ contentApi: network, uploadServices: network }));
const asset = {
  id: 'new-image',
  version: 1,
  name: 'new.png',
  extension: 'PNG',
  mime: 'image/png',
  bytes: 4,
  purpose: 'PUBLIC_IMAGE',
  status: 'READY',
  scanStatus: 'DEV_FIXTURE_ONLY',
  publicUrl: '/new.png',
};
const product = {
  id: 'book1',
  version: 1,
  type: 'BOOK',
  title: '练习册',
  subtitle: null,
  priceFen: 1000,
  originalPriceFen: null,
  coverAssetId: 'old-image',
  sortNo: 0,
  categoryIds: ['c1'],
  contentBlocks: [{ type: 'TEXT', text: '说明' }],
  book: { quantityLimit: 1, shippingSummary: '快递' },
  status: 'DRAFT',
  typeEditable: false,
};
const wrappers: VueWrapper[] = [];
it('retains a failed upload for retry while editing its image caption through a reactive parent', async () => {
  network.transfer.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined);
  const wrapper = mount({
    components: { ContentBlockEditor },
    setup() {
      return { blocks: ref([{ type: 'IMAGE', assetId: 'a', alt: '' }]) };
    },
    template: '<ContentBlockEditor v-model="blocks" />',
  });
  wrappers.push(wrapper);
  await flushPromises();
  await openImages(wrapper);
  const upload = fileInputs(wrapper)[0]!;
  const input = upload.element;
  Object.defineProperty(input, 'files', {
    value: [new File(['data'], 'new.png', { type: 'image/png' })],
    configurable: true,
  });
  await upload.trigger('change');
  await flushPromises();
  expect(new DOMWrapper(document.body).text()).toContain('offline');
  await wrapper
    .findAll('label')
    .find((label) => label.text() === '图片说明')!
    .get('input')
    .setValue('新的说明');
  expect(fileInputs(wrapper)[0]!.element).toBe(input);
  await button(wrapper, '重试上传').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('已选择：new-image');
});
it('rereads a reused product route and ignores the previous pending product response', async () => {
  let finish!: (value: unknown) => void;
  network.product.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { wrapper, router } = await editor('/content/products/book1');
  network.product.mockResolvedValue({ ...product, id: 'course2', type: 'COURSE', title: '新课程' });
  await router.push('/content/products/course2');
  await flushPromises();
  expect(network.product).toHaveBeenLastCalledWith('course2');
  finish(product);
  await flushPromises();
  const title = wrapper
    .findAll('label')
    .find((l) => l.text().trim() === '标题')!
    .get('input');
  expect((title.element as HTMLInputElement).value).toBe('新课程');
  expect(wrapper.text()).not.toContain('配送说明');
});
beforeEach(() => {
  vi.resetAllMocks();
  network.product.mockResolvedValue(structuredClone(product));
  network.categories.mockResolvedValue({
    items: [
      {
        id: 'c1',
        version: 1,
        name: '语文',
        type: 'BOOK',
        enabled: true,
        parentId: null,
        sortNo: 0,
      },
    ],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  network.asset.mockResolvedValue(asset);
  network.assets.mockResolvedValue({ items: [asset], page: 1, pageSize: 10, total: 1 });
  network.presign.mockResolvedValue({
    id: asset.id,
    uploadUrl: 'https://example.invalid/upload',
    uploadToken: 'ticket',
    expiresAt: '2099-01-01T00:00:00Z',
  });
  network.complete.mockResolvedValue(asset);
  network.saveProduct.mockImplementation(async (_id, body) => ({
    ...body,
    id: 'book1',
    version: 2,
    status: 'DRAFT',
  }));
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
});
async function editor(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/content/products/:id', component: ProductEditor },
      { path: '/content/products', component: { template: '<p>商品列表</p>' } },
      { path: '/learning/courses', component: { template: '<p>课程管理</p>' } },
    ],
  });
  await router.push(path);
  await router.isReady();
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] } });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}
function button(wrapper: VueWrapper, text: string) {
  return (
    wrapper.findAll('button').find((item) => item.text() === text) ||
    new DOMWrapper(document.body).findAll('button').find((item) => item.text() === text)!
  );
}

it('holds product saving and reload while upload is pending, then saves the completed image', async () => {
  let finish!: () => void;
  network.transfer.mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  const { wrapper } = await editor('/content/products/book1');
  await openImages(wrapper);
  const input = fileInputs(wrapper)[0]!;
  Object.defineProperty(input.element, 'files', {
    value: [new File(['data'], 'new.png', { type: 'image/png' })],
    configurable: true,
  });
  await input.trigger('change');
  await flushPromises();
  expect(wrapper.get('form > fieldset').attributes('disabled')).toBeDefined();
  await wrapper.get('form').trigger('submit');
  expect(network.saveProduct).not.toHaveBeenCalled();
  expect(button(wrapper, '重新读取商品（丢弃本地修改）').attributes('disabled')).toBeDefined();
  expect(button(wrapper, '取消').attributes('disabled')).toBeDefined();
  expect(button(wrapper, '完成').attributes('disabled')).toBeDefined();
  finish();
  await flushPromises();
  expect(wrapper.text()).toContain('已选择：new-image');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(network.saveProduct).toHaveBeenCalledWith(
    'book1',
    expect.objectContaining({ coverAssetId: 'new-image' }),
  );
  expect(wrapper.text()).toContain('商品已保存');
});

it('keeps the same upload component attached to its image when blocks are reordered', async () => {
  const a = { type: 'IMAGE' as const, assetId: 'a' },
    b = { type: 'IMAGE' as const, assetId: 'b' };
  const wrapper = mount(ContentBlockEditor, { props: { modelValue: [a, b] } });
  wrappers.push(wrapper);
  await flushPromises();
  await openImages(wrapper);
  const original = fileInputs(wrapper).map((input) => input.element);
  await wrapper.setProps({ modelValue: [b, a] });
  expect(fileInputs(wrapper)[0]!.element).toBe(original[1]);
  expect(fileInputs(wrapper)[1]!.element).toBe(original[0]);
});

it('replaces the new-product route with the saved identifier', async () => {
  const { wrapper, router } = await editor('/content/products/new');
  await wrapper.get('input[maxlength="100"]').setValue('新图书');
  await wrapper.get('input[inputmode="decimal"]').setValue('10.00');
  await openImages(wrapper);
  await button(wrapper, '选择 new.png').trigger('click');
  await wrapper.get('input[type="checkbox"]').setValue(true);
  await wrapper.get('textarea[required]').setValue('快递');
  await wrapper.findAll('textarea')[1]!.setValue('内容说明');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(router.currentRoute.value.path).toBe('/content/products/book1');
  expect(wrapper.text()).toContain('商品已保存');
});

it('preserves the edited product after a version conflict', async () => {
  const { wrapper } = await editor('/content/products/book1');
  await wrapper.get('input[maxlength="100"]').setValue('保留我的修改');
  network.saveProduct.mockRejectedValueOnce(
    new ApiError('VERSION_CONFLICT', '版本已变化，请刷新', null, {}, 409),
  );
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect((wrapper.get('input[maxlength="100"]').element as HTMLInputElement).value).toBe(
    '保留我的修改',
  );
  expect(wrapper.get('[role="alert"]').text()).toContain('版本已变化');
  expect(wrapper.text()).toContain('有未保存修改');
});

it('uses opt-in content image upload services through the real picker and uploader', async () => {
  const wrapper = mount(ContentBlockEditor, {
    props: {
      modelValue: [{ type: 'IMAGE', assetId: '', alt: '' }],
      services: {
        presign: async () => {
          throw new ApiError('FORBIDDEN', '页面上传已取消');
        },
        transfer: network.transfer,
        complete: network.complete,
      },
    },
  });
  wrappers.push(wrapper);
  await flushPromises();
  await openImages(wrapper);
  const input = fileInputs(wrapper)[0]!;
  Object.defineProperty(input.element, 'files', {
    value: [new File(['data'], 'public.png', { type: 'image/png' })],
    configurable: true,
  });
  await input.trigger('change');
  await flushPromises();
  expect(new DOMWrapper(document.body).text()).toContain('页面上传已取消');
  expect(network.presign).not.toHaveBeenCalled();
  expect(network.transfer).not.toHaveBeenCalled();
  expect(wrapper.emitted('update:modelValue')).toBeUndefined();
});

async function openImages(wrapper: VueWrapper) {
  for (const picker of wrapper.findAllComponents(AssetPicker)) {
    await picker
      .findAll('button')
      .find((b) => ['更换图片', '选择图片'].includes(b.text()))!
      .trigger('click');
    await flushPromises();
  }
}
function fileInputs(wrapper: VueWrapper) {
  return wrapper.findAllComponents(AssetUpload).map((w) => w.get('input[type="file"]'));
}
it('offers an enabled continue action for a processing file and binds only after READY', async () => {
  network.complete.mockRejectedValueOnce(
    new ApiError('FILE_PROCESSING', '正在检查', null, {}, 409),
  );
  const wrapper = mount(AssetUpload, {
    props: {
      purpose: 'PUBLIC_IMAGE',
      services: {
        presign: network.presign,
        transfer: network.transfer,
        complete: network.complete,
        read: network.asset,
      },
    },
  });
  wrappers.push(wrapper);
  const input = wrapper.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', {
    value: [new File(['data'], 'new.png', { type: 'image/png' })],
    configurable: true,
  });
  await input.trigger('change');
  await flushPromises();
  expect(wrapper.text()).toContain('文件处理中');
  expect(wrapper.emitted('ready')).toBeUndefined();
  const resume = button(wrapper, '继续处理');
  expect(resume.attributes('disabled')).toBeUndefined();
  await resume.trigger('click');
  await flushPromises();
  expect(wrapper.emitted('ready')?.[0]?.[0]).toMatchObject({ id: 'new-image', status: 'READY' });
  expect(network.transfer).toHaveBeenCalledTimes(1);
});
