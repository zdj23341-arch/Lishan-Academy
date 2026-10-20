import { expect, it } from 'vitest';
import { validatePaper } from '../src/learning/admin-validation';
import type { PaperWrite } from '../src/learning/admin-types';
const valid: PaperWrite = {
  version: 0,
  title: '试卷',
  schoolStage: 'SENIOR',
  grade: '12',
  subject: 'ENGLISH',
  year: '2025',
  summary: '',
  status: 'DRAFT',
  visibility: 'HIDDEN',
  contentBlocks: [],
  attachmentIds: ['30'],
  attachmentMetadata: [{ assetId: '30', title: '英语试卷.pdf', sortNo: 0 }],
};
it.each(['1899', '2201', '2025.5', 'abcd'])('explains the rejected paper year %s', (year) => {
  expect(validatePaper({ ...valid, year }).year).toBe('年份须为1900–2200之间的四位整数或留空');
});
it('names each missing classification rather than repeating a generic error', () => {
  expect(validatePaper({ ...valid, schoolStage: '', grade: '', subject: '' })).toMatchObject({
    schoolStage: '请选择学段',
    grade: '请选择年级',
    subject: '请选择学科',
  });
});
it('explains oversized descriptions, attachments and public samples before submission', () => {
  expect(validatePaper({ ...valid, summary: 'x'.repeat(1001) })).toHaveProperty('summary');
  expect(
    validatePaper({
      ...valid,
      attachmentMetadata: [{ assetId: '30', title: 'x'.repeat(201), sortNo: 0 }],
    }),
  ).toHaveProperty('attachmentIds');
  expect(
    validatePaper({ ...valid, attachmentIds: Array.from({ length: 31 }, (_, i) => String(i + 1)) }),
  ).toHaveProperty('attachmentIds');
  expect(
    validatePaper({ ...valid, contentBlocks: [{ type: 'TEXT', text: 'x'.repeat(10001) }] }),
  ).toHaveProperty('contentBlocks');
  expect(
    validatePaper({
      ...valid,
      contentBlocks: Array.from({ length: 101 }, () => ({ type: 'TEXT', text: '样张' })),
    }),
  ).toHaveProperty('contentBlocks');
});
it.each([null, '1900', '2025', '2200'])(
  'keeps valid paper boundaries and optional cover (%s)',
  (year) => {
    expect(validatePaper({ ...valid, year, coverAssetId: null })).toEqual({});
  },
);
