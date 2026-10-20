import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevTransport } from '../src/dev/transport';
import { createDevLearningHandler } from '../src/dev/learning';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createLearningApi } from '../src/learning/api';

function setup() {
  const catalog = createDevCatalogStore();
  let now = Date.parse('2026-10-07T00:00:00Z');
  const learning = createDevLearningHandler(catalog, {
    now: () => now,
    mediaUrl: 'http://127.0.0.1:5173/dev-media/course-sample.mp4',
  });
  function api(token?: string) {
    const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
    if (token)
      session.set({
        accessToken: token,
        expiresAt: '2099-01-01T00:00:00Z',
        user: { id: token, nickname: 'DEV', distributor: false },
      });
    return createLearningApi(
      createClient({
        baseUrl: 'https://dev.local/api/v1',
        transport: createDevTransport(catalog, learning.transport, { content: learning.content }),
        session,
      }),
    );
  }
  return {
    catalog,
    learning,
    api,
    advance: (ms: number) => {
      now += ms;
    },
  };
}

it('public course trial is independent from membership and never grants paid chapter access', async () => {
  const { api } = setup();
  const course = await api().course('DEV-course', 'DEV-course');
  expect(course).toMatchObject({
    entitlement: 'NONE',
    chapters: [
      { id: 'DEV-course-trial', isTrial: true, canPlay: true },
      { id: 'DEV-course-paid', isTrial: false, canPlay: false },
    ],
  });
  expect(await api().playUrl('DEV-course', 'DEV-course-trial')).toMatchObject({
    url: expect.stringContaining('/dev-media/course-sample.mp4'),
    resumeSeconds: 0,
  });
  await expect(
    api('DEV-member-only').playUrl('DEV-course', 'DEV-course-paid'),
  ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  await expect(api().courses()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
});

it('paid access is actor-scoped, survives product unpublish, and expiry denies new authorization', async () => {
  const { api, learning, catalog, advance } = setup();
  learning.grantCourse('DEV-buyer', 'DEV-course', '2026-10-07T00:01:00Z');
  const buyer = api('DEV-buyer');
  expect((await buyer.course('DEV-course', 'DEV-course')).entitlement).toBe('ACTIVE');
  expect((await api('DEV-other').course('DEV-course', 'DEV-course')).entitlement).toBe('NONE');
  const product = catalog.products.find((p) => p.id === 'DEV-course')!;
  product.status = 'OFF_SALE';
  expect(await buyer.playUrl('DEV-course', 'DEV-course-paid')).toHaveProperty('url');
  expect((await buyer.course('DEV-course', 'DEV-course')).allowedActions).not.toContain('BUY');
  advance(60_000);
  await expect(buyer.playUrl('DEV-course', 'DEV-course-paid')).rejects.toMatchObject({
    code: 'ENTITLEMENT_EXPIRED',
  });
  expect(await buyer.courses({ status: 'EXPIRED' })).toMatchObject({
    total: 1,
    items: [{ id: 'DEV-course', entitlement: 'EXPIRED' }],
  });
});

it('records bounded chapter progress, isolates actors and returns current resume/completion on reread', async () => {
  const { api, learning } = setup();
  learning.grantCourse('DEV-buyer', 'DEV-course', null);
  const buyer = api('DEV-buyer');
  await expect(
    buyer.progress('DEV-course', 'DEV-course-paid', { positionSeconds: 999, completed: true }),
  ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
  await buyer.progress('DEV-course', 'DEV-course-paid', { positionSeconds: 45, completed: false });
  expect(await buyer.playUrl('DEV-course', 'DEV-course-paid')).toMatchObject({ resumeSeconds: 45 });
  expect(
    (await api('DEV-other').course('DEV-course', 'DEV-course')).chapters[1]?.lastPositionSeconds,
  ).toBe(0);
  for (const chapterId of ['DEV-course-trial', 'DEV-course-paid']) {
    await buyer.progress('DEV-course', chapterId, { positionSeconds: 60, completed: true });
    await buyer.progress('DEV-course', chapterId, { positionSeconds: 60, completed: true });
  }
  expect(await buyer.courses({ status: 'COMPLETED', page: 1, pageSize: 1 })).toMatchObject({
    total: 1,
    items: [{ completedChapters: 2, totalChapters: 2, status: 'ACTIVE' }],
  });
  expect(await buyer.courses({ status: 'IN_PROGRESS' })).toMatchObject({ total: 1 });
});

it('revocation denies access immediately and unconfigured media does not pretend playback is ready', async () => {
  const { api, learning } = setup();
  learning.grantCourse('DEV-buyer', 'DEV-course', null);
  learning.revokeCourse('DEV-buyer', 'DEV-course');
  await expect(api('DEV-buyer').playUrl('DEV-course', 'DEV-course-paid')).rejects.toMatchObject({
    code: 'ENTITLEMENT_EXPIRED',
  });
  const noMedia = createDevLearningHandler(createDevCatalogStore());
  const result = await noMedia.transport({
    url: 'https://dev.local/api/v1/courses/DEV-course/chapters/DEV-course-trial/play-url',
    method: 'GET',
    headers: {},
  });
  expect(result).toMatchObject({ status: 409, body: { code: 'FILE_NOT_READY' } });
});
