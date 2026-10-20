import { describe, expect, it } from 'vitest';
import { createClient, createSession, type Transport } from '../src/index';
import {
  productWrite,
  validateHomeDraft,
  validateHomePublish,
  moveItem,
} from '../src/content/validation';
import { createUploadController } from '../src/content/upload';
import { createAdminContentApi } from '../src/content/admin-api';
import type { AdminAsset, HomeDraft, ProductForm } from '../src/content/admin-types';

const form: ProductForm = {
  version: 0,
  type: 'BOOK',
  title: '作文训练册',
  subtitle: '',
  price: '39.90',
  originalPrice: '49.00',
  coverAssetId: 'cover1',
  categoryIds: ['c1'],
  contentBlocks: [{ type: 'TEXT', text: '介绍' }],
  quantityLimit: 5,
  shippingSummary: '快递配送',
  durationMonths: 6,
  sortNo: 0,
};
const ready: AdminAsset = {
  id: 'asset1',
  version: 1,
  name: '试卷.pdf',
  extension: 'PDF',
  mime: 'application/pdf',
  bytes: 4,
  purpose: 'PAPER_ATTACHMENT',
  status: 'READY',
  scanStatus: 'CLEAN',
  publicUrl: null,
};
const file = {
  name: '试卷.pdf',
  mime: 'application/pdf',
  bytes: 4,
  purpose: 'PAPER_ATTACHMENT' as const,
  source: new Uint8Array([1, 2, 3, 4]),
};
const ticket = {
  id: 'asset1',
  uploadUrl: 'https://storage.example/upload',
  uploadToken: 'temporary-ticket',
  expiresAt: '2099-01-01T00:00:00Z',
};

describe('content form boundaries', () => {
  it('uses integer fen and excludes fields for unrelated product types', () => {
    expect(productWrite(form)).toEqual({
      version: 0,
      type: 'BOOK',
      title: '作文训练册',
      subtitle: null,
      priceFen: 3990,
      originalPriceFen: 4900,
      coverAssetId: 'cover1',
      categoryIds: ['c1'],
      contentBlocks: [{ type: 'TEXT', text: '介绍' }],
      book: { quantityLimit: 5, shippingSummary: '快递配送' },
      sortNo: 0,
    });
    const course = productWrite({ ...form, type: 'COURSE' });
    expect(course).not.toHaveProperty('book');
    expect(course).not.toHaveProperty('membership');
  });
  it('rejects zero price, inverted prices and invalid quantities', () => {
    expect(() => productWrite({ ...form, price: '0' })).toThrow();
    expect(() => productWrite({ ...form, originalPrice: '1' })).toThrow();
    expect(() => productWrite({ ...form, quantityLimit: 100 })).toThrow();
  });
  it('counts enabled banners and shortcuts and rejects unsafe targets', () => {
    const draft: HomeDraft = { revision: 1, banners: [], shortcuts: [], sections: [] };
    expect(validateHomePublish(draft)).toHaveProperty('banners');
    expect(validateHomePublish(draft)).toHaveProperty('shortcuts');
    draft.banners = Array.from({ length: 3 }, (_, i) => ({
      id: `b${i}`,
      title: '轮播',
      assetId: 'a',
      sortNo: i,
      enabled: true,
      target: { type: 'PRODUCT', id: 'p1', filters: {} },
    }));
    draft.shortcuts = Array.from({ length: 8 }, (_, i) => ({
      id: `s${i}`,
      label: '入口',
      assetId: 'a',
      sortNo: i,
      enabled: true,
      target: { type: 'PRODUCT_LIST', id: null, filters: { type: 'BOOK' } },
    }));
    expect(validateHomePublish(draft)).toEqual({});
    draft.shortcuts[0]!.target.type = 'EXTERNAL' as 'PRODUCT';
    expect(Object.keys(validateHomePublish(draft))).not.toHaveLength(0);
    draft.shortcuts[0]!.target.type = 'PRODUCT_LIST';
    draft.shortcuts.splice(6);
    expect(validateHomePublish(draft)).toEqual({});
  });
  it('requires paper IDs for a published paper grid and accepts the backend section shape', () => {
    const draft: HomeDraft = {
      revision: 1,
      banners: [],
      shortcuts: [],
      sections: [
        {
          id: 'papers',
          type: 'PAPER_GRID',
          title: '试卷专栏',
          sortNo: 0,
          enabled: true,
          moreTarget: { type: 'PAPER_LIST', id: null, filters: {} },
          productIds: [],
          articleId: null,
          paperIds: [],
        },
      ],
    };
    expect(validateHomeDraft(draft, true)).toHaveProperty('sections.0.paperIds');
    draft.sections[0]!.paperIds = ['paper-1'];
    expect(validateHomeDraft(draft, true)).not.toHaveProperty('sections.0.paperIds');
  });
  it('reorders immutably and rejects out-of-range moves', () => {
    const items = ['a', 'b', 'c'];
    expect(moveItem(items, 0, 2)).toEqual(['b', 'c', 'a']);
    expect(items).toEqual(['a', 'b', 'c']);
    expect(() => moveItem(items, -1, 2)).toThrow();
  });
});

