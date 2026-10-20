import { expect, it } from 'vitest';
import { createClient } from '../src/http';
import { createSession } from '../src/session';
import { createPublicConfigurationApi } from '../src/content/public-config';
import { createCatalogApi } from '../src/content/api';

it('reads public agreement references anonymously without clearing an existing session on failure', async () => {
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.set({
    accessToken: 'revoked',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: '1', nickname: '用户', distributor: false },
  });
  const requests: unknown[] = [];
  let fail = false;
  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: async (request) => {
      requests.push(request);
      return fail
        ? {
            status: 401,
            body: { code: 'UNAUTHORIZED', message: 'not allowed', requestId: 'test', data: null },
          }
        : {
            status: 200,
            body: {
              code: 'OK',
              message: 'OK',
              requestId: 'test',
              data: {
                agreementVersions: { terms: '2', privacy: '3' },
                agreementLinks: {
                  terms: { title: '用户协议', version: '2', articleId: '11' },
                  privacy: { title: '隐私政策', version: '3', articleId: '12' },
                },
              },
            },
          };
    },
  });
  const api = createPublicConfigurationApi(client);
  expect((await api.get()).agreementLinks?.privacy).toEqual({
    title: '隐私政策',
    version: '3',
    articleId: '12',
  });
  fail = true;
  await expect(api.get()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  expect(session.get()?.accessToken).toBe('revoked');
  for (const request of requests) {
    expect(request).toMatchObject({ url: '/api/v1/config/public', method: 'GET' });
    expect((request as { headers: unknown }).headers).not.toHaveProperty('Authorization');
  }
});

it('opens public agreement article content without sending an invalid old bearer', async () => {
  const session = createSession({ get: () => null, set: () => {}, remove: () => {} });
  session.set({
    accessToken: 'old-token',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: '1', nickname: '用户', distributor: false },
  });
  let authorization: unknown;
  const client = createClient({
    baseUrl: '/api/v1',
    session,
    transport: async (request) => {
      authorization = request.headers.Authorization;
      expect(request.url).toBe('/api/v1/articles/11');
      return {
        status: 200,
        body: {
          code: 'OK',
          message: 'OK',
          requestId: 'article',
          data: {
            id: '11',
            title: '用户协议',
            coverUrl: null,
            contentBlocks: [{ type: 'TEXT', text: '真实协议正文' }],
          },
        },
      };
    },
  });
  const article = await createCatalogApi(client).article('11');
  const block = article.contentBlocks[0];
  expect(block).toMatchObject({ type: 'TEXT', text: '真实协议正文' });
  expect(authorization).toBeUndefined();
});
