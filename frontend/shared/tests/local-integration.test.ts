import { describe, expect, it } from 'vitest';
import { readEnvironment, usesDevelopmentLogin } from '../src/environment';

const input = {
  mode: 'development',
  provider: 'http',
  apiBaseUrl: 'http://localhost:8080/api/v1',
  uploadBaseUrl: 'http://localhost:8080',
  imageBaseUrl: 'https://cdn.example.com/images',
  videoBaseUrl: 'http://localhost:8080/video',
  developmentProxy: 'true',
  browserOrigin: 'http://127.0.0.1:5173',
};

describe('local HTTP integration', () => {
  it('sends browser API and local media through the frontend origin while preserving external media', () => {
    const env = readEnvironment(input);
    expect(env.apiBaseUrl).toBe('http://127.0.0.1:5173/api/v1');
    expect(env.uploadBaseUrl).toBe('http://127.0.0.1:5173');
    expect(env.imageBaseUrl).toBe('https://cdn.example.com/images');
    expect(env.videoBaseUrl).toBe('http://127.0.0.1:5173/video');
  });

  it.each([
    { browserOrigin: undefined },
    { developmentProxy: 'false' },
    { provider: 'mock' },
    { mode: 'test' },
  ])('keeps configured backend URLs without a browser development proxy: %j', (change) => {
    expect(readEnvironment({ ...input, ...change }).apiBaseUrl).toBe(
      'http://localhost:8080/api/v1',
    );
  });

  it('keeps production endpoints even if a local flag is accidentally present', () => {
    expect(
      readEnvironment({
        ...input,
        mode: 'production',
        apiBaseUrl: 'https://api.example.com/api/v1',
        uploadBaseUrl: 'https://api.example.com',
        videoBaseUrl: 'https://api.example.com',
      }).apiBaseUrl,
    ).toBe('https://api.example.com/api/v1');
  });

  it.each([
    ['development', 'http', 'true', true],
    ['test', 'http', 'true', true],
    ['development', 'http', undefined, false],
    ['development', 'http', 'false', false],
    ['development', 'mock', undefined, true],
    ['production', 'http', 'true', false],
  ])(
    'selects development login only in explicit development contexts (%s/%s/%s)',
    (mode, provider, flag, expected) => {
      expect(usesDevelopmentLogin({ mode, provider }, flag)).toBe(expected);
    },
  );
});
