import { expect, it } from 'vitest';
import { resolveServiceUrls } from '../src/service-urls';

it('resolves backend media and capability URLs for native clients without changing text, external URLs or signed queries', () => {
  const data = {
    title: '/api/v1/is-ordinary-text',
    coverUrl: '/api/v1/assets/1/content',
    uploadUrl: '/api/v1/local-uploads/2',
    items: [
      { url: '/api/v1/protected/content?token=a%2Fb&expires=123' },
      { iconUrl: 'https://cdn.example.com/icon.png' },
      { imageUrl: '/static/mock-image.svg' },
    ],
    optional: null,
  };
  expect(resolveServiceUrls(data, 'http://localhost:8080/api/v1')).toEqual({
    title: '/api/v1/is-ordinary-text',
    coverUrl: 'http://localhost:8080/api/v1/assets/1/content',
    uploadUrl: 'http://localhost:8080/api/v1/local-uploads/2',
    items: [
      { url: 'http://localhost:8080/api/v1/protected/content?token=a%2Fb&expires=123' },
      { iconUrl: 'https://cdn.example.com/icon.png' },
      { imageUrl: '/static/mock-image.svg' },
    ],
    optional: null,
  });
  expect(data.coverUrl).toBe('/api/v1/assets/1/content');
});
