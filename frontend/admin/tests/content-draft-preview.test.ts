// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ContentDraftPreview from '../src/components/ContentDraftPreview.vue';
const api = vi.hoisted(() => ({ asset: vi.fn() }));
vi.mock('../src/api/content', () => ({ contentApi: api }));
afterEach(() => vi.resetAllMocks());
it('shows an actual READY public cover when the editor omits the optional cover switch', async () => {
  api.asset.mockResolvedValue({
    id: 'image1',
    purpose: 'PUBLIC_IMAGE',
    status: 'READY',
    publicUrl: '/ready.png',
  });
  const w = mount(ContentDraftPreview, {
    props: { title: '真实封面预览', assetId: 'image1', blocks: [] },
  });
  try {
    await flushPromises();
    expect(w.get('img').attributes('src')).toBe('/ready.png');
    expect(w.get('img').attributes('alt')).toBe('真实封面预览');
  } finally {
    w.unmount();
  }
});
it('keeps the course preview text-only when the editor explicitly hides its cover', async () => {
  const w = mount(ContentDraftPreview, {
    props: {
      title: '课程预览',
      showCover: false,
      blocks: [{ type: 'TEXT', text: '课程真实说明' }],
    },
  });
  try {
    expect(w.find('img').exists()).toBe(false);
    expect(w.find('.draft-preview-placeholder').exists()).toBe(false);
    expect(w.text()).toContain('课程真实说明');
  } finally {
    w.unmount();
  }
});
