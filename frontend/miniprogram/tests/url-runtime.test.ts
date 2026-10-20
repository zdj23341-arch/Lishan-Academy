import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

describe.each([undefined, {}])('WeChat runtime with URL=%j', (missingUrl) => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubGlobal('URL', missingUrl);
    vi.stubGlobal('URLSearchParams', undefined);
    vi.stubGlobal('window', undefined);
    vi.stubEnv('MODE', 'development');
    vi.stubEnv('VITE_PROVIDER', 'http');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/api/v1');
    vi.stubEnv('VITE_UPLOAD_BASE_URL', 'http://localhost:8080');
    vi.stubEnv('VITE_IMAGE_BASE_URL', 'http://localhost:8080');
    vi.stubEnv('VITE_VIDEO_BASE_URL', 'http://localhost:8080');
    vi.stubEnv('VITE_DEV_PROXY', 'true');
    vi.stubGlobal('uni', { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {} });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('initializes the real native client and resolves an API response without browser globals', async () => {
    const requests: unknown[] = [];
    vi.stubGlobal('uni', {
      getStorageSync: () => '',
      setStorageSync() {},
      removeStorageSync() {},
      request: (request: { success(value: unknown): void }) => {
        requests.push(request);
        request.success({
          statusCode: 200,
          data: {
            code: 'OK',
            message: '',
            requestId: 'native',
            data: { coverUrl: '/api/v1/assets/1/content?signature=a%2Fb' },
          },
        });
      },
    });
    const { client, environment } = await import('../src/api/client');
    expect(environment.apiBaseUrl).toBe('http://localhost:8080/api/v1');
    await expect(client.request('/home')).resolves.toEqual({
      coverUrl: 'http://localhost:8080/api/v1/assets/1/content?signature=a%2Fb',
    });
    expect(requests).toHaveLength(1);
    expect(globalThis.URL).toBe(missingUrl);
  });

  it('keeps the H5 development proxy and external media origin behavior', async () => {
    const { readEnvironment } = await import('../../shared/src/environment');
    const result = readEnvironment({
      mode: 'development',
      provider: 'http',
      developmentProxy: 'true',
      browserOrigin: 'http://127.0.0.1:5173',
      apiBaseUrl: 'http://localhost:8080/api/v1',
      uploadBaseUrl: 'http://localhost:8080',
      imageBaseUrl: 'https://cdn.example.com/images',
    });
    expect(result).toMatchObject({
      apiBaseUrl: 'http://127.0.0.1:5173/api/v1',
      uploadBaseUrl: 'http://127.0.0.1:5173',
      imageBaseUrl: 'https://cdn.example.com/images',
    });
  });

  it('loads mock membership, learning pages and trial media with parsed query parameters', async () => {
    vi.stubEnv('VITE_PROVIDER', 'mock');
    const { client } = await import('../src/api/client');
    const { session } = await import('../src/api/session');
    const login = await client.request<import('../../shared/src/contracts').LoginView>(
      '/auth/dev-login',
      { method: 'POST', body: {} },
    );
    session.set(login);
    await expect(client.request('/membership/me')).resolves.toMatchObject({ state: 'NONE' });
    await expect(
      client.request('/learning/courses', { query: { page: 1, pageSize: 1 } }),
    ).resolves.toMatchObject({ page: 1, pageSize: 1 });
    await expect(
      client.request('/courses/DEV-course/chapters/DEV-course-trial/play-url'),
    ).resolves.toMatchObject({
      url: 'http://127.0.0.1:5173/course-sample.mp4?devChapter=DEV-course-trial',
    });
  });

  it('uploads native evidence with signed queries intact and never sends bearer to another origin', async () => {
    const { createEvidenceTransfer, evidenceFile } = await import('../src/api/evidence');
    const requests: import('../../shared/src/http').TransportRequest[] = [];
    const transfer = createEvidenceTransfer({
      apiBaseUrl: 'https://api.example:443/api/v1',
      accessToken: () => 'test-token',
      send: async (request) => {
        requests.push(request);
        return { status: 204, body: null };
      },
    });
    const file = evidenceFile(new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]).buffer);
    const ticket = {
      id: '1',
      uploadUrl: '/uploads/1?signature=a%2Fb&x=1',
      uploadToken: 'ticket',
      expiresAt: '2099-01-01T00:00:00Z',
    };
    await transfer(ticket, file);
    expect(requests[0]).toMatchObject({
      url: 'https://api.example/uploads/1?signature=a%2Fb&x=1',
      headers: { Authorization: 'Bearer test-token' },
    });
    await transfer({ ...ticket, uploadUrl: 'https://objects.example/1?signature=a%2Fb' }, file);
    expect(requests[1]?.headers).not.toHaveProperty('Authorization');
    for (const uploadUrl of [
      'https://user:pass@api.example/1',
      'http://objects.example/1',
      'https://api.example/1#fragment',
      'javascript:alert(1)',
    ]) {
      await expect(transfer({ ...ticket, uploadUrl }, file)).rejects.toMatchObject({
        code: 'INVALID_UPLOAD_URL',
      });
    }
    expect(requests).toHaveLength(2);
  });
});
