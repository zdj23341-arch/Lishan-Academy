import { expect, it, vi } from 'vitest';
import { createClient, createSession } from '../src/index';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevAdminHandler } from '../src/dev/content-admin';
import { createAdminContentApi } from '../src/content/admin-api';

it('reads a selected asset through the same admin API used for the library and reports missing assets', async () => {
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    transport: createDevAdminHandler(createDevCatalogStore()),
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  const admin = createAdminContentApi(client, () => ['CONTENT_EDIT']);
  const list = await admin.assets({ page: 1, pageSize: 100 });
  expect(list.items.length).toBeGreaterThan(0);
  expect(await admin.asset(list.items[0]!.id)).toEqual(list.items[0]);
  await expect(admin.asset('missing')).rejects.toMatchObject({ code: 'NOT_FOUND', status: 404 });
});

it('rejects asset detail reads before transport when content permission is absent', async () => {
  const request = vi.fn();
  const admin = createAdminContentApi({ request } as never, () => []);
  await expect(admin.asset('private')).rejects.toMatchObject({ code: 'FORBIDDEN' });
  expect(request).not.toHaveBeenCalled();
});
