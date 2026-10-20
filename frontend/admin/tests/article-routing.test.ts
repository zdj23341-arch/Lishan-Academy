// @vitest-environment jsdom
import { beforeAll, describe, expect, it, vi } from 'vitest';

let router: typeof import('../src/router').router;

beforeAll(async () => {
  vi.stubEnv('VITE_PROVIDER', 'mock');
  vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/api/v1');
  ({ router } = await import('../src/router'));
});

describe('article list and editor routes', () => {
  it('keeps the list at the collection route and sends an article id to the editor route', () => {
    const paths = router.getRoutes().map((route) => route.path);
    expect(paths).toContain('/content/articles');
    expect(paths).toContain('/content/articles/:id');
  });
});
