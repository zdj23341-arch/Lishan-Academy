import { describe, expect, it, vi } from 'vitest';
import { createClient, createSession } from '../src/index';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevAdminHandler } from '../src/dev/content-admin';
import { createAdminContentApi } from '../src/content/admin-api';
function setup() {
  const store = createDevCatalogStore();
  const transport = createDevAdminHandler(store);
  const client = createClient({
    baseUrl: 'https://dev.local/api/v1',
    transport,
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
  });
  return { store, transport, admin: createAdminContentApi(client, () => ['CONTENT_EDITOR']) };
}
describe('admin fixture lifecycle', () => {
  it('rejects category cycles, mismatched categories and type edits without mutating data', async () => {
    const { admin } = setup();
    const category = await admin.saveCategory(null, {
      version: 0,
      type: 'BOOK',
      name: 'parent',
      parentId: null,
      sortNo: 0,
      enabled: true,
    });
    await expect(
      admin.saveCategory(category.id, { ...category, parentId: category.id }),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    const book = await admin.product('DEV-book');
    await expect(admin.saveProduct(book.id, { ...book, type: 'COURSE' })).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
    });
    await expect(
      admin.saveProduct(book.id, { ...book, categoryIds: ['DEV-category-COURSE'] }),
    ).rejects.toMatchObject({ code: 'VALIDATION_ERROR' });
    expect(await admin.product(book.id)).toEqual(book);
  });
  it('rejects wrong upload size, credentials and expired tickets', async () => {
    const { admin, transport } = setup();
    const ticket = await admin.presign({
      name: 'a.png',
      mime: 'image/png',
      bytes: 3,
      purpose: 'PUBLIC_IMAGE',
    });
    const request = {
      url: ticket.uploadUrl,
      method: 'PUT' as const,
      headers: { 'X-Upload-Token': ticket.uploadToken },
      body: new Uint8Array([1]),
    };
    expect((await transport({ ...request, headers: {} })).status).toBe(403);
    expect((await transport(request)).status).toBe(422);
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse(ticket.expiresAt) + 1);
    try {
      expect((await transport(request)).body).toMatchObject({ code: 'UPLOAD_EXPIRED' });
    } finally {
      vi.restoreAllMocks();
    }
  });
  it('resolves only ready public images and refuses publishing a new course without metadata', async () => {
    const { admin } = setup();
    const book = await admin.product('DEV-book');
    await expect(
      admin.saveProduct(null, {
        ...book,
        version: 0,
        contentBlocks: [{ type: 'IMAGE', assetId: 'missing' }],
      }),
    ).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    const course = await admin.saveProduct(null, {
      ...book,
      version: 0,
      type: 'COURSE',
      categoryIds: ['DEV-category-COURSE'],
    });
    expect(course).not.toHaveProperty('book');
    await expect(admin.setProductPublished(course.id, true, course.version)).rejects.toMatchObject({
      code: 'INVALID_STATE',
    });
  });
  it('creates hidden drafts, publishes and preserves off-sale detail and version conflicts', async () => {
    const { admin, store } = setup();
    const source = await admin.product('DEV-book');
    const draft = await admin.saveProduct(null, { ...source, version: 0, title: 'New book' });
    expect(store.products.some((p) => p.id === draft.id)).toBe(false);
    await expect(admin.setProductPublished(draft.id, false, draft.version)).rejects.toMatchObject({
      code: 'INVALID_STATE',
    });
    const published = await admin.setProductPublished(draft.id, true, draft.version);
    expect(store.products.find((p) => p.id === draft.id)?.allowedActions).toEqual(['BUY']);
    await expect(admin.saveProduct(draft.id, draft)).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
    });
    await admin.setProductPublished(draft.id, false, published.version);
    expect(store.products.find((p) => p.id === draft.id)).toMatchObject({
      status: 'OFF_SALE',
      allowedActions: [],
    });
  });
  it('supports category paging, enabled visibility, and article state changes', async () => {
    const { admin, store } = setup();
    const category = await admin.saveCategory(null, {
      version: 0,
      type: 'BOOK',
      name: 'New',
      parentId: null,
      sortNo: 2,
      enabled: true,
    });
    expect((await admin.categories({ keyword: 'New', pageSize: 1 })).total).toBe(1);
    await admin.saveCategory(category.id, { ...category, enabled: false });
    expect(store.categories.some((c) => c.id === category.id)).toBe(false);
    const article = await admin.saveArticle(null, {
      version: 0,
      title: 'Draft',
      coverAssetId: null,
      contentBlocks: [{ type: 'TEXT', text: 'body' }],
      status: 'DRAFT',
    });
    expect(store.articles.some((a) => a.id === article.id)).toBe(false);
    const published = await admin.saveArticle(article.id, { ...article, status: 'PUBLISHED' });
    expect(store.articles.find((a) => a.id === article.id)?.title).toBe('Draft');
    await admin.saveArticle(article.id, { ...published, status: 'UNPUBLISHED' });
    expect(store.articles.some((a) => a.id === article.id)).toBe(false);
  });
  it('requires transfer before complete and enforces single-use credentials and disable versions', async () => {
    const { admin, transport } = setup();
    const ticket = await admin.presign({
      name: 'a.png',
      mime: 'image/png',
      bytes: 3,
      purpose: 'PUBLIC_IMAGE',
    });
    await expect(admin.complete(ticket.id)).rejects.toMatchObject({ code: 'FILE_NOT_READY' });
    const request = {
      url: ticket.uploadUrl,
      method: 'PUT' as const,
      headers: { 'X-Upload-Token': ticket.uploadToken },
      body: new Uint8Array([1, 2, 3]),
    };
    expect((await transport(request)).status).toBe(200);
    expect((await transport(request)).status).toBe(409);
    const asset = await admin.complete(ticket.id);
    expect(asset.status).toBe('READY');
    expect(await admin.complete(ticket.id)).toEqual(asset);
    const disabled = await admin.disableAsset(asset.id, asset.version, 'remove');
    expect(disabled.publicUrl).toBeNull();
    await expect(admin.disableAsset(asset.id, asset.version, 'stale')).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
    });
  });
});
