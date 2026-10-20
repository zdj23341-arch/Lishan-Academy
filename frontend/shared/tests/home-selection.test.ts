import { expect, it } from 'vitest';
import { validateHomeDraft } from '../src/content/validation';
import type { HomeDraft } from '../src/content/admin-types';
const draft = (): HomeDraft => ({
  revision: 1,
  banners: [],
  shortcuts: [],
  sections: [
    {
      id: 'courses',
      type: 'COURSE_GRID',
      title: '课程',
      enabled: true,
      sortNo: 0,
      moreTarget: null,
      articleId: null,
      productIds: [],
      selectionMode: 'AUTO',
      displayLimit: 4,
      pinnedIds: [],
    },
  ],
});
it('permits automatic sections without explicit selections while retaining legacy manual validation', () => {
  expect(validateHomeDraft(draft(), true)).toEqual({});
  const manual = draft();
  delete manual.sections[0]!.selectionMode;
  expect(validateHomeDraft(manual, true)['sections.0.productIds']).toBeDefined();
});
it('rejects invalid automatic limits and automatic mode for mixed sections', () => {
  const value = draft();
  value.sections[0]!.displayLimit = 51;
  expect(validateHomeDraft(value, true)['sections.0.displayLimit']).toBeDefined();
  value.sections[0]!.displayLimit = 4;
  value.sections[0]!.type = 'PRODUCT_GRID';
  expect(validateHomeDraft(value, true)['sections.0.selectionMode']).toBeDefined();
});
