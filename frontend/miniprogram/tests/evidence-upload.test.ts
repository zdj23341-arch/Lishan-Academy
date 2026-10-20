// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import EvidenceUpload from '../src/components/EvidenceUpload.vue';
import { session as componentSession } from '../src/api/session';
const hooks = vi.hoisted(() => ({ request: vi.fn(), choose: vi.fn() }));
vi.mock('../src/api/session', async () => {
  const { createSession } = await import('../../shared/src/session');
  return { session: createSession({ get: () => null, set() {}, remove() {} }) };
});
vi.mock('../src/api/client', () => ({
  client: { request: hooks.request },
  environment: { provider: 'real', apiBaseUrl: 'https://api.example/api' },
}));
import { createSession } from '../../shared/src/session';
import type { AdminAsset, UploadTicket } from '../../shared/src/content/admin-types';
import {
  createEvidenceUpload,
  evidenceFile,
  createEvidenceTransfer,
  chooseEvidence,
  evidenceServices,
} from '../src/api/evidence';

const png = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]).buffer;
const ticket: UploadTicket = {
  id: 'e1',
  uploadUrl: '/upload/e1',
  uploadToken: 'secret',
  expiresAt: '2099-01-01T00:00:00Z',
};
const asset = { id: 'e1', status: 'READY' } as AdminAsset;
function actor() {
  const session = createSession({ get: () => null, set() {}, remove() {} });
  session.set({
    accessToken: 'alice',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 'alice', nickname: 'Alice', distributor: false },
  });
  return session;
}
describe('customer evidence upload', () => {
  it('uses uni.request PUT with an ArrayBuffer on WeChat, without multipart fields', async () => {
    vi.stubGlobal('window', undefined);
    const request = vi.fn(({ success }: { success: (value: unknown) => void }) =>
      success({ statusCode: 204, data: null }),
    );
    vi.stubGlobal('uni', { request });
    const services = await evidenceServices(actor());
    await services.transfer(ticket, evidenceFile(png));
    expect(request.mock.calls[0]?.[0]).toMatchObject({
      method: 'PUT',
      data: png,
      header: {
        'Content-Type': 'application/octet-stream',
        'X-Upload-Token': 'secret',
        Authorization: 'Bearer alice',
      },
    });
    expect(request.mock.calls[0]?.[0]).not.toHaveProperty('filePath');
    expect(request.mock.calls[0]?.[0]).not.toHaveProperty('formData');
    vi.unstubAllGlobals();
  });
  it('emits only the completed ID after an H5 raw upload and sends no scan declarations', async () => {
    componentSession.set(actor().get()!);
    hooks.request.mockReset();
    hooks.request.mockImplementation(async (path: string) =>
      path === '/files/presign' ? ticket : asset,
    );
    const fetcher = vi.fn(async () => ({ ok: true, arrayBuffer: async () => png }));
    vi.stubGlobal('fetch', fetcher);
    vi.stubGlobal('uni', {
      chooseImage: ({ success }: { success: (value: unknown) => void }) =>
        success({ tempFilePaths: ['blob:local'], tempFiles: [] }),
    });
    const wrapper = mount(EvidenceUpload);
    await wrapper.find('button').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('ready')).toEqual([['e1']]);
    expect(hooks.request.mock.calls).toEqual([
      [
        '/files/presign',
        {
          method: 'POST',
          body: {
            name: 'evidence.png',
            mime: 'image/png',
            bytes: 8,
            purpose: 'AFTERSALE_EVIDENCE',
          },
        },
      ],
      ['/files/e1/complete', { method: 'POST', body: {} }],
    ]);
    expect(fetcher.mock.calls[1]).toEqual([
      'https://api.example/upload/e1',
      expect.objectContaining({
        method: 'PUT',
        body: expect.any(Blob),
        redirect: 'error',
        credentials: 'omit',
      }),
    ]);
    expect(wrapper.find('.evidence-preview').exists()).toBe(false);
    wrapper.unmount();
    vi.unstubAllGlobals();
    hooks.request.mockReset();
  });
  it('reads WeChat filesystem bytes without fabricated JPEG metadata', async () => {
    vi.stubGlobal('window', undefined);
    vi.stubGlobal('uni', {
      chooseImage: ({ success }: { success: (value: unknown) => void }) =>
        success({ tempFilePaths: ['wxfile://temporary'], tempFiles: [{ size: 8 }] }),
      getFileSystemManager: () => ({
        readFile: ({ success }: { success: (value: unknown) => void }) => success({ data: png }),
      }),
    });
    const selected = await chooseEvidence();
    expect(selected.file.source).toBe(png);
    expect(selected.file.mime).toBe('image/png');
    vi.unstubAllGlobals();
  });
  it.each(['transfer', 'complete'] as const)(
    'suppresses stale %s completion after account replacement or disposal',
    async (stage) => {
      const session = actor();
      let release!: () => void;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      const complete = vi.fn(async () => {
        if (stage === 'complete') await gate;
        return asset;
      });
      const upload = createEvidenceUpload(session, {
        presign: async () => ticket,
        transfer: async () => {
          if (stage === 'transfer') await gate;
        },
        complete,
      });
      const phases: string[] = [];
      upload.subscribe((state) => phases.push(state.phase));
      const pending = upload.start(evidenceFile(png));
      await flushPromises();
      if (stage === 'transfer')
        session.set({
          ...actor().get()!,
          accessToken: 'bob',
          user: { id: 'bob', nickname: 'Bob', distributor: false },
        });
      else upload.dispose();
      release();
      await expect(pending).rejects.toThrow();
      expect(phases).not.toContain('READY');
      expect(phases.at(-1)).toBe('IDLE');
      if (stage === 'transfer') expect(complete).not.toHaveBeenCalled();
      await expect(upload.retry()).rejects.toThrow();
      upload.dispose();
    },
  );
  it('clears a pending image selection after logout without uploading or emitting success', async () => {
    componentSession.set(actor().get()!);
    let selected!: (value: unknown) => void;
    vi.stubGlobal('uni', {
      chooseImage: ({ success }: { success: typeof selected }) => {
        selected = success;
      },
    });
    const wrapper = mount(EvidenceUpload);
    await wrapper.find('button').trigger('click');
    await flushPromises();
    componentSession.clear();
    selected({ tempFilePaths: ['blob:test'], tempFiles: [] });
    await flushPromises();
    expect(wrapper.emitted('ready')).toBeUndefined();
    expect(wrapper.find('.evidence-preview').exists()).toBe(false);
    expect(hooks.request).not.toHaveBeenCalled();
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
  it('derives metadata from bytes and rejects unsupported, mismatched or oversized images', () => {
    expect(evidenceFile(png, 'temporary')).toMatchObject({
      name: 'evidence.png',
      mime: 'image/png',
      bytes: 8,
      purpose: 'AFTERSALE_EVIDENCE',
    });
    expect(() => evidenceFile(png, 'wrong.jpg')).toThrow();
    expect(() => evidenceFile(new ArrayBuffer(12), 'x.png')).toThrow();
    const large = new Uint8Array(10 * 1024 * 1024 + 1);
    large.set(new Uint8Array(png));
    expect(() => evidenceFile(large.buffer, 'x.png')).toThrow();
  });
  it('deduplicates starts, retries with a new ticket, and accepts only READY', async () => {
    const presign = vi.fn(async () => ticket);
    const complete = vi
      .fn()
      .mockResolvedValueOnce({ ...asset, status: 'UPLOADED' })
      .mockResolvedValue(asset);
    const upload = createEvidenceUpload(actor(), { presign, transfer: async () => {}, complete });
    const first = upload.start(evidenceFile(png));
    expect(upload.start(evidenceFile(png))).toBe(first);
    await expect(first).rejects.toThrow();
    await expect(upload.retry()).resolves.toMatchObject({ id: 'e1' });
    expect(presign).toHaveBeenCalledTimes(2);
    upload.dispose();
  });
  it('clears retry and suppresses later stages after session change', async () => {
    const session = actor();
    let resolve!: (value: UploadTicket) => void;
    const transfer = vi.fn(async () => {});
    const upload = createEvidenceUpload(session, {
      presign: () =>
        new Promise((r) => {
          resolve = r;
        }),
      transfer,
      complete: async () => asset,
    });
    const states: string[] = [];
    upload.subscribe((s) => states.push(s.phase));
    const pending = upload.start(evidenceFile(png));
    await Promise.resolve();
    session.clear();
    resolve(ticket);
    await expect(pending).rejects.toThrow();
    expect(transfer).not.toHaveBeenCalled();
    expect(states.at(-1)).toBe('IDLE');
    await expect(upload.retry()).rejects.toThrow();
    upload.dispose();
  });
  it('sends raw PUT bytes with ticket token and scopes bearer to API origin', async () => {
    const requests: unknown[] = [];
    const transfer = createEvidenceTransfer({
      apiBaseUrl: 'https://api.example/api',
      accessToken: () => 'alice',
      send: async (r) => {
        requests.push(r);
        return { status: 204, body: null };
      },
    });
    const file = evidenceFile(png);
    await transfer(ticket, file);
    expect(requests[0]).toMatchObject({
      method: 'PUT',
      body: png,
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Upload-Token': 'secret',
        Authorization: 'Bearer alice',
      },
    });
    await transfer({ ...ticket, uploadUrl: 'https://storage.example/e1' }, file);
    expect((requests[1] as { headers: object }).headers).not.toHaveProperty('Authorization');
    await expect(
      transfer({ ...ticket, uploadUrl: 'https://user:pass@storage.example/e1' }, file),
    ).rejects.toThrow();
    await expect(
      transfer({ ...ticket, uploadUrl: 'http://storage.example/e1' }, file),
    ).rejects.toThrow();
  });
});
