import { expect, it } from 'vitest';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createAdminContentApi } from '../src/content/admin-api';
import { createAdminLearningApi } from '../src/learning/admin-api';
import type { ContentBlockInput } from '../src/content/admin-types';

it.each(['PRODUCT', 'ARTICLE', 'PAPER', 'COURSE'] as const)(
  '%s submits image content using the strict backend block contract',
  async (kind) => {
    const blocks: ContentBlockInput[] = [
      { type: 'TEXT', text: '介绍' },
      { type: 'IMAGE', assetId: '33', alt: '' },
    ];
    const product = {
      id: '7',
      version: 3,
      type: 'COURSE' as const,
      title: '课程',
      subtitle: null,
      coverAssetId: null,
      priceFen: 9900,
      originalPriceFen: null,
      status: 'DRAFT' as const,
      sortNo: 0,
      categoryIds: [],
      contentBlocks: [],
    };
    const course = {
      id: '3',
      version: 0,
      productId: '7',
      validityType: 'DAYS' as const,
      validityDays: 365,
    };
    const paper = {
      id: '4',
      version: 1,
      title: '试卷',
      schoolStage: 'SENIOR',
      stage: 'SENIOR',
      grade: '12',
      subject: 'ENGLISH',
      year: 2025,
      status: 'DRAFT' as const,
      coverAssetId: null,
      contentBlocks: [],
      attachments: [],
    };
    let written: unknown;
    const client = createClient({
      baseUrl: '/api/v1',
      session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
      transport: async (req) => {
        let data: unknown = null;
        if (req.method === 'GET') {
          if (req.url === '/api/v1/admin/products/7') data = product;
          else if (req.url === '/api/v1/admin/courses/3') data = course;
          else if (req.url.includes('/chapters'))
            data = { items: [], page: 1, pageSize: 100, total: 0 };
          else throw new Error('Unexpected read: ' + req.url);
        } else {
          const body = req.body as { contentBlocks?: Record<string, unknown>[] };
          if (body.contentBlocks) {
            if (
              body.contentBlocks.some((block) =>
                Object.keys(block).some((key) => !['type', 'text', 'assetId'].includes(key)),
              )
            )
              return {
                status: 400,
                body: {
                  code: 'VALIDATION_ERROR',
                  message: 'Invalid request fields',
                  requestId: 'strict-block',
                  data: null,
                },
              };
            written = body.contentBlocks;
          }
          data = req.url.includes('/papers/')
            ? paper
            : req.url.includes('/courses/')
              ? course
              : product;
        }
        return {
          status: 200,
          body: { code: 'OK', message: 'OK', requestId: 'strict-block', data },
        };
      },
    });
    const content = createAdminContentApi(client, () => ['CONTENT_EDIT']);
    const learning = createAdminLearningApi(client, () => ['CONTENT_EDIT']);
    if (kind === 'PRODUCT') await content.saveProduct('7', { ...product, contentBlocks: blocks });
    else if (kind === 'ARTICLE')
      await content.saveArticle('5', {
        version: 0,
        title: '图文',
        status: 'DRAFT',
        coverAssetId: null,
        contentBlocks: blocks,
      });
    else if (kind === 'PAPER')
      await learning.savePaper('4', {
        ...paper,
        year: '2025',
        summary: '',
        attachmentIds: [],
        visibility: 'HIDDEN',
        contentBlocks: blocks,
      });
    else
      await learning.saveCourse('3', {
        ...course,
        title: '课程',
        productVersion: 3,
        contentBlocks: blocks,
      });
    expect(written).toEqual([
      { type: 'TEXT', text: '介绍' },
      { type: 'IMAGE', assetId: '33' },
    ]);
    expect(blocks[1]).toEqual({ type: 'IMAGE', assetId: '33', alt: '' });
  },
);
