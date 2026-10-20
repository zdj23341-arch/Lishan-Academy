// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Recommendation from '../src/components/HomeRecommendation.vue';
import { ApiError } from '@jxsc/shared';
const api = vi.hoisted(() => ({
  homeDraft: vi.fn(),
  recommendHome: vi.fn(),
  publishHome: vi.fn(),
}));
vi.mock('../src/api/content', () => ({ contentApi: api }));
afterEach(() => vi.resetAllMocks());
it('writes only the chosen draft section and preserves selection on revision conflict', async () => {
  const draft = {
    revision: 5,
    sections: [
      {
        id: 'course-section',
        title: '最新课程',
        type: 'COURSE_GRID',
        selectionMode: 'AUTO',
        enabled: true,
      },
      { id: 'books', type: 'BOOK_GRID', enabled: true },
    ],
    banners: [],
    shortcuts: [],
  };
  api.homeDraft.mockResolvedValue(draft);
  api.recommendHome.mockRejectedValueOnce(new ApiError('VERSION_CONFLICT', '版本已变化'));
  const w = mount(Recommendation, {
    props: { contentId: '101', kind: 'COURSE' },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue'],
          template: '<div v-if="modelValue"><slot/><slot name="footer"/></div>',
        },
      },
    },
  });
  try {
    await w.get('button').trigger('click');
    await flushPromises();
    expect(w.findAll('option')).toHaveLength(1);
    const save = () => w.findAll('button').find((b) => b.text() === '保存推荐')!;
    await save().trigger('click');
    await flushPromises();
    expect(w.get('[role=alert]').text()).toContain('版本已变化');
    expect((w.get('select').element as HTMLSelectElement).value).toBe('course-section');
    expect(api.recommendHome).toHaveBeenCalledWith('course-section', 5, '101');
    api.recommendHome.mockResolvedValue({ ...draft, revision: 6 });
    await save().trigger('click');
    await flushPromises();
    expect(w.text()).toContain('发布首页后生效');
    expect(api.publishHome).not.toHaveBeenCalled();
  } finally {
    w.unmount();
  }
});
