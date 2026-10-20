import { expect, it } from 'vitest';
import { createDevCatalogStore, createDevTransport } from '../src/dev/transport';
import { createClient, createSession } from '../src/index';
import { createCatalogApi } from '../src/content/api';

it('filters catalog lists by sale status and actual category while retaining purchased off-sale detail', async () => {
  const store = createDevCatalogStore();
  const api = createCatalogApi(
    createClient({
      baseUrl: 'https://api.example/api/v1',
      transport: createDevTransport(store),
      session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
    }),
  );
  expect((await api.products()).items.some((item) => item.id === 'DEV-course-off')).toBe(false);
  expect((await api.product('DEV-course-off')).allowedActions).toContain('VIEW_CONTENT');
  expect(
    (await api.products({ categoryId: 'DEV-chinese' })).items.every((item) => item.type === 'BOOK'),
  ).toBe(true);
});
