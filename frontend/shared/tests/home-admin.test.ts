import { describe, expect, it } from 'vitest';
import { createClient, createSession } from '../src/index';
import { createCatalogApi } from '../src/content/api';
import { createAdminContentApi } from '../src/content/admin-api';
import { createDevCatalogStore, createDevTransport } from '../src/dev/transport';
import { createDevAdminHandler } from '../src/dev/content-admin';

function setup() {
  const store = createDevCatalogStore();
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const client = createClient({
    baseUrl: 'https://api.example/api/v1',
    session,
    transport: createDevTransport(store, createDevAdminHandler(store)),
  });
  return {
    admin: createAdminContentApi(client, () => ['CONTENT_EDITOR']),
    catalog: createCatalogApi(client),
  };
}

describe('home draft and publication through frontend APIs', () => {
  it('keeps saved draft and preview separate from public content until publication', async () => {
    const { admin, catalog } = setup();
    const initial = await catalog.home();
    const draft = await admin.homeDraft();
    draft.banners[0]!.title = '新轮播标题';
    const saved = await admin.saveHome(draft);
    expect(saved.revision).toBe(draft.revision + 1);
    expect((await admin.previewHome()).banners[0]!.title).toBe('新轮播标题');
    expect(await catalog.home()).toEqual(initial);
    const published = await admin.publishHome(saved.revision, '更新活动');
    expect((await catalog.home()).banners[0]!.title).toBe('新轮播标题');
    expect(await catalog.home()).toEqual(published);
  });
  it('allows incomplete drafts but rejects incomplete publication without changing the public snapshot', async () => {
    const { admin, catalog } = setup();
    const initial = await catalog.home();
    const draft = await admin.homeDraft();
    draft.banners = [];
    const saved = await admin.saveHome(draft);
    await expect(admin.publishHome(saved.revision, '未完成')).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
    });
    expect(await catalog.home()).toEqual(initial);
  });
  it('rejects stale saves and stale publications without mutating the losing editor', async () => {
    const { admin } = setup();
    const first = await admin.homeDraft();
    const stale = await admin.homeDraft();
    first.banners[0]!.title = '另一位编辑';
    await admin.saveHome(first);
    stale.banners[0]!.title = '保留本地编辑';
    await expect(admin.saveHome(stale)).rejects.toMatchObject({
      status: 409,
      code: 'VERSION_CONFLICT',
    });
    await expect(admin.publishHome(stale.revision, '旧版本')).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
    });
    expect(stale.banners[0]!.title).toBe('保留本地编辑');
    expect((await admin.homeDraft()).banners[0]!.title).toBe('另一位编辑');
  });
  it('validates referenced resources and publication reason', async () => {
    const { admin } = setup();
    const draft = await admin.homeDraft();
    await expect(admin.publishHome(draft.revision, ' ')).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
    });
    draft.banners[0]!.assetId = 'missing';
    const saved = await admin.saveHome(draft);
    await expect(admin.publishHome(saved.revision, '图片')).rejects.toMatchObject({
      code: 'FILE_NOT_READY',
    });
    saved.banners[0]!.assetId = 'DEV-public-image';
    saved.banners[0]!.target = { type: 'PRODUCT', id: 'missing', filters: {} };
    const next = await admin.saveHome(saved);
    await expect(admin.publishHome(next.revision, '商品')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });
  it('honors enabled state and explicit sort order when rendering', async () => {
    const { admin } = setup();
    const draft = await admin.homeDraft();
    draft.banners[0]!.sortNo = 10;
    draft.sections[0]!.enabled = false;
    const saved = await admin.saveHome(draft);
    const result = await admin.publishHome(saved.revision, '排序');
    expect(result.banners.map((item) => item.id)).toEqual([
      'DEV-banner-1',
      'DEV-banner-2',
      'DEV-banner-0',
    ]);
    expect(result.sections.map((item) => item.id)).toEqual(['DEV-courses', 'DEV-papers']);
  });
});
