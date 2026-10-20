import { expect, it } from 'vitest';
import { normalizeContentBlocks, validateHomePublish } from '../src/content/validation';
import type { HomeDraft } from '../src/content/admin-types';
it('normalizes writable blocks without trusting public URLs or unknown block types', () => {
  expect(
    normalizeContentBlocks([
      { type: 'IMAGE', assetId: 'image', url: 'https://evil.test', alt: ' Caption ' },
      { type: 'TEXT', text: ' Text ', html: '<script>' },
    ]),
  ).toEqual([
    { type: 'IMAGE', assetId: 'image', alt: 'Caption' },
    { type: 'TEXT', text: 'Text' },
  ]);
  expect(() => normalizeContentBlocks([{ type: 'HTML', html: 'bad' }])).toThrow();
  expect(() => normalizeContentBlocks([{ type: 'IMAGE', url: 'https://evil.test' }])).toThrow();
});
it('ignores incomplete disabled home content while retaining identity checks', () => {
  const draft: HomeDraft = {
    revision: 1,
    banners: [
      {
        id: 'disabled',
        title: '',
        assetId: '',
        sortNo: 0,
        enabled: false,
        target: { type: 'PRODUCT', id: null, filters: {} },
      },
    ],
    shortcuts: [],
    sections: [
      {
        id: 'section',
        title: '',
        type: 'ARTICLE_CARD',
        articleId: null,
        productIds: [],
        moreTarget: null,
        enabled: false,
        sortNo: 0,
      },
    ],
  };
  const errors = validateHomePublish(draft);
  expect(errors).toEqual({ banners: expect.any(String), shortcuts: expect.any(String) });
  draft.sections[0]!.id = '';
  expect(validateHomePublish(draft)['sections.0.id']).toBeTruthy();
});