describe('authorized upload lifecycle', () => {
  it('transfers selected bytes before server completion and only binds READY', async () => {
    const events: string[] = [];
    const uploader = createUploadController({
      presign: async (input) => {
        expect(input).toEqual({
          name: '试卷.pdf',
          mime: 'application/pdf',
          bytes: 4,
          purpose: 'PAPER_ATTACHMENT',
        });
        events.push('presign');
        return ticket;
      },
      transfer: async (t, f) => {
        expect(t.uploadToken).toBe('temporary-ticket');
        expect(f.source).toBe(file.source);
        events.push('transfer');
      },
      complete: async (id) => {
        expect(id).toBe('asset1');
        events.push('complete');
        return ready;
      },
    });
    expect(await uploader.start(file)).toEqual(ready);
    expect(events).toEqual(['presign', 'transfer', 'complete']);
    expect(uploader.state.phase).toBe('READY');
  });
  it('does not complete a failed transfer, and retry obtains a fresh ticket', async () => {
    let transfers = 0;
    let tickets = 0;
    let completions = 0;
    const uploader = createUploadController({
      presign: async () => ({ ...ticket, uploadToken: `ticket-${++tickets}` }),
      transfer: async () => {
        if (++transfers === 1) throw new Error('offline');
      },
      complete: async () => {
        completions++;
        return ready;
      },
    });
    await expect(uploader.start(file)).rejects.toThrow('offline');
    expect(completions).toBe(0);
    expect(uploader.state.asset).toBeNull();
    await uploader.retry();
    expect(tickets).toBe(2);
    expect(uploader.state.asset?.id).toBe('asset1');
  });
  it('does not treat rejected metadata as a usable file', async () => {
    const uploader = createUploadController({
      presign: async () => ticket,
      transfer: async () => {},
      complete: async () => ({ ...ready, status: 'REJECTED' }),
    });
    await expect(uploader.start(file)).rejects.toThrow();
    expect(uploader.state.phase).toBe('FAILED');
    expect(uploader.state.asset).toBeNull();
  });
  it('validates extension, MIME and size before requesting credentials', async () => {
    let called = false;
    const uploader = createUploadController({
      presign: async () => {
        called = true;
        return ticket;
      },
      transfer: async () => {},
      complete: async () => ready,
    });
    await expect(
      uploader.start({ ...file, name: 'run.exe', mime: 'application/octet-stream' }),
    ).rejects.toThrow();
    expect(called).toBe(false);
    await expect(uploader.start({ ...file, bytes: 0 })).rejects.toThrow();
    expect(called).toBe(false);
  });
});

describe('admin API permissions and conflicts', () => {
  function api(transport: Transport, permissions: string[]) {
    const client = createClient({
      baseUrl: 'https://api.example/api/v1',
      session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
      transport,
    });
    return createAdminContentApi(client, () => permissions);
  }
  it.each([true, false])(
    'sends a valid audited lifecycle request (publish=%s)',
    async (publish) => {
      const service = api(
        async (req) => {
          const body = req.body as { version: number; reason?: string };
          if (req.method !== 'POST' || !body.reason?.trim() || body.reason.length > 500)
            return {
              status: 400,
              body: {
                code: 'VALIDATION_ERROR',
                message: 'Invalid request fields',
                data: null,
                requestId: 'lifecycle',
              },
            };
          expect(body.version).toBe(3);
          expect(req.url).toBe(
            `https://api.example/api/v1/admin/products/7/${publish ? 'publish' : 'unpublish'}`,
          );
          return {
            status: 200,
            body: {
              code: 'OK',
              message: 'OK',
              data: { id: '7', version: 4, status: publish ? 'ON_SALE' : 'OFF_SALE' },
              requestId: 'lifecycle',
            },
          };
        },
        ['CONTENT_EDIT'],
      );
      await expect(service.setProductPublished('7', publish, 3)).resolves.toMatchObject({
        id: '7',
        version: 4,
        status: publish ? 'ON_SALE' : 'OFF_SALE',
      });
    },
  );
  it('denies writes without content permission before transport', async () => {
    let calls = 0;
    const service = api(async () => {
      calls++;
      return { status: 200, body: { code: 'OK', message: '', data: null, requestId: 'r' } };
    }, []);
    await expect(service.saveProduct(null, productWrite(form))).rejects.toMatchObject({
      code: 'FORBIDDEN',
    });
    expect(calls).toBe(0);
  });
  it('forwards a draft revision and surfaces 409 without mutating input', async () => {
    const draft: HomeDraft = { revision: 3, banners: [], shortcuts: [], sections: [] };
    const service = api(
      async (req) => {
        expect(req.method).toBe('PUT');
        expect(req.body).toEqual(draft);
        return {
          status: 409,
          body: {
            code: 'VERSION_CONFLICT',
            message: '已被他人修改',
            data: null,
            requestId: 'conflict',
          },
        };
      },
      ['CONTENT_EDITOR'],
    );
    await expect(service.saveHome(draft)).rejects.toMatchObject({
      code: 'VERSION_CONFLICT',
      requestId: 'conflict',
    });
    expect(draft.revision).toBe(3);
  });
});
