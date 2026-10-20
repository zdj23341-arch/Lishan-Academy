import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSession } from '../src/session';
import { ApiError, createClient, type TransportRequest } from '../src/http';
import { createLearningApi } from '../src/learning/api';
import * as files from '../src/learning/files';
import type { AssetSummary, MembershipView } from '../src/contracts';
import type { FileAuthorization } from '../src/learning/types';

const pdf: AssetSummary = {
  id: 'pdf',
  name: 'Paper.pdf',
  extension: 'PDF',
  mime: 'application/pdf',
  bytes: 20,
};
const authorization: FileAuthorization = {
  file: pdf,
  url: 'https://files.test/paper?signature=secret',
  expiresAt: '2026-10-07T00:01:00Z',
};
const membership: MembershipView = {
  level: 'STANDARD',
  state: 'ACTIVE',
  startsAt: null,
  expiresAt: '2026-12-31T00:00:00Z',
  canDownload: true,
  plans: [],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function setup(loggedIn = true, mockAssetUrls?: readonly string[]) {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-07T00:00:00Z'));
  const stored = new Map<string, string>();
  const session = createSession({
    get: (key) => stored.get(key) ?? null,
    set: (key, value) => {
      stored.set(key, value);
    },
    remove: (key) => {
      stored.delete(key);
    },
  });
  const login = (id: string, expiresAt = '2099-01-01T00:00:00Z') =>
    session.set({ accessToken: id, expiresAt, user: { id, nickname: id, distributor: false } });
  if (loggedIn) login('one');
  const api = {
    membership: vi.fn(async () => structuredClone(membership)),
    authorize: vi.fn(async () => structuredClone(authorization)),
  };
  const platform = { preview: vi.fn(async () => {}), save: vi.fn(async () => {}), clear: vi.fn() };
  const controller = files.createProtectedFileController({
    api,
    session,
    platform,
    ...(mockAssetUrls ? { mockAssetUrls } : {}),
  });
  return { controller, api, platform, session, login, stored };
}
afterEach(() => vi.useRealTimers());

describe('protected file controller', () => {
  it('sends the requested authorization action to the matching platform boundary', async () => {
    const { controller: c, api, platform } = setup();
    await c.open('paper', pdf, 'DOWNLOAD');
    expect(api.authorize).toHaveBeenCalledWith('paper', 'pdf', 'DOWNLOAD');
    expect(platform.save).toHaveBeenCalledWith(authorization);
    expect(platform.preview).not.toHaveBeenCalled();
    expect(c.state).toMatchObject({ busy: false, error: null });
    await c.open('paper', pdf, 'PREVIEW');
    expect(platform.preview).toHaveBeenCalledWith(authorization);
    expect(api.authorize).toHaveBeenCalledTimes(2);
  });
  it('requires a session before requesting protected access', async () => {
    const { controller: c, api, platform } = setup(false);
    await c.open('paper', pdf, 'PREVIEW');
    expect(api.authorize).not.toHaveBeenCalled();
    expect(platform.preview).not.toHaveBeenCalled();
    expect(c.state.errorCode).toBe('UNAUTHORIZED');
  });
  it.each(['UNAUTHORIZED', 'FORBIDDEN', 'MEMBERSHIP_EXPIRED', 'FILE_NOT_READY'])(
    'does not transfer on API %s, strips raw errors and allows reauthorization',
    async (code) => {
      const { controller: c, api, platform, stored } = setup();
      api.authorize.mockRejectedValueOnce(
        new ApiError(code, authorization.url, authorization.url, { url: authorization.url }),
      );
      await c.open('paper', pdf, 'DOWNLOAD');
      expect(platform.save).not.toHaveBeenCalled();
      expect(c.state.error).toBeTruthy();
      expect(JSON.stringify(c.state)).not.toContain('signature');
      expect(JSON.stringify([...stored])).not.toContain('signature');
      await c.open('paper', pdf, 'DOWNLOAD');
      expect(api.authorize).toHaveBeenCalledTimes(2);
      expect(platform.save).toHaveBeenCalledOnce();
    },
  );
  it.each(['2026-10-07T00:00:00Z', 'invalid', '2026-10-07T00:01:00'])(
    'rejects an expired or ambiguous authorization expiry %s',
    async (expiresAt) => {
      const { controller: c, api, platform } = setup();
      api.authorize.mockResolvedValue({ ...authorization, expiresAt });
      await c.open('paper', pdf, 'DOWNLOAD');
      expect(platform.save).not.toHaveBeenCalled();
      expect(c.state.errorCode).toBe('AUTHORIZATION_EXPIRED');
    },
  );
  it.each([
    { extension: 'EXE' },
    { mime: 'application/octet-stream' },
    { bytes: 0 },
    { bytes: -1 },
    { bytes: NaN },
  ])('rejects unsupported or inconsistent metadata before authorization: %j', async (change) => {
    const { controller: c, api, platform } = setup();
    await c.open('paper', { ...pdf, ...change }, 'PREVIEW');
    expect(api.authorize).not.toHaveBeenCalled();
    expect(platform.preview).not.toHaveBeenCalled();
    expect(c.state.error).toBeTruthy();
  });
  it('validates authoritative metadata and file identity again before transfer', async () => {
    const { controller: c, api, platform } = setup();
    api.authorize.mockResolvedValueOnce({ ...authorization, file: { ...pdf, id: 'different' } });
    await c.open('paper', pdf, 'PREVIEW');
    api.authorize.mockResolvedValueOnce({ ...authorization, file: { ...pdf, mime: 'image/png' } });
    await c.open('paper', pdf, 'PREVIEW');
    expect(platform.preview).not.toHaveBeenCalled();
  });
  it.each([
    'http://files.test/file',
    'javascript:alert(1)',
    '//files.test/file',
    'https://user:secret@files.test/a',
    '/fixture.pdf',
    'https://files.test/a#secret',
  ])('rejects unsupported or insecure URL %s', async (url) => {
    const { controller: c, api, platform } = setup();
    api.authorize.mockResolvedValue({ ...authorization, url });
    await c.open('paper', pdf, 'PREVIEW');
    expect(platform.preview).not.toHaveBeenCalled();
    expect(c.state.errorCode).toBe('INVALID_FILE_URL');
  });
  it('allows development HTTP tickets only on the configured API origin', () => {
    const localAuthorization = {
      ...authorization,
      url: 'http://localhost:8080/api/v1/protected-content/token',
      expiresAt: '2099-01-01T00:00:00Z',
    };
    const developmentOptions: files.ProtectedFileValidationOptions = {
      developmentHttpApiBaseUrl: 'http://localhost:8080/api/v1',
    };
    expect(files.validateFileAuthorization(localAuthorization, developmentOptions)).toBe('PDF');
    expect(() =>
      files.validateFileAuthorization(
        { ...localAuthorization, url: 'http://127.0.0.1:8080/api/v1/protected-content/token' },
        developmentOptions,
      ),
    ).toThrow(expect.objectContaining({ code: 'INVALID_FILE_URL' }));
  });
  it('allows only explicitly listed root-relative mock asset URLs', async () => {
    const { controller: c, api, platform } = setup(true, ['/mock-paper.pdf']);
    api.authorize.mockResolvedValueOnce({ ...authorization, url: '/mock-paper.pdf' });
    await c.open('paper', pdf, 'PREVIEW');
    expect(platform.preview).toHaveBeenCalledOnce();
    api.authorize.mockResolvedValueOnce({ ...authorization, url: '/other.pdf' });
    await c.open('paper', pdf, 'PREVIEW');
    expect(platform.preview).toHaveBeenCalledOnce();
  });
  it('coalesces repeated clicks for the whole active operation', async () => {
    const { controller: c, api, platform } = setup();
    const transfer = deferred<void>();
    platform.save.mockReturnValueOnce(transfer.promise);
    const one = c.open('paper', pdf, 'DOWNLOAD');
    await Promise.resolve();
    const two = c.open('paper', pdf, 'DOWNLOAD');
    expect(api.authorize).toHaveBeenCalledOnce();
    expect(c.state.busy).toBe(true);
    transfer.resolve();
    await Promise.all([one, two]);
    expect(platform.save).toHaveBeenCalledOnce();
  });
  it.each(['hide', 'clear', 'dispose', 'account'] as const)(
    'discards late authorization on %s',
    async (event) => {
      const { controller: c, api, platform, login } = setup();
      const response = deferred<FileAuthorization>();
      api.authorize.mockReturnValueOnce(response.promise);
      const pending = c.open('paper', pdf, 'PREVIEW');
      if (event === 'account') login('two');
      else c[event]();
      response.resolve(authorization);
      await pending;
      expect(platform.preview).not.toHaveBeenCalled();
      expect(platform.clear).toHaveBeenCalled();
      expect(c.state.busy).toBe(false);
      expect(c.state.fileId).toBeNull();
    },
  );
  it('clears platform handles automatically on URL or login expiry', async () => {
    const { controller: c, platform, login } = setup();
    login('short', '2026-10-07T00:00:20Z');
    await c.open('paper', pdf, 'PREVIEW');
    const before = platform.clear.mock.calls.length;
    await vi.advanceTimersByTimeAsync(20000);
    expect(platform.clear.mock.calls.length).toBeGreaterThan(before);
    expect(c.state.fileId).toBeNull();
  });
  it('expires the session while authorization is still pending and releases the busy operation', async () => {
    const { controller: c, api, platform, login } = setup();
    login('short', '2026-10-07T00:00:20Z');
    const response = deferred<FileAuthorization>();
    api.authorize.mockReturnValueOnce(response.promise);
    const pending = c.open('paper', pdf, 'PREVIEW');
    await vi.advanceTimersByTimeAsync(20000);
    expect(c.state.busy).toBe(false);
    expect(c.state.fileId).toBeNull();
    response.resolve(authorization);
    await pending;
    expect(platform.preview).not.toHaveBeenCalled();
  });
  it('retains no authorization in observable state and clears on URL expiry', async () => {
    const { controller: c, platform, stored } = setup();
    await c.open('paper', pdf, 'PREVIEW');
    expect(JSON.stringify(c.state)).not.toContain('https:');
    expect(JSON.stringify([...stored])).not.toContain('signature');
    const before = platform.clear.mock.calls.length;
    await vi.advanceTimersByTimeAsync(60000);
    expect(platform.clear.mock.calls.length).toBeGreaterThan(before);
    expect(c.state.fileId).toBeNull();
  });
  it('sanitizes rejected platform errors and retries with new authorization', async () => {
    const { controller: c, api, platform } = setup();
    platform.save.mockRejectedValueOnce(new Error(authorization.url));
    await c.open('paper', pdf, 'DOWNLOAD');
    expect(c.state.error).toBeTruthy();
    expect(JSON.stringify(c.state)).not.toContain('signature');
    await c.open('paper', pdf, 'DOWNLOAD');
    expect(api.authorize).toHaveBeenCalledTimes(2);
    expect(c.state.error).toBeNull();
  });
});

it('uses the real membership/me GET and encoded action-specific authorization POST at the HTTP boundary', async () => {
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  const requests: TransportRequest[] = [];
  const api = createLearningApi(
    createClient({
      baseUrl: '/api/v1',
      session,
      transport: async (request) => {
        requests.push(request);
        return {
          status: 200,
          body: {
            code: 'OK',
            message: '',
            requestId: 'test',
            data: request.method === 'GET' ? membership : authorization,
          },
        };
      },
    }),
  );
  expect(await api.membership()).toEqual(membership);
  expect(await api.authorize('paper/one', 'file one', 'DOWNLOAD')).toEqual(authorization);
  expect(requests).toMatchObject([
    { url: '/api/v1/membership/me', method: 'GET' },
    {
      url: '/api/v1/papers/paper%2Fone/files/file%20one/authorize',
      method: 'POST',
      body: { action: 'DOWNLOAD' },
    },
  ]);
});
