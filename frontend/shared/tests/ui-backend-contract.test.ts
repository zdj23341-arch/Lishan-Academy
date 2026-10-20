import { expect, it, vi } from 'vitest';
import { createAdminLearningApi } from '../src/learning/admin-api';
import { createAdminContentApi } from '../src/content/admin-api';
import { createLearningApi } from '../src/learning/api';
import type { ApiClient } from '../src/http';

it('preserves article author/category updates and sends no read-only timestamps', async () => {
  const request = vi.fn().mockResolvedValue({});
  const api = createAdminContentApi({ request } as unknown as ApiClient, () => ['CONTENT_EDIT']);
  const record = {
    version: 2,
    title: '真实文章',
    coverAssetId: null,
    contentBlocks: [{ type: 'TEXT' as const, text: '正文' }],
    status: 'DRAFT' as const,
    authorName: '真实作者',
    categoryName: '',
    updatedAt: 'read-only',
    publishedAt: 'read-only',
  };
  await api.saveArticle('4', record);
  expect(request).toHaveBeenCalledWith('/admin/articles/4', {
    method: 'PATCH',
    body: {
      version: 2,
      title: '真实文章',
      coverAssetId: null,
      contentBlocks: [{ type: 'TEXT', text: '正文' }],
      status: 'DRAFT',
      authorName: '真实作者',
      categoryName: '',
    },
  });
});

it('sends supported membership user/status filters with pagination while omitting event-only fields', async () => {
  const request = vi.fn().mockResolvedValue({ items: [], total: 0 });
  const api = createAdminLearningApi({ request } as unknown as ApiClient, () => ['ORDER_OPERATE']);
  await api.membershipRecords({
    userId: '5',
    status: 'EXPIRED',
    orderId: 'event-not-entitlement',
    kind: 'REFUND',
    page: 2,
    pageSize: 20,
  });
  expect(request).toHaveBeenCalledWith('/admin/membership/records', {
    query: { page: 2, pageSize: 20, userId: '5', status: 'EXPIRED' },
  });
});
it('retains optional server resume fields through the customer course adapter', async () => {
  const resume = {
    chapterId: 'ch',
    chapterTitle: '最近一节',
    positionSeconds: 15,
    lastLearnedAt: '2026-10-09T01:00:00Z',
  };
  const request = vi.fn().mockResolvedValue({
    items: [
      {
        courseId: 'c',
        productId: 'p',
        title: '课程',
        status: 'ACTIVE',
        expiresAt: null,
        completedChapters: 1,
        totalChapters: 4,
        resume,
      },
    ],
    page: 1,
    pageSize: 20,
    total: 1,
  });
  const api = createLearningApi({ request } as unknown as ApiClient);
  expect((await api.courses()).items[0]?.resume).toEqual(resume);
});
